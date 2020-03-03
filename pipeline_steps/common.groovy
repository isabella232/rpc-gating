import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import groovy.json.JsonException
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.ghprb.extensions.build.GhprbCancelBuildsOnUpdate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.math.MathContext
import java.security.SecureRandom
import groovy.transform.Field

// Constants Governing the Weekly RE-Maintenance Window
@Field DayOfWeek maintDay = DayOfWeek.MONDAY
@Field Integer maintHour = 10 // UTC
@Field Duration maintDuration = Duration.ofHours(2)
@Field Integer daysInWeek = 7


void download_venv(){
  sh """#!/bin/bash -xeu
    REPO_BASE="http://rpc-repo.rackspace.com/rpcgating/venvs"
    cd ${env.WORKSPACE}
    pushd rpc-gating
      SHA=\$(git rev-parse HEAD)
    popd
    curl --fail -s "\${REPO_BASE}/rpcgatingvenv_\${SHA}.tbz" > venv.tbz
  """
  print("Venv Downloaded")
}
void install_ansible(){
  print("install_ansible")
  try{
    download_venv()
  }catch (e){
    print("Venv not found, kicking off Build-Gating-Venv to build it. Error: ${e}")
    build(
      job: "Build-Gating-Venv",
      wait: true,
      parameters: [
        [
          $class: 'StringParameterValue',
          name: 'RPC_GATING_BRANCH',
          value: env.RPC_GATING_BRANCH
        ]
      ]
    )
    sleep(time: 60, unit: "SECONDS")
    retry(3){
      try{
        download_venv()
      } catch (f) {
        print ("Post venv build download failed, pausing before retry")
        sleep(time: 60, unit: "SECONDS")
        throw f
      }
    }
  }
  sh """#!/bin/bash -eu
    cd ${env.WORKSPACE}
    echo "Unpacking Venv..."
    tar xjfp venv.tbz
    op=\$(cat .venv/original_venv_path) # Original Path
    np=\${PWD}/.venv                    # New Path
    grep -ri --files-with-match \$op \
      |while read f; do sed -i.bak "s|\$op|\$np|" \$f; done
    [[ -e .venv/lib64 ]] || {
      pushd .venv
        ln -s lib lib64
      popd
    }
    echo "Venv Unpack Complete"
    echo "Resetting python for the venv..."
    if which scl; then
      echo "CentOS node detected, copying in external python2 interpreter and setting PYTHONPATH in activate script"
      # CentOS 6 can take a hike, its glibc isn't new enough for python 2.7.12
      cp /opt/rh/python27/root/usr/bin/python .venv/bin/python
      # hack the selinux module into the venv
      cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib/python2.7/site-packages/ ||:
      # I'm not sure why this is needed, but I assume its due to a change in python's
      # default module search paths between 2.7.8 and 2.7.12
      echo "export PYTHONPATH=${env.WORKSPACE}/.venv/lib/python2.7/site-packages" >> .venv/bin/activate
    else
      if ! virtualenv --version &>/dev/null; then
        echo "Virtualenv binary not found. Installing it."
        # Get the distribution name
        if [[ -e /etc/lsb-release ]]; then
          source /etc/lsb-release
          DISTRO_RELEASE=\${DISTRIB_CODENAME}
        elif [[ -e /etc/os-release ]]; then
          source /etc/os-release
          DISTRO_RELEASE=\${UBUNTU_CODENAME}
        else
          echo "Unable to determine distribution due to missing lsb/os-release files."
          exit 1
        fi
        if [[ "\${DISTRO_RELEASE}" == "trusty" ]]; then
          apt-get install -y python-virtualenv
        else
          apt-get install -y python-virtualenv virtualenv
        fi
      fi
      VIRTUALENV_VERSION=\$(virtualenv --version 2>/dev/null)
      echo "Virtualenv version: \${VIRTUALENV_VERSION}"
      VIRTUALENV_MAJOR_VERSION=\$(echo \${VIRTUALENV_VERSION} | cut -d. -f1)
      VIRTUALENV_MINOR_VERSION=\$(echo \${VIRTUALENV_VERSION} | cut -d. -f2)
      VIRTUALENV_ARGS="--verbose --python=python2 --always-copy"
      if (( \${VIRTUALENV_MAJOR_VERSION} >= 13 )); then
        VIRTUALENV_ARGS+=" --no-pip --no-setuptools --no-wheel"
      fi
      if (( \${VIRTUALENV_MAJOR_VERSION} < 14 )); then
         VIRTUALENV_ARGS+=" --never-download"
      else
         VIRTUALENV_ARGS+=" --no-download"
      fi
      if (( \${VIRTUALENV_MAJOR_VERSION} <= 1 )) && (( \${VIRTUALENV_MINOR_VERSION} <= 7 )); then
         VIRTUALENV_ARGS+=" --no-site-packages"
      fi
      # The pre-built venv has symlinks from 'python2.7' and 'python2' to 'python'.
      # We need to remove those and the python binary in order for the virtualenv
      # python binary copy to work.
      echo "Removing previous python binaries."
      rm -vf .venv/bin/python .venv/bin/python2*
      echo "Resetting python binaries in virtualenv with the arguments: \${VIRTUALENV_ARGS}"
      virtualenv \${VIRTUALENV_ARGS} .venv
    fi
    echo "Venv python reset complete."
  """
}

/* Run ansible-galaxy within the rpc-gating venv
 * Args:
 *  args: list of string args to pass to ansible-galaxy
 */
def venvGalaxy(String[] args){
  sh """#!/bin/bash -x
    which scl && source /opt/rh/python27/enable
    set +x; . ${env.WORKSPACE}/.venv/bin/activate; set -x
    ansible-galaxy ${args.join(' ')}
  """
} //venvGalaxy

/* Run ansible-playbooks within the rpc-gating venv
 * Sadly the standard ansibleplaybook step doesn't allow specifying a custom
 * ansible path. It does allow selection of an ansible tool, but those are
 * statically configured in global jenkins config.
 *
 * Args:
 *  playbooks: list of playbook filenames
 *  vars: dict of vars to be passed to ansible as overrides
 *  args: list of string args to pass to ansible-playbook
 */
def venvPlaybook(Map args){
  withEnv(get_deploy_script_env()){
    if (!('vars' in args)){
      args.vars=[:]
    }
    if (!('args' in args)){
      args.args=[]
    }
    for (int i=0; i<args.playbooks.size(); i++){
      String playbook = args.playbooks[i]
      // randomised vars file path for parallel safety
      String vars_file="vars.${playbook.split('/')[-1]}.${rand_int_str()}"
      write_json(file: vars_file, obj: args.vars)
      sh """#!/bin/bash -x
        which scl && source /opt/rh/python27/enable
        set +x; . ${env.WORKSPACE}/.venv/bin/activate; set -x
        export ANSIBLE_CONFIG=${env.WORKSPACE}/rpc-gating/playbooks/ansible.cfg
        ansible-playbook ${args.args.join(' ')} -e@${vars_file} ${playbook}
      """
    } //for
  } //withenv
} //venvplaybook

def calc_ansible_forks(){
  String forks = sh (script: """#!/bin/bash
    CPU_NUM=\$(grep -c ^processor /proc/cpuinfo)
    if [ \${CPU_NUM} -lt "10" ]; then
      ANSIBLE_FORKS=\${CPU_NUM}
    else
      ANSIBLE_FORKS=10
    fi
    echo -n "\${ANSIBLE_FORKS}"
  """, returnStdout: true)
  print "Ansible forks: ${forks}"
  return forks
}

/* this is a func rather than a var, so that the linter doesn't try
to evaluate ${forks} and fail.
These vars should be set every time deploy.sh or test-upgrade is run
*/
List get_deploy_script_env(){
  String forks = calc_ansible_forks()
  return [
    'TERM=linux',
    "ANSIBLE_FORKS=${forks}",
  ]
}

/*
 * JsonSluperClassic and JsonOutput are not serializable, so they
 * can only be used in @NonCPS methods. However readFile and writeFile
 * cannotbe used in NonCPS methods, so reading and writing json
 * requires one function to handle the io, and another to do the.
 * conversion.
 *
 * JsonSluperClassic returns a serializable object, but JsonSlurper
 * does not. This makes Classic preferable for pipeline use.
 */
@NonCPS
def _parse_json_string(Map args){
  return (new JsonSlurperClassic()).parseText(args.json_text)
}

@NonCPS
def _write_json_string(Map args){
    return (new JsonOutput()).toJson(args.obj)
}

/* Read Json file and return object
 * Args:
 *  file: String path of file to read
 */
def parse_json(Map args){
    return this._parse_json_string(
      json_text: readFile(file: args.file)
    )
}

/* Write object to file as JSON
 * Args:
 *  file: String path of file to write
 *  obj: Object to translate into JSON
 */
def write_json(Map args){
  writeFile(
    file: args.file,
    text: this._write_json_string(obj: args.obj)
  )
}

/* Run a stage if the stage name is contained in an env var
 * Args:
 *   - stage_name: String name of this stage
 *   - stage: Closure to execute
 * Environment:
 *    - STAGES: String list of stages that should be run
 */
def conditionalStage(Map args){
  if (env.STAGES == null){
  throw new Exception(
    "ConditionalStage used without STAGES env var existing."\
    + " Ensure the top level job has a string param called STAGES.")
  }
  stage(args.stage_name){
    if (env.STAGES.contains(args.stage_name)){
        print "Stage Start: ${args.stage_name}"
        args.stage()
        print "Stage Complete: ${args.stage_name}"
    } else {
      print "Skipped: ${args.stage_name}"
    }
  }
}

/* Run a step if the step name is contained in an env var
 * Args:
 *   - step_name: String name of this stage
 *   - step: Closure to execute
 * Environment:
 *    - STAGES: String list of steps that should be run
 *
 * The difference between this and conditionalStage is that
 * this doesn't wrap the step in a stage block. This is useful
 * for cleanup jobs which confuse the jenkins visualisation when
 * run in a stage.
 */
def conditionalStep(Map args){
  if (env.STAGES == null){
    throw new Exception(
      "ConditionalStep used without STAGES env var existing."\
      + " Ensure the top level job has a string param called STAGES.")
  }
  if (env.STAGES.contains(args.step_name)){
      print "Step Start: ${args.step_name}"
      args.step()
      print "Step Complete: ${args.step_name}"
  } else {
    print "Skipped: ${args.step_name}"
  }
}

/**
 * Creates an acronym from a string
 * quick brown fox --> qbf
 * Arguments:
 *  string: the string to process
 * Returns:
 *  a string containing the acronym, or empty string if null or empty
 */
def String acronym(String s){
  String acronym=""

  if (s != null) {
    List words = s.split("[-_ ]")
    for (def i=0; i<words.size(); i++) {
      if (words[i].size() > 0) {
        acronym += words[i][0]
      }
    }
  }

  return acronym
}

/**
 * A test routine for the acronym function.
 */
def testAcronym() {

  List testStrings = [
    "The Quick Brown Fox",
    "The-Quick-Brown-Fox",
    "The_Quick_Brown_Fox",
    "The Quick-Brown_Fox",
    "The  Quick--Brown__Fox"
  ]

  List testEmptyStrings = [
    "",
    null
  ]

  for (def i=0; i<testStrings.size(); i++) {
    assert "TQBF" == acronym(testStrings[i])
  }

  for (def i=0; i<testEmptyStrings.size(); i++) {
    assert "" == acronym(testEmptyStrings[i])
  }
}

def String rand_int_str(int max=0xFFFF, int base=16){
  return Integer.toString(Math.abs((new SecureRandom()).nextInt(max)), base)
}

def String gen_instance_name(String prefix="AUTO"){
  String instance_name = ""
  if (env.INSTANCE_NAME == "AUTO"){
    if (prefix == "AUTO"){
      prefix = acronym(env.JOB_NAME)
    }
    //4 digit hex string to avoid name colisions
    instance_name = "${prefix}-${env.BUILD_NUMBER}-${rand_int_str()}"
  }
  else {
    instance_name = env.INSTANCE_NAME
  }
  //Hostname should match instance name for MaaS. Hostnames are converted
  //to lower case, so we'll do the same for instance name.
  instance_name = instance_name.toLowerCase()
  print "Instance_name: ${instance_name}"
  return instance_name
}

String container_name(){
  return "jenkinsjob_"+env.JOB_NAME+"_"+env.BUILD_NUMBER
}

def archive_artifacts(Map args = [:]){
  stage('Compress and Publish Artifacts'){
    try{
      // If the build fails, or this is a PR, the default
      // artifact types to upload are 'log'. Otherwise we
      // will attempt all types.
      if ((currentBuild.result == "FAILURE") || (env.ghprbPullId != null)) {
        suggested_artifact_types = "log"
      } else {
        suggested_artifact_types = "all"
      }

      // However, if an argument is given then use it instead.
      artifact_types = args.get("artifact_types", suggested_artifact_types)

      results_dir = args.get("results_dir", "${env.WORKSPACE}/results")

      dir(results_dir) {
        Integer testXmlLintRc = sh(
          returnStatus: true,
          script: """#!/bin/bash -xe
            test -f /usr/bin/xmllint
          """
        )

        // The junit step doesn't like parsing non-XML files (resulting in builds
        // being // marked as UNSTABLE). If xmllint is installed we verify that
        // all XML input is valid, and if not we move the affected files aside so
        // the junit pipeline step won't parse them.
        if (testXmlLintRc == 0) {
          List xmlFiles = findFiles(glob: '*.xml')

          for (file in xmlFiles) {
            Integer xmlStatus = sh(
              returnStatus: true,
              script: """#!/bin/bash -xe
                /usr/bin/xmllint ${file} > /dev/null || mv ${file} ${file}-broken
              """
            )
          }
        }

        junit allowEmptyResults: true, testResults: "*.xml"
      }

      pubcloud.uploadArtifacts("artifact_types": artifact_types)
      def buildArtifacts
      if (fileExists("build_artifacts.yml")){
        buildArtifacts = readYaml file: "build_artifacts.yml"
      } else{
        buildArtifacts = ["artifacts": []]
      }
      println "Uploaded artefact details:\n${buildArtifacts}"

      if (env.RE_JOB_TRIGGER != "PULL") {
        buildArtifacts.artifacts.find {k, v ->
          // rpc-gating is skipped because it is not a component and so that unit tests can continue
          if (k == "file" && v.container_name == env.RE_JOB_REPO_NAME && v.container_name != "rpc-gating"){
            addArtifactTypeToComponent(v.container_name, v.name, k, v.container_public_url, "RE")
            return true
          }
        }
      }
      if(buildArtifacts.artifacts){
        currentBuild.description = buildArtifacts.artifacts.collect{_, v ->
          "<h2><a href='${v.public_url}'>${v.title}</a></h2>"
        }.join("")
      }
    } catch (e){
      // This function is called from stdJob, so we must catch exceptions to
      // prevent failures in RE code eg (artifact archival) from causing
      // the build result to be set to failure.
      // try-catch placed here because this function is used in multiple places.
      if (env.ENABLE_JIRA == "yes") {
        String jiraProjectKey = args.get("jiraProjectKey", "RE")
        if (jiraProjectKey && !isAbortedBuild()){
          try {
            create_jira_issue(jiraProjectKey,
                              "Artifact Archival Failure: ${env.BUILD_TAG}",
                              "[${env.BUILD_TAG}|${env.BUILD_URL}]",
                              ["jenkins", "artifact_archive_fail"])
          } catch (f){
            print "Failed to create Jira Issue :( ${f}"
          }
        }
      }
      if (args.get("throwExceptionOnArchiveFailure", false)){
        throw e
      } else {
        print "Error while archiving artifacts, swallowing this exception to prevent "\
              +"archive errors from failing the build: ${e}"
      }
    }// try
  } // stage
}

void addArtifactTypeToComponent(String componentName, String artifactStoreName, String artifactType, String url, String jiraProjectKey){
  shared_slave() {
    String gatingVenv = "${WORKSPACE}/.venv"
    String componentVenv = "${WORKSPACE}/.componentvenv"
    sh """#!/bin/bash -xe
        virtualenv --python python3 ${componentVenv}
        set +x; . ${componentVenv}/bin/activate; set -x
        pip install -c '${env.WORKSPACE}/rpc-gating/constraints_rpc_component.txt' rpc_component
    """

    String releasesDir = "${WORKSPACE}/releases"

    clone_with_pr_refs("${releasesDir}", "https://github.com/rcbops/releases", "master")

    withEnv(
      [
        "ISSUE_SUMMARY=Add artefact container public URL to releases for ${componentName}",
        "ISSUE_DESCRIPTION=This issue was generated automatically when artefacts were uploaded to a new container.",
        "LABELS=component-artifacts jenkins",
        "JIRA_PROJECT_KEY=${jiraProjectKey}",
        "TARGET_BRANCH=master",
        "COMMIT_TITLE=Update ${componentName} with new artifact store ${artifactStoreName}",
        "COMMIT_MESSAGE=This change adds a new artifact store to the component definition.",
      ]
    ){
      withCredentials(
        [
          string(
            credentialsId: 'rpc-jenkins-svc-github-pat',
            variable: 'PAT'
          ),
          usernamePassword(
            credentialsId: "jira_user_pass",
            usernameVariable: "JIRA_USER",
            passwordVariable: "JIRA_PASS"
          ),
        ]
      ){
        sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
          try{
            sh """#!/bin/bash -xe
              cd ${releasesDir}
              set +x; . ${componentVenv}/bin/activate; set -x
              component \
                --releases-dir . \
                artifact-store \
                  --component-name ${componentName} \
                  get \
                    --name ${artifactStoreName}
            """
          } catch (Exception e){
            println "Failed to find artefact store, ${e}."
            println "Adding new artefact store."
            sh """#!/bin/bash -xe
              cd ${releasesDir}
              set +x; . ${componentVenv}/bin/activate; set -x
              component \
                --no-commit-changes \
                --releases-dir . \
                artifact-store \
                  --component-name ${componentName} \
                  add \
                    --name ${artifactStoreName} \
                    --type ${artifactType} \
                    --public-url ${url}
              deactivate
              set +x; . ${gatingVenv}/bin/activate; set -x
              git status
              git diff
              ${WORKSPACE}/rpc-gating/scripts/commit_and_pull_request.sh
            """
          }
        }
      }
    }
  }
}

List get_cloud_creds(){
  return [
    string(
      credentialsId: "dev_pubcloud_username",
      variable: "PUBCLOUD_USERNAME"
    ),
    string(
      credentialsId: "dev_pubcloud_api_key",
      variable: "PUBCLOUD_API_KEY"
    ),
    string(
      credentialsId: "dev_pubcloud_tenant_id",
      variable: "PUBCLOUD_TENANT_ID"
    ),
    file(
      credentialsId: 'id_rsa_cloud10_jenkins_file',
      variable: 'JENKINS_SSH_PRIVKEY'
    )
  ]
}

def writeCloudsCfg(){
  withRequestedCredentials("cloud_creds") {
    String cfg = """
client:
  force_ipv4: true
clouds:
  public_cloud:
    profile: rackspace
    auth_type: rackspace_apikey
    auth:
      username: ${env.PUBCLOUD_USERNAME}
      api_key: ${env.PUBCLOUD_API_KEY}
    # The default regions include LON which is not
    # in the same catalog, causing errors when
    # using the ansible dynamic inventory due to
    # missing endpoints. We therefore specify all
    # the regions in the US catalog.
    regions:
      - IAD
      - DFW
      - ORD
      - HKG
      - SYD

  pubcloud_uk:
    profile: rackspace
    auth_type: rackspace_apikey
    regions:
      - LON
    auth:
      auth_url: "https://lon.identity.api.rackspacecloud.com/v2.0/"
      username: ${env.PUBCLOUD_UK_USERNAME}
      api_key: ${env.PUBCLOUD_UK_API_KEY}

  phobos_nodepool:
    identity_api_version: 3
    verify: False
    auth:
      auth_url: "${env.PHOBOS_NODEPOOL_AUTH_URL}"
      project_name: "${env.PHOBOS_NODEPOOL_PROJECT_NAME}"
      project_id: "${env.PHOBOS_NODEPOOL_PROJECT_ID}"
      username: "${env.PHOBOS_NODEPOOL_USERNAME}"
      password: "${env.PHOBOS_NODEPOOL_PASSWORD}"
      user_domain_name: "${env.PHOBOS_NODEPOOL_USER_DOMAIN_NAME}"

# This configuration is used by ansible
# when using the openstack dynamic inventory.
ansible:
  use_hostnames: True
  expand_hostvars: False
  fail_on_errors: False
"""

    String tmp_dir = pwd(tmp: true)
    String clouds_cfg = "${tmp_dir}/clouds.yaml"
    sh """
    echo "${cfg}" > ${clouds_cfg}
    """
    return clouds_cfg
  }
}

def writeRaxmonCfg(Map args){
  String cfg = """[credentials]
username=${args.username}
api_key=${args.api_key}

[api]
url=https://monitoring.api.rackspacecloud.com/v1.0

[auth_api]
url=https://identity.api.rackspacecloud.com/v2.0/tokens

[ssl]
verify=true
"""

  String tmp_dir = pwd(tmp:true)
  String raxrc_cfg = "${tmp_dir}/.raxrc.cfg"
  sh """
    echo "${cfg}" > ${raxrc_cfg}
  """

  return raxrc_cfg
}

def prepareRpcGit(String branch = "auto", String dest = "/opt"){
  if (branch == "auto"){
    /* if job is triggered by PR, then we need to set RPC_REPO and
       RPC_BRANCH using the env vars supplied by ghprb.
    */
    if ( env.ghprbPullId != null ){
      env.RPC_REPO = "https://github.com/${env.ghprbGhRepository}.git"
      branch = "origin/pr/${env.ghprbPullId}/merge"
      print("Triggered by PR: ${env.ghprbPullLink}")
    } else {
      branch = env.RPC_BRANCH
    }
  }

  print("Repo: ${env.RPC_REPO} Branch: ${branch}")

  clone_with_pr_refs("${dest}/rpc-openstack", env.RPC_REPO, branch)
}

// Clone repo with Refspecs required for PRs.
// Shouldn't need to supply any params to checkout a PR merged with the base.
// Uses shell+git to avoid hostname verification failures with
// the built in git scm step.
// Use init + fetch instead of clone so that the repo can
// be cloned into a non-empty directory. Thats added for
// compatibility with the jenkins git scm step.
// Note: Creds are not supplied for https connections
// If you need autheniticated access, use ssh:// or git@
String clone_with_pr_refs(
  String directory='./',
  String repo="git@github.com:${env.ghprbGhRepository}",
  String ref="origin/pr/${env.ghprbPullId}/merge",
  String refspec='+refs/pull/\\*:refs/remotes/origin/pr/\\*'\
                +' +refs/heads/\\*:refs/remotes/origin/\\*'
){
  if(repo == "git@github.com:null"){
    throw new Exception(
      "repo not supplied to common.clone_with_pr_refs or env.ghprbGhRepository"\
      + " not set."
    )
  }
  if(ref == "origin/pr/null/merge"){
    throw new Exception(
      "ref not supplied to common.clone_with_pr_refs or env.ghprbPullId not "\
      + "set, attempting to checkout PR for a periodic build?")
  }
  String sha
  if (is_internal_repo_id(repo)) {
    sha = clone_internal_repo(directory, repo, ref, refspec)
  } else {
    sha = clone_external_repo(directory, repo, ref, refspec)
  }
  return sha
}

// Convert a https:// github url to an git@ github url
String https_to_ssh_github_url(String https_url){
  if (https_url.startsWith("https://")) {
    git_url = https_url.replaceAll("https://", "git@")
                       .replaceAll("github.com/", "github.com:")
    println("Updated repo from ${https_url} to ${git_url}")
    return git_url
  } else {
    println("Not a https url, so not converting to a git url: ${https_url}")
    return https_url
  }
}


String clone_repo(String directory, String ssh_key, String repo, String ref, String refspec) {
  // Need to clone/fetch with ssh@ protocol
  repo = https_to_ssh_github_url(repo)
  print "Cloning Repo: ${repo}@${ref}"
  sshagent (credentials:[ssh_key]){
    sh """#!/bin/bash -xe
      mkdir -p ${directory}
      cd ${directory}
      # use init + fetch to avoid the "dir not empty git fail"
      git init .
      # If the git repo previously existed, we remove the origin
      git remote rm origin || true
      git remote add origin "${repo}"
      # Don't quote refspec as it should be separate args to git.
      # only log errors
      git fetch --quiet --tags origin ${refspec}
      git checkout ${ref}
      git submodule update --init
    """
  }
  String sha = sh(script: "cd ${directory}; git rev-parse --verify HEAD", returnStdout: true).trim()
  return sha
}


Boolean is_internal_repo_id(String repo_url) {
  if (repo_url == null) {
    throw new Exception("repo_url is null in is_internal_repo_id")
  } else {
    return repo_url.startsWith("internal:")
  }
}


String clone_internal_repo(String directory, String internal_repo, String ref, String refspec) {
  repo_secret_id = internal_repo.split(":", 2)[1]
  repo_creds = [
    string(
      credentialsId: repo_secret_id,
      variable: "INTERNAL_REPO_URL"
    ),
  ]

  String sha
  String directoryInternalSlave = directory.minus(env.WORKSPACE + "/")
  internal_slave() {
    withCredentials(repo_creds) {
      withEnv(
        [
          "INTERNAL_REPO_HOSTNAME=${(env.INTERNAL_REPO_URL =~ "(?:@|//)([a-zA-Z0-9.-]+)(:|/)")[0][1]}",
        ]
      ){
        sh(
          """#!/bin/bash
            ssh-keyscan "\${INTERNAL_REPO_HOSTNAME}" 2>/dev/null | while read ssh_key; do
              if grep -q "\${ssh_key}" ~/.ssh/known_hosts; then
                echo "Internal repo key already in known hosts: \${ssh_key}"
              else
                echo "Adding internal repo key to known hosts: \${ssh_key}"
                echo "\${ssh_key}" >> ~/.ssh/known_hosts
              fi
            done
          """
        )
      }
      sha = clone_repo(directoryInternalSlave, "rpc-jenkins-svc-github-key", env.INTERNAL_REPO_URL, ref, refspec)
    }

    if (directoryInternalSlave.endsWith("/")) {
      directory_pattern = directoryInternalSlave.minus(env.WORKSPACE + "/") + "**"
    } else {
      directory_pattern = directoryInternalSlave.minus(env.WORKSPACE + "/") + "/**"
    }
    print "Internal repo stash include pattern: \"${directory_pattern}\"."
    stash includes: directory_pattern, name: "repo-clone"
  }
  unstash "repo-clone"
  return sha
}


String clone_external_repo(String directory, String repo, String ref, String refspec) {
    clone_repo(directory, "rpc-jenkins-svc-github-ssh-key", repo, ref, refspec)
}


/**
 * Merge list of pull requests.
 *
 * Iterate through the list of pull request IDs and merge each change
 * on top of the current HEAD. If a merge fails an exception is raised.
 *
 * @param directory location of the Git repository
 * @param pullRequestIDs ordered list of pull request numbers to merge
 * @return null
 */
void merge_pr_chain(String directory='./', List pullRequestIDs=null){
  println("Attempting to merge the following pull requests onto the base branch: ${pullRequestIDs}.")
  dir(directory){
    for (pullRequestID in pullRequestIDs) {
      println("Merging pull request ${pullRequestID}.")
      sh """#!/bin/bash -xe
        git merge origin/pr/${pullRequestID}/head
      """
    }
  }
  println("Finished merging the pull requests onto the base branch.")
}

// This function is potentially racy as it runs on shared slaves
// and modifies files outside the workspace which require a lock.
void configure_git(){
  print "Configuring Git"
  lock("configure_git"){
    retry(3){
      // credentials store created to ensure that non public repos
      // can be cloned when specified as https:// urls.
      // Ssh auth is handled in clone_with_pr_refs
      try {
        sh """#!/bin/bash -e
          mkdir -p ~/.ssh
          i_git_set(){
            k="\$1"
            v="\$2"
            if [[ "\$(git config --global \$k)" == "\$v" ]]; then
              echo "Git config key \$k already set to \$v"
            else
              echo "Setting git config value \$k: \$v"
              git config --global "\$k" "\$v"
            fi
          }
          ssh-keyscan github.com 2>/dev/null | while read ssh_key; do
            if grep -q "\${ssh_key}" ~/.ssh/known_hosts; then
              echo "Github.com key already in known hosts: \${ssh_key}"
            else
              echo "Adding github.com key to known hosts: \${ssh_key}"
              echo "\${ssh_key}" >> ~/.ssh/known_hosts
            fi
          done
          i_git_set user.email "rpc-jenkins-svc@github.com"
          i_git_set user.name "rpc.jenkins.cit.rackspace.net"
        """
      } catch (Exception e){
        sleep(5)
        throw e
      }
    }
  }
  print "Git Configuration Complete"
}

/* Set mtime to a constant value as git doesn't track mtimes but
 * docker 1.7 does, this causes cache invalidation when files are
 * added.
 */
def docker_cache_workaround(){
   sh "touch -t 201704100000 *.txt"
}

/* Used to check whether a pull request only includes changes
 * to files that match the skip regex. If the regex is an
 * empty string, that is treated as matching nothing.
 */
Boolean skip_build_check(String regex) {
  print "Skipping pattern:\n'$regex'"
  if (regex != "") {
    withEnv(["SKIP_REGEX=$regex"]) {
      def rc = sh(
        script: """#!/bin/bash
          set -xeu
          cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
          git status
          git show --stat=400,400 | awk '/\\|/{print \$1}' \
              |python -c 'import os, re, sys; all(re.search(os.environ["SKIP_REGEX"], line, re.VERBOSE) for line in sys.stdin) and sys.exit(0) or sys.exit(1)'
          """,
          returnStatus: true
      )
      if (rc==0) {
        print "All change files match skip pattern. Skipping..."
        return true
      } else if(rc==1) {
        print "One or more change files not matched by skip pattern. Continuing..."
        return false
      } else if(rc==128) {
        throw new Exception("Directory is not a git repo, cannot compile changes.")
      }
    }
  } else {
    return false
  } // if
}

/* DEPRECATED FUNCTION
 * This function should be removed once it is no longer in use. It remains
 * while still required by old-style jobs and has been replaced in standard
 * jobs by `skip_build_check`.
 */
def is_doc_update_pr(String git_dir) {
  if (env.ghprbPullId != null) {
    dir(git_dir) {
      def rc = sh(
        script: """#!/bin/bash
          set -xeu
          git status
          git show --stat=400,400 | awk '/\\|/{print \$1}' \
            | egrep -v -e '.*md\$' \
                       -e '.*rst\$' \
                       -e '^releasenotes/' \
                       -e '^gating/generate_release_notes/' \
                       -e '^gating/post_merge' \
                       -e '^gating/update_dependencies/'
        """,
        returnStatus: true
      )
      if (rc==0){
        print "Detected a deployment-related change or periodic job execution. Continuing..."
        return false
      }else if(rc==1){
        print "No deployment-related changes were detected. Skipping..."
        return true
      }else if(rc==128){
        throw new Exception("Directory is not a git repo, cannot check if changes are doc only")
      }
    }
  }
}

/* Look for JIRA issue key in commit messages for commits in the source branch
 * that aren't in the target branch.
 * This function uses environment variables injected by github pull request
 * builder and so can only be used for PR triggered jobs
 */
def get_jira_issue_key(String repo_path="rpc-openstack"){
  def key_regex = "[a-zA-Z][a-zA-Z0-9_]+-[1-9][0-9]*"
  commits = sh(
    returnStdout: true,
    script: """#!/bin/bash -e
      cd ${repo_path}
      git log --pretty=%B origin/${ghprbTargetBranch}..origin/pr/${ghprbPullId}/merge""")
  print("Looking for Jira issue keys in the following commits: ${commits}")
  try{
    String key = (commits =~ key_regex)[0]
    print ("First Found Jira Issue Key: ${key}")
    return key
  } catch (e){
    throw new Exception("""
No JIRA Issue key were found in commits ${repo_path}:${ghprbSourceBranch}""")
  }
}

/**
 * Notify users of build failures.
 *
 * Creates/updates build failures issue for job if JiraProject specified.
 * Sends notification on Slack if slackChannel specified.
 *
 * @param jiraProject Name of Jira project where issue should be created.
 * @param jiraLabels List of labels to add to issue.
 * @param slackChannel Name of Slack channel where message should be sent, e.g. "#foo".
 * @param slackTeam Name of Slack team to notify, e.g. "@bar".
 */
String build_failure_notify(String jiraProject,
                            List jiraLabels = [],
                            String slackChannel = null,
                            String slackTeam = null
                            ){
  String issueKey
  if (env.ENABLE_JIRA == "yes") {
    if (jiraProject){
      println("Creating build failure issue.")
      withCredentials([
        usernamePassword(
          credentialsId: "jira_user_pass",
          usernameVariable: "JIRA_USER",
          passwordVariable: "JIRA_PASS"
        )
      ]){
        issueKey = sh(script: """#!/bin/bash -xe
          cd ${env.WORKSPACE}
          set +x; . .venv/bin/activate; set -x
          python rpc-gating/scripts/jirautils.py \
            --user '$JIRA_USER' \
            --password '$JIRA_PASS' \
            build_failure_issue \
              --project "${jiraProject}" ${generate_label_options(jiraLabels)} \
              --job-name "${env.JOB_NAME}" \
              --job-url "${env.JOB_URL}" \
              --build-tag "${env.BUILD_TAG}" \
              --build-url "${env.BUILD_URL}"
        """, returnStdout: true).trim()
      }
    }
  }
  if (slackChannel){
    println("Sending build failure notification on Slack.")
    String message
    if (issueKey){
      message = "Build failure, see ${jiraLinkFromIssueKey(issueKey)} for details."
    } else{
      message = "Build failure, no issue created, see ${env.BUILD_URL} for details."
    }
    if (slackTeam){
      message = "${slackTeam}: ${message}"
    }
    slackSend(
      channel: slackChannel,
      message: message,
      color: "warning"
    )
  }

  return issueKey
}


// Create String of jirautils.py/create_jira_issue options for labels
// eg: --label "jenkins" --label "maas"
@NonCPS
String generate_label_options(List labels){
  List terms = []
  for (label in labels) {
      terms += "--label \"${label}\""
  }
  return terms.join(" ")
}


// This method creates a jira issue. Doesn't check for duplicates or
// do anthing fancy. Use build_failure_notify for build failures.
String create_jira_issue(String project,
                         String summary,
                         String description,
                         List labels = []){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script:"""#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        create_issue \
          --summary "${summary}" \
          --description "${description}" \
          --project "${project}" ${generate_label_options(labels)}
    """, returnStdout: true).trim()
  }
}


String get_or_create_jira_issue(String project,
                                String status = "BACKLOG",
                                String summary,
                                String description,
                                List labels = [],
                                Boolean debug=false){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    String debugString = debug ? "--debug" : ""
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' ${debugString} \
        get_or_create_issue \
          --status "${status}" \
          --summary "${summary}" \
          --description "${description}" \
          --project "${project}" ${generate_label_options(labels)}
    """, returnStdout: true).trim()
  }
}


List jira_query(String query){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        query \
          --query "${query}"
    """,
    returnStdout: true).tokenize()
  }
}

List jira_comments(String key){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        comments \
          --issue "${key}"
    """,
    returnStdout: true).tokenize('\n')
  }
}


void jira_close(String key){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        close \
          --issue "${key}"
    """
  }
}

void jira_close_all(String query, Integer max_issues=30,
                    Boolean allow_all_projects=false){
  allow_all_projects_str = ""
  if (allow_all_projects){
    allow_all_projects_str = "--allow-all-projects"
  }
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        close_all \
          --query "${query}" \
          --max-issues ${max_issues} ${allow_all_projects_str}
    """
  }
}

void jira_set_labels(String key, List labels){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        set_labels \
          --issue "${key}" ${generate_label_options(labels)}
    """
  }
}

// Create inventory file. Useful for running part of a job against
// an existing node, where the job expects an inventory file to
// have been created by the resource allocation step.
void drop_inventory_file(String content,
                         String path='rpc-gating/playbooks/inventory/hosts'){
    dir(env.WORKSPACE){
      writeFile file: path, text: content
    }
}

// Conditional step to drop manually created inventory file
void override_inventory(){
  conditionalStep(
    step_name: "Override Inventory",
    step:{
        String inventory_path
        if (env.OVERRIDE_INVENTORY_PATH == null){
          inventory_path = 'rpc-gating/playbooks/inventory/hosts'
        } else{
          inventory_path = env.OVERRIDE_INVENTORY_PATH
        }
        drop_inventory_file(env.INVENTORY, inventory_path)
    }
  )
}

Boolean isNodepoolNode(String node){
  return node =~ /^nodepool-/
}

Boolean isNodepoolHoldRequired(String holdOnError){
  return holdOnError && holdOnError != "0"
}

// initialisation steps for nodes
void use_node(String label=null, body){
  node(label){
    try {
      print "Preparing ${env.NODE_NAME} for use"
      deleteDir()
      if (! env.RPC_GATING_BRANCH){
        env.RPC_GATING_BRANCH="master"
      }
      configure_git()
      clone_with_pr_refs('rpc-gating',
                         'git@github.com:rcbops/rpc-gating',
                         env.RPC_GATING_BRANCH)
      install_ansible()
      print "${env.NODE_NAME} preparation complete, now ready for use."
      body()
    } catch (e){
      errString = "Caught exception on ${env.NODE_NAME}: ${e} Build: ${env.BUILD_URL}"
      print errString

      if (isNodepoolNode(env.NODE_NAME) && isNodepoolHoldRequired(env.HOLD_ON_ERROR)){
        nodePoolHold(duration: env.HOLD_ON_ERROR, reason: errString)
      }
      throw e
    } finally {
      try {
        // This may fail if node has gone offline
        // We've had a few jobs where the node goes offline during the post stage
        // but that must not fail the job. RE-2087
        deleteDir()
      } catch (Exception e){
        print("Failed to clean workspace on ${env.NODE_NAME}: ${e}")
      }
    }
  }
}

//shortcut functions for a shared slave or internal shared slave

void shared_slave(Closure body){
  use_node("pubcloud_multiuse", body)
}

void internal_slave(Closure body){
  use_node("CentOS", body)
}

void standard_job_slave(String slave_type, Closure body){
  if (slave_type == "instance") {
    pubcloud.runonpubcloud() {
      body()
    }
  } else if (slave_type.startsWith("nodepool-")) {
    use_node(slave_type){
      body()
    }
  } else if (slave_type == "internal"){
    internal_slave(){
      body()
    }
  } else if (slave_type == "shared") {
      // don't need to wrap body in shared_slave here
      // as globalWraps will have already allocated a shared slave executor
      body()
  } else if (slave_type == "container") {
    String image_name = env.BUILD_TAG.toLowerCase()
    String dockerfile_repo_dir = "${env.WORKSPACE}/"

    if (env.SLAVE_CONTAINER_DOCKERFILE_REPO == "PROJECT") {
      if ( env.ghprbPullId != null ) {
        clone_with_pr_refs( "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}", )
      } else {
        clone_with_pr_refs(
          "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
          env.REPO_URL,
          env.BRANCH,
        )
      }

      dockerfile_repo_dir += env.RE_JOB_REPO_NAME
    } else if (env.SLAVE_CONTAINER_DOCKERFILE_REPO == "RE") {
      dockerfile_repo_dir += "rpc-gating"
    } else {
      throw new Exception(
        "SLAVE_CONTAINER_DOCKERFILE_REPO '${env.SLAVE_CONTAINER_DOCKERFILE_REPO}' is not supported."
      )
    }

    dir(dockerfile_repo_dir) {
      container = docker.build(image_name, genDockerBuildArgs())
    }

    container.inside {
      configure_git()
      install_ansible()
      body()
    }
  } else {
    throw new Exception("slave_type '$slave_type' is not supported.")
  }
}


void connect_phobos_vpn(String gateway=null){
  withRequestedCredentials("phobos_vpn_auth_creds"){
    dir('rpc-gating/playbooks'){
      if (gateway == null){
        gateway = env.gw_from_creds
      }
      Boolean isPhobosVPNServerPingable = ! sh(
        script: """#!/bin/bash
          apt-get update
          apt-get install -y iputils-ping
          ping -c 3 ${gateway}
        """,
        returnStatus: true
      ).asBoolean()
      if (isPhobosVPNServerPingable){
        venvPlaybook(
          playbooks: [
            "vpn_setup.yml"
          ],
          args: [
            "-u root"
          ],
          vars: [
            ipsec_id: env.ipsec_id,
            ipsec_secret: env.ipsec_secret,
            xauth_user: env.xauth_user,
            xauth_pass: env.xauth_pass,
            gateway: gateway,
            vpn_name: "phobos",
            connectivity_test_url: "http://172.20.4.10:5000/"
          ]
        ) //venvPlaybook
      }
    } // dir
  } // withRequestedCredentials
}


Boolean isKronosVPNConnected(){
    withCredentials(
      [
        string(
          credentialsId: 'kronos_docker_registry_url',
          variable: 'registryURL'
        )
      ]
    ){
      return ! sh (
        script: """curl --connect-timeout 10 --insecure '$registryURL' """,
        returnStatus: true
      ).asBoolean()
    }
}

void connect_kronos_vpn(){
  if (! isKronosVPNConnected()){
    withRequestedCredentials("kronos_vpn_auth_creds"){
      withCredentials(
        [
          string(
            credentialsId: 'kronos_docker_registry_url',
            variable: 'registryURL'
          )
        ]
      ){
        dir("${WORKSPACE}/rpc-gating/playbooks"){
          venvPlaybook(
            playbooks: [
              "vpn_setup.yml"
            ],
            args: [
              "-u root"
            ],
            vars: [
              ipsec_id: env.KRONOS_IPSEC_ID,
              ipsec_secret: env.KRONOS_IPSEC_SECRET,
              xauth_user: env.KRONOS_XAUTH_USER,
              xauth_pass: env.KRONOS_XAUTH_PASS,
              gateway: env.KRONOS_GW_FROM_CREDS,
              vpn_name: "kronos",
              connectivity_test_url: registryURL
            ]
          )
        }
      }
    }
  }
}

// This is a global wrapper to kick off the RPC-O Newton deployment
// artifact build job which is a special case for RPC-O's newton branch.
void buildRpcNewtonArtifacts() {
  build(
    job: "PM-rpc-openstack-newton-artifact-build",
    wait: true,
    parameters: [
      [
        $class: 'StringParameterValue',
        name: 'RPC_GATING_BRANCH',
        value: env.RPC_GATING_BRANCH
      ]
    ]
  )
}

// Build an array suitable for passing to withCredentials
// from a space or comma separated list of credential IDs.
@NonCPS
List build_creds_array(String list_of_cred_ids){
    print("Building credentials array from the following list of IDs: ${list_of_cred_ids}")
    Map creds_bundles = [
      "cloud_creds": [
        'dev_pubcloud_username',
        'dev_pubcloud_api_key',
        'dev_pubcloud_tenant_id',
        'PUBCLOUD_UK_USERNAME',
        'PUBCLOUD_UK_API_KEY',
        'phobos_nodepool_auth_url',
        'phobos_nodepool_project_name',
        'phobos_nodepool_project_id',
        'phobos_nodepool_username',
        'phobos_nodepool_password',
        'phobos_nodepool_user_domain_name'
      ],
      "rpc_asc_creds": [
        'RPC_ASC_QTEST_API_TOKEN',
        'ASC_OPS_FABRIC_GOOGLE_VAULT'
      ],
      "rpc_ri_creds": [
        'RPC_RI_APPFORMIX_CF_ACCOUNT'
      ],
      "rpc_repo": [
        'RPC_REPO_IP',
        'RPC_REPO_SSH_USERNAME_TEXT',
        'RPC_REPO_SSH_USER_PRIVATE_KEY_FILE',
        'RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE',
        'RPC_REPO_GPG_SECRET_KEY_FILE',
        'RPC_REPO_GPG_PUBLIC_KEY_FILE'
      ],
      "phobos_embedded": [
        'phobos_clouds_rpc_jenkins_user',
        'id_rsa_cloud10_jenkins_file',
        'rackspace_ca_crt'
      ],
      "kronos_vpn_auth_creds": [
        "kronos_vpn_ipsec",
        "kronos_vpn_xauth",
        "kronos_vpn_gateway"
      ],
      "phobos_vpn_auth_creds": [
        "phobos_vpn_ipsec",
        "phobos_vpn_xauth",
        "phobos_vpn_gateway"
      ],
      "jenkins_ssh_privkey": [
        'id_rsa_cloud10_jenkins_file',
      ],
      "jenkins_api_creds": [
        'service_account_jenkins_api_creds'
      ],
      "rpc_osp": [
        "RPC_OSP_REDHAT_ISO_URL",
        "RPC_OSP_REDHAT_POOL_ID",
        "RPC_OSP_REDHAT_PASSWORD",
        "RPC_OSP_REDHAT_USERNAME"
      ],
      "kronos_docker_registry": [
       "kronos_docker_registry_domain_name",
       "kronos_mk8s_jenkins_account"
      ]
    ]
    // only needs to contain creds that should be exposed.
    // every cred added should also be documented in RE for Projects
    Map available_creds = [
      "kronos_docker_registry_domain_name": string(
        credentialsId: 'kronos_docker_registry_domain_name',
        variable: 'kronos_docker_registry_domain_name'
      ),
      "kronos_mk8s_jenkins_account": usernamePassword(
        credentialsId: "kronos_mk8s_jenkins_account",
        usernameVariable: "kronos_mk8s_jenkins_username",
        passwordVariable: "kronos_mk8s_jenkins_password"
      ),
      "dev_pubcloud_username": string(
        credentialsId: "dev_pubcloud_username",
        variable: "PUBCLOUD_USERNAME"
      ),
      "dev_pubcloud_api_key": string(
        credentialsId: "dev_pubcloud_api_key",
        variable: "PUBCLOUD_API_KEY"
      ),
      "dev_pubcloud_tenant_id": string(
        credentialsId: "dev_pubcloud_tenant_id",
        variable: "PUBCLOUD_TENANT_ID"
      ),
      "PUBCLOUD_UK_USERNAME": string(
        credentialsId: "PUBCLOUD_UK_USERNAME",
        variable: "PUBCLOUD_UK_USERNAME"
      ),
      "PUBCLOUD_UK_API_KEY": string(
        credentialsId: "PUBCLOUD_UK_API_KEY",
        variable: "PUBCLOUD_UK_API_KEY"
      ),
      "RE_GRAFANA_ADMIN_PASSWORD": string(
        credentialsId: "RE_GRAFANA_ADMIN_PASSWORD",
        variable: "RE_GRAFANA_ADMIN_PASSWORD"
      ),
      "RE_GRAFANA_GRAFYAML_API_KEY": string(
        credentialsId: "RE_GRAFANA_GRAFYAML_API_KEY",
        variable: "RE_GRAFANA_GRAFYAML_API_KEY"
      ),
      "RE_GRAPHITE_ADMIN_PASSWORD": string(
        credentialsId: "RE_GRAPHITE_ADMIN_PASSWORD",
        variable: "RE_GRAPHITE_ADMIN_PASSWORD"
      ),
      "RE_GRAPHITE_SECRET_KEY": string(
        credentialsId: "RE_GRAPHITE_SECRET_KEY",
        variable: "RE_GRAPHITE_SECRET_KEY"
      ),
      "RPC_ASC_QTEST_API_TOKEN": string(
        credentialsId: "RPC_ASC_QTEST_API_TOKEN",
        variable: "RPC_ASC_QTEST_API_TOKEN"
      ),
      "RPC_RI_APPFORMIX_CF_ACCOUNT": usernamePassword(
        credentialsId: "RPC_RI_APPFORMIX_CF_ACCOUNT",
        usernameVariable: "RPC_RI_APPFORMIX_CF_USERNAME",
        passwordVariable: "RPC_RI_APPFORMIX_CF_PASSWORD"
      ),
      "id_rsa_cloud10_jenkins_file": file(
        credentialsId: 'id_rsa_cloud10_jenkins_file',
        variable: 'JENKINS_SSH_PRIVKEY'
      ),
      "rpc_jenkins_svc_github_key_file": sshUserPrivateKey(
        credentialsId: 'rpc-jenkins-svc-github-ssh-key',
        keyFileVariable: 'JENKINS_GITHUB_SSH_PRIVKEY'
      ),
      "RPC_REPO_IP": string(
        credentialsId: "RPC_REPO_IP",
        variable: "REPO_HOST"
      ),
      "RPC_REPO_SSH_USERNAME_TEXT": string(
        credentialsId: "RPC_REPO_SSH_USERNAME_TEXT",
        variable: "REPO_USER"
      ),
      "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE": file(
        credentialsId: "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE",
        variable: "REPO_USER_KEY"
      ),
      "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE": file(
        credentialsId: "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE",
        variable: "REPO_HOST_PUBKEY"
      ),
      "RPC_REPO_GPG_SECRET_KEY_FILE": file(
        credentialsId: "RPC_REPO_GPG_SECRET_KEY_FILE",
        variable: "GPG_PRIVATE"
      ),
      "RPC_REPO_GPG_PUBLIC_KEY_FILE": file(
        credentialsId: "RPC_REPO_GPG_PUBLIC_KEY_FILE",
        variable: "GPG_PUBLIC"
      ),
      "phobos_clouds_rpc_jenkins_user": file(
        credentialsId: "phobos_clouds_rpc_jenkins_user",
        variable: "phobos_clouds_rpc_jenkins_user"
      ),
      "rackspace_ca_crt": file(
        credentialsId: "rackspace_ca_crt",
        variable: "rackspace_ca_crt"
      ),
      "kronos_vpn_ipsec": usernamePassword(
        credentialsId: "kronos_vpn_ipsec",
        usernameVariable: "KRONOS_IPSEC_ID",
        passwordVariable: "KRONOS_IPSEC_SECRET"
      ),
      "kronos_vpn_xauth": usernamePassword(
        credentialsId: "kronos_vpn_xauth",
        usernameVariable: "KRONOS_XAUTH_USER",
        passwordVariable: "KRONOS_XAUTH_PASS"
      ),
      "kronos_vpn_gateway": string(
        credentialsId: 'kronos_vpn_gateway',
        variable: 'KRONOS_GW_FROM_CREDS'
      ),
      "phobos_vpn_ipsec": usernamePassword(
        credentialsId: "phobos_vpn_ipsec",
        usernameVariable: "ipsec_id",
        passwordVariable: "ipsec_secret"
      ),
      "phobos_vpn_xauth": usernamePassword(
        credentialsId: "phobos_vpn_xauth",
        usernameVariable: "xauth_user",
        passwordVariable: "xauth_pass"
      ),
      "phobos_vpn_gateway": string(
        credentialsId: 'phobos_vpn_gateway',
        variable: 'gw_from_creds'
      ),
      "phobos_nodepool_auth_url": string(
        credentialsId: "phobos_nodepool_auth_url",
        variable: "PHOBOS_NODEPOOL_AUTH_URL"
      ),
      "phobos_nodepool_project_name": string(
        credentialsId: "phobos_nodepool_project_name",
        variable: "PHOBOS_NODEPOOL_PROJECT_NAME"
      ),
      "phobos_nodepool_project_id": string(
        credentialsId: "phobos_nodepool_project_id",
        variable: "PHOBOS_NODEPOOL_PROJECT_ID"
      ),
      "phobos_nodepool_username": string(
        credentialsId: "phobos_nodepool_username",
        variable: "PHOBOS_NODEPOOL_USERNAME"
      ),
      "phobos_nodepool_password": string(
        credentialsId: "phobos_nodepool_password",
        variable: "PHOBOS_NODEPOOL_PASSWORD"
      ),
      "phobos_nodepool_user_domain_name": string(
        credentialsId: "phobos_nodepool_user_domain_name",
        variable: "PHOBOS_NODEPOOL_USER_DOMAIN_NAME"
      ),
      "service_account_jenkins_api_creds": usernamePassword(
        credentialsId: "service_account_jenkins_api_creds",
        usernameVariable: "JENKINS_USERNAME",
        passwordVariable: "JENKINS_API_KEY"
      ),
      "RPC_OSP_REDHAT_ISO_URL": string(
        credentialsId: "RPC_OSP_REDHAT_ISO_URL",
        variable: "RPC_OSP_REDHAT_ISO_URL"
      ),
      "RPC_OSP_REDHAT_POOL_ID": string(
        credentialsId: "RPC_OSP_REDHAT_POOL_ID",
        variable: "RPC_OSP_REDHAT_POOL_ID"
      ),
      "RPC_OSP_REDHAT_PASSWORD": string(
        credentialsId: "RPC_OSP_REDHAT_PASSWORD",
        variable: "RPC_OSP_REDHAT_PASSWORD"
      ),
      "RPC_OSP_REDHAT_USERNAME": string(
        credentialsId: "RPC_OSP_REDHAT_USERNAME",
        variable: "RPC_OSP_REDHAT_USERNAME"
      ),
      "IMAGE_JENKINS_PASSWORD": string(
        credentialsId: "IMAGE_JENKINS_PASSWORD",
        variable: "IMAGE_JENKINS_PASSWORD"
      ),
      "ASC_OPS_FABRIC_GOOGLE_VAULT": string(
        credentialsId: "ASC_OPS_FABRIC_GOOGLE_VAULT",
        variable: "ASC_OPS_FABRIC_GOOGLE_VAULT"
      )
    ]

    // split string into list, reject empty items.
    List requested_creds = list_of_cred_ids.split(/[, ]+/).findAll({
      it.size() > 0
    })

    // check for invalid values
    List invalid = requested_creds - (creds_bundles.keySet()
                                      + available_creds.keySet())
    if (invalid != []){
      throw new Exception("Attempt to use unknown credential(s): ${invalid}")
    }
    // expand bundles into sublists, then flatten the list
    List requested_bundle_expanded = requested_creds.collect({
      creds_bundles[it] ?: it
    }).flatten()
    print ("Expanded Credentials: ${requested_bundle_expanded}")
    // convert list of ids to list of objects
    List creds_array = requested_bundle_expanded.collect({
      available_creds[it]
    })
    print ("Final Credentials Array: ${creds_array}")
    return creds_array
}

// Supply credentials to a closure. Similar to withCredentials except
// that this function takes a string containing a list of credential IDs
// instead of an array of credentials objects. This is so that a string can
// be used in a JJB Project to request credentials.
void withRequestedCredentials(String list_of_cred_ids, Closure body){
  List creds = build_creds_array(list_of_cred_ids)
  withCredentials(creds){
    body()
  }
}

Cause getRootCause(Cause cause){
    if (cause.class.toString().contains("UpstreamCause")) {
         for (upCause in cause.upstreamCauses) {
             return getRootCause(upCause)
         }
     } else {
        return cause
     }
}

void setTriggerVars(){
  List causes = currentBuild.rawBuild.getCauses()
  Cause root_cause = getRootCause(causes[0])
  env.RE_JOB_TRIGGER_DETAIL="No detail available"
  if (root_cause instanceof Cause.UpstreamCause){
      env.RE_JOB_TRIGGER="UPSTREAM"
      env.RE_JOB_TRIGGER_DETAIL = "${root_cause.getUpstreamProject()}/${root_cause.getUpstreamBuild()}"
  } else if (root_cause instanceof Cause.UserIdCause){
      env.RE_JOB_TRIGGER = "USER"
      env.RE_JOB_TRIGGER_DETAIL = root_cause.getUserName()
  } else if (root_cause instanceof hudson.triggers.TimerTrigger.TimerTriggerCause) {
      env.RE_JOB_TRIGGER = "TIMER"
  } else if (root_cause instanceof com.cloudbees.jenkins.GitHubPushCause){
      env.RE_JOB_TRIGGER = "PUSH"
      env.RE_JOB_TRIGGER_DETAIL = root_cause.getShortDescription()
  } else if (root_cause instanceof org.jenkinsci.plugins.ghprb.GhprbCause){
      env.RE_JOB_TRIGGER="PULL"
      env.RE_JOB_TRIGGER_DETAIL = "${env.ghprbPullTitle}/${root_cause.pullID}"
  } else {
      env.RE_JOB_TRIGGER="OTHER"
  }
  print ("Trigger: ${env.RE_JOB_TRIGGER} (${env.RE_JOB_TRIGGER_DETAIL})")
}

// convert a comma separated list of wrapper names
// into a list of functions for use with wrapList
List stdJobWrappers(String wrappers){
  Map availableWrappers = [
    "phobos_vpn": {body -> connect_phobos_vpn(); body()},
    "kronos_vpn": {body -> connect_kronos_vpn(); body()},
    "rpco_deploy_artifact_build": {body -> buildRpcNewtonArtifacts(); body()}
  ]
  // Convert csv list of strings to list of wrapper functions
  // via the above map
  List wrapperFuncs = []
  for (String wrapperName in wrappers.tokenize(", ")){
    Closure c = availableWrappers[wrapperName]
    if (c == null){
      throw new Exception("Invalid Standard Job Wrapper: ${wrapperName}")
    } else {
      wrapperFuncs << c
    }
  }
  return wrapperFuncs
}

// nest a load of functions from a list
// wraplist([a,b,c]){ print "body" } is equivalent to:
// a {
//   b {
//     c {
//       print "body"
//     }
//   }
// }
void wrapList(List wrappers, Closure body){
  // if the list of wrappers is empty, just
  // execute the passed in closure (nothing to wrap)
  if (wrappers.empty){
    body()
  } else {
    // wrappers is not empty, lets get wrapping

    // count down from through the wrappers list
    for (i in wrappers.size()..1){
        // define an alias for the loop counter
        // within the scope of the loop, so this
        // value will be captured by closures
        // created within the loop
        def _i = i

        // b is the closure that will be
        // passed to the next closure to be created
        Closure b

        // On the first iteration use, the actual
        // body closure that was passed into this function.
        // On subsequent iterations use the previously
        // created closure.
        if (_i == wrappers.size()){
            b = body
        } else {
            b = c
        }
        // create a closure
        // this closure calls one of the functions from the
        // passed in function list, either with the passed
        // in body closure as the argument, or with the
        // previously created closure as the argument.
        c = {wrappers[_i-1](b)}
    }
    // execute the nest of closures
    // this will look something like:
    // {wrappers[0]({wrappers[1]({wrappers[2]({print "body"})})})}
    c()
  }
}


// add wrappers that should be used for all jobs.
// max log size is in MB
void globalWraps(Closure body){
  // global timeout is long, so individual jobs can set shorter timeouts and
  // still have to cleanup, archive atefacts etc.
  timestamps{
    timeout(time: env.BUILD_TIMEOUT_HRS ?: 10, unit: 'HOURS'){
      shared_slave(){
        wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 200, 'failBuild': true, 'setOwn': true]) {
          setTriggerVars()
          if (env.ENABLE_JIRA == "yes") {
            if(shouldAbortForMaintenance()){
              recordAbortDueToMaintenance()
              currentBuild.result = "ABORTED"
              return
            }
          }
          print("common.globalWraps pre body")
          body()
          print("common.globalWraps post body")
        } // log size
      } // shared slave
    } // timeout
  } // timestamps
}

Boolean isUserAbortedBuild() {
  if (currentBuild.rawBuild.getAction(InterruptedBuildAction.class)) {
    userAborted = true
  } else {
    userAborted = false
  }
  return userAborted
}

Boolean isPrUpdateAbortedBuild(){
  if (currentBuild.rawBuild.getAction(GhprbCancelBuildsOnUpdate.class)) {
    prAborted = true
  } else {
    prAborted = false
  }
  return prAborted
}

Boolean isAbortedBuild(){
  return isUserAbortedBuild() || isPrUpdateAbortedBuild()
}

String genDockerBuildArgs() {
  return sh(script: """#!/usr/bin/env python3
from os import environ
from shlex import quote

# Throw a KeyError if neither are set.
build_args = environ['SLAVE_CONTAINER_DOCKERFILE_BUILD_ARGS']
dockerfile = environ['SLAVE_CONTAINER_DOCKERFILE_PATH']
formatted_args = ""

# We require SLAVE_CONTAINER_DOCKERFILE_PATH to always be set, while
# SLAVE_CONTAINER_DOCKERFILE_BUILD_ARGS can contain an empty string.
if not dockerfile:
    raise Exception("No Dockefile path specified in "
                    "SLAVE_CONTAINER_DOCKERFILE_PATH")

if build_args:
    for arg in build_args.split():
        formatted_args += "--build-arg {} ".format(quote(arg))

formatted_args += '-f {} .'.format(quote(dockerfile))

print(formatted_args)
  """, returnStdout: true).trim()
}

return this

void stdJob(String hook_dir, String credentials, String jira_project_key, String wrappers, String slackChannel, String slackTeam) {
  globalWraps(){
    standard_job_slave(env.SLAVE_TYPE) {
      wrapList(stdJobWrappers(wrappers)){
        env.RE_HOOK_ARTIFACT_DIR="${env.WORKSPACE}/artifacts"
        env.RE_HOOK_RESULT_DIR="${env.WORKSPACE}/results"

        currentBuild.result="SUCCESS"

        runThawIfSnapshot()
        try {
          withRequestedCredentials(credentials) {

            stage('Checkout') {
              String commit
              if (env.ghprbPullId == null) {
                commit = clone_with_pr_refs(
                  "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
                  env.REPO_URL,
                  env.BRANCH,
                )
                if (env.pullRequestChain) {
                  pullRequestIDs = loadCSV(env.pullRequestChain)
                  merge_pr_chain("${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}", pullRequestIDs)
                }
              } else {
                print("Triggered by PR: ${env.ghprbPullLink}")
                commit = clone_with_pr_refs(
                  "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
                )
              }
              updateStringParam(
                "_BUILD_SHA",
                commit,
                "The SHA tested by this build."
              )
            }

            stage('Execute Pre Script') {
              // The 'pre' stage is used to prepare the environment for
              // testing but is not the test itself. Retry on failure
              // to reduce the likelihood of non-test errors.
              retry(3) {
                sh """#!/bin/bash -xeu
                  cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                  if [[ -e gating/${hook_dir}/pre ]]; then
                    gating/${hook_dir}/pre
                  fi
                """
              }
            }

            try {
              // Any errors will be propagated to the outer catch clause
              stage('Execute Run Script') {
                sh """#!/bin/bash -xeu
                  cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                  gating/${hook_dir}/run
                """
              }
            } catch (e) {
              // Set the build failure flag so the finally clause can report the job status
              currentBuild.result="FAILURE"
              // Need to re-throw the same exception so the outer try/catch can deal with it
              throw e
            } finally {
              try {
                stage('Execute Post Script') {
                  // We do not want the 'post' execution to fail the test,
                  // but we do want to know if it fails so we make it only
                  // return status.
                  post_result = sh(
                    returnStatus: true,
                    script: """#!/bin/bash -xeu
                              export RE_JOB_STATUS=${currentBuild.result}
                              cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                              if [[ -e gating/${hook_dir}/post ]]; then
                                gating/${hook_dir}/post
                              fi"""
                  )
                  if (post_result != 0) {
                    print("Post stage failed with return code ${post_result}")
                  }
                }
              } catch (Exception e){
                print("Caught exception during post stage, swallowing so it doesn't cause the build to fail: "+e)
              }
            }
          }
        } catch (e) {
          currentBuild.result="FAILURE"
          errString = "Caught exception on ${env.NODE_NAME}: ${e} Build: ${env.BUILD_URL}"
          print errString

          if (env.ghprbPullId == null && ! isAbortedBuild()) {
            def labels = ['post-merge-test-failure', 'jenkins', env.JOB_NAME]
            build_failure_notify(jira_project_key, labels, slackChannel, slackTeam)
          }
          throw e
        } finally {
            // try-catch within archive_artifacts() prevents
            // this call from failing standard job builds
            archive_artifacts(
              results_dir: "${env.RE_HOOK_RESULT_DIR}"
            )
        } // try
      } // stdJobWrappers
    } // standard_job_slave
  } // globalwraps
} //stdJob func

/** Check if the build is a pull request that only modifies files that
 *  do not require testing by matching against skip pattern.
 */
Boolean isSkippable(String skip_pattern, String credentials) {
  Boolean skipIt
  globalWraps(){
    if (env.ghprbPullId == null) {
      skipIt = false
    } else {
      withRequestedCredentials(credentials) {
        stage('Check PR against skip-list') {
          print("Triggered by PR: ${env.ghprbPullLink}")
          clone_with_pr_refs(
            "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
          )
          skipIt = skip_build_check(skip_pattern)
        }
      }
    }
  }
  return skipIt
}

void runReleasesPullRequestWorkflow(String baseBranch, String prBranch, String jiraProjectKey,
    String statusContext, String skipTestsTriggerPhrase, String reReleaseTriggerPhrase){
  def (prType, componentText) = getComponentChange(baseBranch, prBranch)
  def componentYaml = readYaml text: componentText

  if (prType == "release"){
    String REPO_NAME=componentYaml["name"]
    String REPO_URL=componentYaml["repo_url"]
    String SHA=componentYaml["release"]["sha"]
    String MAINLINE=componentYaml["release"]["series"]
    String RC_BRANCH="${MAINLINE}-rc"

    dir(REPO_NAME){
      clone_with_pr_refs(
        "./",
        REPO_URL,
        "master",
      )
      println "=== Checking that the specified SHA exists ==="
      Boolean sha_exists = ! sh(
        script: """#!/bin/bash -xe
          git rev-parse --verify ${SHA}^{commit}
        """,
        returnStatus: true,
      )
      if (! sha_exists) {
        throw new Exception("The supplied SHA ${SHA} was not found.")
      }
      println "=== Checking for the existence of an RC branch ==="
      Boolean has_rc_branch = ! sh(
        script: """#!/bin/bash -xe
          git rev-parse --verify remotes/origin/${RC_BRANCH}
        """,
        returnStatus: true,
      )
      if (has_rc_branch) {
        println "=== Checking that the specified SHA is the tip of RC branch ${RC_BRANCH} ==="
        String latest_sha = sh(
          script: """#!/bin/bash -xe
            git rev-parse --verify remotes/origin/${RC_BRANCH}
          """,
          returnStdout: true,
        ).trim()
        // (NOTE(mattt): We should be releasing the tip of ${RC_BRANCH}, so
        //               if the SHA specified in the release does not match
        //               the tip of ${RC_BRANCH} we throw an exception to
        //               abort the release.
        if (latest_sha != SHA) {
          throw new Exception("The supplied SHA ${SHA} is not the tip on RC branch ${RC_BRANCH}.")
        }
      }

      if (skipPullRequestTests(skipTestsTriggerPhrase)) {
        println "Skipping the validation of the project code."
      } else {
        testRelease(componentText)
      }
      createRelease(componentText, has_rc_branch, shouldReRelease(reReleaseTriggerPhrase))
    }
  }else if (type == "registration"){
    registerComponent(componentText, jiraProjectKey)
  }else if (type == "artifact-store"){
    // Adding an artefact store does not activate any additional workflow and
    // so this block only needs skipping so the pull request can be merged.
  }else{
    throw new Exception("The pull request type ${prType} is unsupported.")
  }

  String description = "Release tests passed, merging..."
  gate.updateStatusAndMerge(description, statusContext)

  if (prType == "release"){
    try {
      updateDependents(REPO_NAME)
    } catch (e) {
      println "Error updateDependents(): unable to perform dep update on dependent components"
    }
  }
}

Boolean skipPullRequestTests(String triggerPhrase){
  return (ghprbCommentBody ==~ /.*${triggerPhrase}.*/)
}

/**
* Test the comment that triggered this build,
* if it contains "re release", then any conflicting
* release artifacts (eg tags or github releases)
* will be removed in order for the release to proceed
*/
Boolean shouldReRelease(String reReleaseTriggerPhrase){
  return (ghprbCommentBody ==~ /.*${reReleaseTriggerPhrase}.*/)
}

List getComponentChange(String baseBranch, String prBranch){
  venv = "${WORKSPACE}/.componentvenv"
  sh """#!/bin/bash -xe
      virtualenv --python python3 ${venv}
      set +x; . ${venv}/bin/activate; set -x
      pip install -c '${env.WORKSPACE}/rpc-gating/constraints_rpc_component.txt' rpc_component
  """
  types = ["release", "registration", "artifact-store"]
  for (i=0; i < types.size(); i++){
    type = types[i]
    try{
      component_text = sh(
        script: """#!/bin/bash -xe
          set +x; . ${venv}/bin/activate; set -x
          component --releases-dir . compare --from ${baseBranch} --to ${prBranch} --verify ${type}
        """,
        returnStdout: true
      )
      break
    }catch (e){
      println "Pull request is not a ${type}."
      component_text = null
    }
  }
  if (component_text == null) {
    throw new Exception("The pull request does not correspond to any of the known types ${types}.")
  }

  println "Pull request is a ${type}."
  println "=== component CLI standard out ==="
  println component_text

  return [type, component_text]
}

void registerComponent(String component_text, String jiraProjectKey){
  def component = readYaml text: component_text
  createComponentSkeleton(component["name"], component["repo_url"], jiraProjectKey)
  createComponentJobs(component["name"], component["repo_url"], component["releases"], jiraProjectKey)
}

void createComponentSkeleton(String name, String repoUrl, String jiraProjectKey){
  dir("${WORKSPACE}/${name}"){
    clone_with_pr_refs(".", repoUrl.replace("https://github.com/", "git@github.com:"), "origin/master")
    withEnv(
      [
        "ISSUE_SUMMARY=Add component skeleton to ${name}",
        "ISSUE_DESCRIPTION=This issue was generated automatically as part of registering a new component.",
        "LABELS=component-skeleton jenkins",
        "JIRA_PROJECT_KEY=${jiraProjectKey}",
        "TARGET_BRANCH=master",
        "COMMIT_TITLE=Add new component gating skeleton",
        "COMMIT_MESSAGE=This project is being added to the RE platform. This change adds the\nbasic structure required to make use of the platform. Please modify\nthis pull request before merging to enable the required support.",
      ]
    ){
      withCredentials(
        [
          string(
            credentialsId: 'rpc-jenkins-svc-github-pat',
            variable: 'PAT'
          ),
          usernamePassword(
            credentialsId: "jira_user_pass",
            usernameVariable: "JIRA_USER",
            passwordVariable: "JIRA_PASS"
          ),
        ]
      ){
        sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
          sh """#!/bin/bash -xe
            set +x; . ${WORKSPACE}/.venv/bin/activate; set -x
            ${WORKSPACE}/rpc-gating/scripts/add_component_skeleton.py .
            ${WORKSPACE}/rpc-gating/scripts/commit_and_pull_request.sh
          """
        }
      }
    }
  }
}

void createComponentJobs(String name, String repoUrl, List releases, String jiraProjectKey){
  String repoDir = "${WORKSPACE}/rpc-gating-master"
  String filename = "${name}.yml".replace("-", "_")
  String projectsFile = "${repoDir}/rpc_jobs/${filename}"

  dir(repoDir) {
    // NOTE(mattt): while likely to be unnecessary, we just re-clone rpc-gating
    // here to ensure we're not committing anything sensitive that may have
    // been created in ${WORKSPACE}/rpc-gating.
    git branch: 'master', url: 'https://github.com/rcbops/rpc-gating'

    createComponentGateTrigger(name, repoUrl, projectsFile)
    createComponentPreRelease(name, repoUrl, releases, projectsFile)
    createPrWhisperer(name, repoUrl, projectsFile)
    createCheckmarx(name, repoUrl, projectsFile, jiraProjectKey)

    withEnv(
      [
        "ISSUE_SUMMARY=Add component skeleton to ${name}",
        "ISSUE_DESCRIPTION=This issue was generated automatically as part of registering a new component.",
        "LABELS=component-skeleton jenkins",
        "JIRA_PROJECT_KEY=${jiraProjectKey}",
        "TARGET_BRANCH=master",
        "COMMIT_TITLE=Add default jobs to ${name}",
        "COMMIT_MESSAGE=This project is being added to the RE platform. This change adds some\ndefault jobs required by all projects.",
      ]
    ){
      withCredentials(
        [
          string(
            credentialsId: 'rpc-jenkins-svc-github-pat',
            variable: 'PAT'
          ),
          usernamePassword(
            credentialsId: "jira_user_pass",
            usernameVariable: "JIRA_USER",
            passwordVariable: "JIRA_PASS"
          ),
        ]
      ){
        sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
          sh """#!/bin/bash -xe
            set +x; . ${WORKSPACE}/.venv/bin/activate; set -x
            git status
            git diff
            ${WORKSPACE}/rpc-gating/scripts/commit_and_pull_request.sh
          """
        } // sshagent
      } // withCredentials
    } // withEnv
  } // dir
}

void createComponentGateTrigger(String name, String repoUrl, String projectsFile){
  String jjb = """
- project:
    name: "${name}-gate-trigger"
    repo_name: "${name}"
    repo_url: "${repoUrl}"
    jobs:
      - 'Component-Gate-Trigger_{repo_name}'"""

  Boolean jobNotExists = sh(
    returnStatus: true,
    script: """#!/bin/bash -xe
      grep -s 'Component-Gate-Trigger_{repo_name}' ${projectsFile}
    """
  ).asBoolean()

  if (jobNotExists) {
    sh """#!/bin/bash -xe
      echo "${jjb}" >> ${projectsFile}
    """
  } // if
}

void createComponentPreRelease(String name, String repoUrl, List releases, String projectsFile){
  String jjb

  Boolean jobNotExists = sh(
    returnStatus: true,
    script: """#!/bin/bash -xe
      grep -s 'RE-Release-PR_{repo}-{BRANCH}' ${projectsFile}
    """
  ).asBoolean()

  if (jobNotExists) {
    if (!releases){
      // NOTE(mattt): If no releases have been specified, then we just
      // add a job for the project's master branch.
      releases = [["series": "master"]]
    } // if

    jjb = """
- project:
    name: "${name}-re-release-pr"
    repo:
"""

    for ( release in releases ) {
      jjb += """      - ${name}:
          URL: "${repoUrl}"
          BRANCH: "${release['series']}"
"""
    } //for

    jjb += """    jobs:
      - 'RE-Release-PR_{repo}-{BRANCH}'"""

    sh """#!/bin/bash -xe
      echo "${jjb}" >> ${projectsFile}
    """
  } // if
}

void createPrWhisperer(String name, String repoUrl, String projectsFile){
  String jjb = ""

  Boolean jobNotExists = sh(
    returnStatus: true,
    script: """#!/bin/bash -xe
      grep -s 'Pull-Request-Whisperer_{repo}' ${projectsFile}
    """
  ).asBoolean()

  if (jobNotExists) {
    jjb += """
- project:
    name: '${name}-whisperer'
    series:
      - all_branches:
          branches: '.*'
    repo:
      - ${name}:
          repo_url: '${repoUrl}'
    jobs:
      - 'Pull-Request-Whisperer_{repo}'
"""

    sh """#!/bin/bash -xe
      echo "${jjb}" >> ${projectsFile}
    """
  } // if
}

/**
* Create a Checkmarx code scan job when a new Repo is added.
*/
void createCheckmarx(String repoName, String repoUrl, String projectsFile, String jiraProjectKey){
  String jjb = ""
  Boolean jobNotExists = sh(
    returnStatus: true,
    script: """#!/bin/bash -xe
      grep -s '{repoName}-checkmarx' ${projectsFile}
    """
  ).asBoolean()
  if (jobNotExists){
    jjb += """
- project:
    name: '${repoName}-checkmarx'
    scan_type:
      - default
      - pci
    jira_project_key: "${jiraProjectKey}"
    trigger:
      - PM
    repo_name:
      - ${repoName}:
          repo_url: "${repoUrl}"
          branch: master
    jobs:
      - '{trigger}-Checkmarx_{scan_type}-{repo_name}'
    """
    sh """#!/bin/bash -xe
      echo "${jjb}" >> ${projectsFile}
    """
  }
}

WorkflowRun findExistingSuccessfulBuild(WorkflowJob job, String sha) {
  job.getBuilds().find { build ->
    (
      build.isBuilding() == false
      && build.getAction(ParametersAction).getParameter("_BUILD_SHA")
      && build.getAction(ParametersAction).getParameter("_BUILD_SHA").getValue() == sha
      && build.getResult() == Result.fromString("SUCCESS")
    )
  }
}

void testRelease(component_text){
  def component = readYaml text: component_text
  String name = component['name']
  String series = component['release']['series']
  String sha = component['release']['sha']

  List allWorkflowJobs = Hudson.instance.getAllItems(WorkflowJob)
  List releaseJobs = (allWorkflowJobs.findAll {it.displayName =~ /RELEASE_${name}-${series}/})

  def parallelBuilds = [:]

  // Cannot do for (job in jobNames), see:
  // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
  for (j in releaseJobs) {
    WorkflowJob releaseJob = j
    WorkflowRun existingSuccessfulBuild = findExistingSuccessfulBuild(releaseJob, sha)
    if (! existingSuccessfulBuild) {
      String periodicJobName = releaseJob.displayName.replace("RELEASE", "PM")
      WorkflowJob periodicJob = allWorkflowJobs.find {it.displayName == periodicJobName}
      if (periodicJob) {
        existingSuccessfulBuild = findExistingSuccessfulBuild(periodicJob, sha)
      }
    }

    if (! existingSuccessfulBuild) {
      parallelBuilds[releaseJob.displayName] = {
        build(
          job: releaseJob.displayName,
          wait: true,
          parameters: [
            [
              $class: 'StringParameterValue',
              name: 'BRANCH',
              value: sha
            ]
          ]
        )
      }
    } else {
      println("Found existing successful build ${existingSuccessfulBuild}, skipping new test.")
    }
  }

  parallel parallelBuilds
}

void createRelease(String component_text, Boolean from_rc_branch, Boolean re_release){
  build(
    job: "Component-Release",
    wait: true,
    parameters: [
      [
        $class: "StringParameterValue",
        name: "RPC_GATING_BRANCH",
        value: RPC_GATING_BRANCH,
      ],
      [
        $class: "StringParameterValue",
        name: "component_text",
        value: component_text,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_repo",
        value: ghprbGhRepository,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_number",
        value: ghprbPullId,
      ],
      [
        $class: "BooleanParameterValue",
        name: "from_rc_branch",
        value: from_rc_branch,
      ],
      [
        $class: "BooleanParameterValue",
        name: "re_release",
        value: re_release,
      ],
    ]
  )
}

void updateDependents(String componentName){
  def parallelBuilds = [:]
  List allWorkflowJobs = Hudson.instance.getAllItems(WorkflowJob)
  venv = "${WORKSPACE}/.componentvenv"

  sh """#!/bin/bash -xe
    if [ ! -e ${venv} ]; then
      virtualenv --python python3 ${venv}
      set +x; . ${venv}/bin/activate; set -x
      pip install -c '${env.WORKSPACE}/rpc-gating/constraints_rpc_component.txt' rpc_component
    fi
  """

  def dependentComponents_text = sh(
    returnStdout: true,
    script: """#!/bin/bash
      set +x; . ${venv}/bin/activate; set -x
      component --releases-dir ./ dependents --component-name ${componentName} get
    """
  )

  def dependentComponents = readYaml text: dependentComponents_text
  def dependentComponentsToUpdate = dependentComponents["name"]

  for ( dependentComponent in dependentComponentsToUpdate ) {
    jobs = (allWorkflowJobs.findAll { it.displayName =~ /PM-Dep-Update_${dependentComponent}.*/ })
    for ( job in jobs ) {
      parallelBuilds[job.displayName] = {
        build(
          job: job.displayName,
          wait: false,
          parameters: [
            [
              $class: 'StringParameterValue',
              name: 'third_party_dependencies_update',
              value: "false"
            ]
          ]
        )
      }
    }
  }

  parallel parallelBuilds
}


List loadCSV(String str){
  str.split(",").collect {it.trim()}
}


String dumpCSV(List l){
  l.join(",")
}

/**
 * Update string parameter.
 *
 * This procedure updates the value and description of an existing string
 * parameter.
 */
void updateStringParam(String name, String value, String description){
    println "Updating string parameter '${name}' to '${value}'."
    List updatedParam = [new StringParameterValue(name, value, description)]
    ParametersAction existingAction = currentBuild.rawBuild.getAction(ParametersAction)
    ParametersAction newAction = existingAction.createUpdated(updatedParam)
    currentBuild.rawBuild.addOrReplaceAction(newAction)
}

/**
* Get the predicted duration for the current build in milliseconds.
* Prediction is based on up to 6 recent builds.
* See: https://javadoc.jenkins-ci.org/hudson/model/Job.html#getEstimatedDurationCandidates--
* NonCPS because build is not serialisable
*/
@NonCPS
Duration getPredictedDuration(){
  Run build = currentBuild.rawBuild
  Job job = build.getParent()
  return Duration.ofMillis(job.getEstimatedDuration())
}

/**
* Get the predicted end time for this build, based on the time it moved out of the queue
* and its predicted duration (UTC).
* NonCPS because build is not serialisable
*/
@NonCPS
LocalDateTime getPredictedCompletionTime(){
  Run build = currentBuild.rawBuild
  Long startTimeInSeconds = (build.getStartTimeInMillis()/1000).round(MathContext.DECIMAL32).longValueExact()
  LocalDateTime startTime = LocalDateTime.ofEpochSecond(startTimeInSeconds, 0, ZoneOffset.UTC)
  return startTime.plus(getPredictedDuration())
}

/**
* Get date object representing the start of the next maintenance window UTC
*/
LocalDateTime getNextMaintenanceWindowStart(){
  // get current time & date
  LocalDateTime now = LocalDateTime.now()

  // Initially assume the next maintenance window starts today at maintHour today.
  LocalDateTime maintStart = now\
                   .withHour(maintHour)\
                   .withMinute(0)\
                   .withSecond(0)\
                   .withNano(0)

  if (now.getDayOfWeek() == maintDay){
    // its maintenanceDay, we either need to return today's maintenance window
    // or next Monday's.
    if (now > maintStart + maintDuration){
      // return next week's maintenance window
      maintStart = maintStart.plusDays(7)
    }
  } else {
    // Its not Monday.
    Integer doW = now.getDayOfWeek().getValue()
    Integer mDoW = maintDay.getValue()
    Integer daysTillNextMaint = (daysInWeek -(doW - mDoW)) % daysInWeek
    maintStart = maintStart.plusDays(daysTillNextMaint)
  }
  return maintStart
}

/**
* Check if this job is likely to run into the next maintenance window
*/
Boolean willOverlapMaintenanceWindow(){
  // The pr buffer is an hour before the maintenance window starts.
  // PRs must be predicted to finish before the PR buffer starts
  // to be allowed to run. This gives PR jobs an hour to overrun
  // their predicted duration.
  Duration prBuffer = Duration.ofHours(1)

  LocalDateTime completion = getPredictedCompletionTime()
  LocalDateTime maintWindowStart = getNextMaintenanceWindowStart()
  LocalDateTime prBufferStart = maintWindowStart.minus(prBuffer)

  Boolean willOverlap = prBufferStart.isBefore(completion)
  if(willOverlap){
    print("This build is predicted to overlap the next maintenance window")
  }
  return willOverlap
}



Boolean issueExistsForNextMaintenanceWindow(project="RE"){
  LocalDateTime nextWindow = getNextMaintenanceWindowStart()
  String nextWindowDateString = nextWindow.format(DateTimeFormatter.ISO_LOCAL_DATE)
  List issues = jira_query("project=\"${project}\" AND summary ~ '${nextWindowDateString} Maintenance Window' and STATUS not in (Finished)")
  Boolean issueExists = issues.size > 0
  if (issueExists){
    print("Issue exists for next maintenance window: ${issues[0]}")
  }
  return issueExists
}

/**
* Jobs matching the patterns in this function are allowed to run during
* a maintenance window to facilitate testing. Note this only overrides
* the abort check in standard jobs, it won't override quietDown mode.
*/
@NonCPS
Boolean allowBuildDuringMaintenanceWindow(){
  patterns = [
    /RE-Maintenance/,
    /RE-unit/,
    /RPC-Gating-Unit-Tests/,
    /scratchpipeline/
  ]

  for (pattern in patterns){
    if (env.JOB_NAME.contains(pattern)){
      return true
    }
  }
  print("This Job is not allowed to build during maintenance windows")
  return false
}

Boolean maintenanceInProgress(){
  String query
  List issues
  String jiraIssue = ""
  try {
    jiraIssue = maintenanceIssueForDate("today")
  } catch (Exception e){
    // No maintenance issue for today so no maintenance is in progress.
    return false
  }

  Boolean afterStartTime =  LocalTime.now() > LocalTime.of(maintHour, 0)
  query = "key=\"${jiraIssue}\" and STATUS not in (Finished)"
  issues = jira_query(query)
  Boolean maintIssueStillOpen = issues.size() > 0

  if (afterStartTime && maintIssueStillOpen){
    print("Maintenance in progress")
    return true
  }
  return false
}

Boolean shouldAbortForMaintenance(){
    Boolean willOverlapFutureMaintenance = issueExistsForNextMaintenanceWindow() && willOverlapMaintenanceWindow();
    return (willOverlapFutureMaintenance || maintenanceInProgress()) && ! allowBuildDuringMaintenanceWindow()
}

/*
* Create an issue for the next maintenance window, returns the issue key
*/
String getOrCreateIssueForNextMaintenanceWindow(){
  LocalDateTime nextWindow = getNextMaintenanceWindowStart()
  String nextWindowDateString = nextWindow.format(DateTimeFormatter.ISO_LOCAL_DATE)
  String jiraIssue = get_or_create_jira_issue(
    "RE",
    "BACKLOG",
    "${nextWindowDateString} Maintenance Window",
    "Jira issue to collect notes relating to a maintenance window, for more information please see https://rpc-openstack.atlassian.net/wiki/spaces/RE/pages/469794817/RE+Infrastructure+Maintenance+Window",
    ["jenkins", "re", "maintenance"]
  )
  return jiraIssue
}

String jiraLinkFromIssueKey(String issueKey){
  return "https://rpc-openstack.atlassian.net/browse/${issueKey}"
}

void recordAbortDueToMaintenance(){
  String pullLinkComment = ""

  String jiraIssue = maintenanceIssueForDate("today")

  print("Jira Issue Key: ${jiraIssue}")
  String jiraLink=jiraLinkFromIssueKey(jiraIssue)

  if(env.RE_JOB_TRIGGER == "PULL"){
    // Build triggered by a PR

    String comment = "Build ${env.BUILD_URL} was aborted as its estimated completion time overlapped an RE maintenance window. See Jira Issue: ${jiraLink}"
    github.add_comment_to_pr(comment, true)

    // Set pull link string that will be embedded in a Jira comment,
    // so that RE team members reviewing the maintenance ticket can
    // easily find affected PRs.
    pullLinkComment = "PR: ${env.ghprbPullLink}"
  }

  // Store PR data as JSON in the jira issue comment.
  // This will be read back at the end of the maintenance window to automatically
  // restart aborted jobs.
  // Not storing the job id, because there isn't a good way to map job names to
  // trigger phrases so we'll have to recheck_all on every PR that has a failure.
  Map data = [
    repo: env.ghprbGhRepository,
    prnum: env.ghprbPullId,
    link: env.ghprbPullLink,
    build: env.BUILD_URL
  ]
  String json = _write_json_string(obj: data)
  print("Jira Comment: ${json}")
  jiraComment(
    issueKey: jiraIssue,
    body: json
  )
  println("Build aborted as it may have run into the maintenance window. ${pullLinkComment} ${jiraLink}")
  }

/**
* date should be an ISO8601 date YYYY-MM-DD or "today"
*/
String maintenanceIssueForDate(String date="today", project="RE"){
  if (date == "today"){
    LocalDateTime now = LocalDateTime.now()
    date = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
  }
  String query = "project=\"${project}\" AND summary ~ '${date} Maintenance Window'"
  List issues = jira_query(query)
  if (issues.size < 1){
    throw new Exception("No maintenance issue found matching ${query}")
  } else if (issues.size > 1){
    throw new Exception("More than one maintenance issue found matching ${query}: ${issues}")
  }
  return issues[0]
}

/**
* When jobs are aborted due to a maintenance window,
* some json data is added as a comment to the maintenance
* jira issue. This method uses that data to determine
* which PRs need to be rechecked.
*/
void restartAbortedPRBuilds(String maint_date="today"){
  String jiraIssue = maintenanceIssueForDate(maint_date)
  List comments = jira_comments(jiraIssue)
  // Map used to deduplicate comments, so we don't recheck the same PR twice.
  Map prsWithFailures = [:]
  for (comment in comments){
    try {
      Map pr_data = _parse_json_string(json_text: comment)
      if (pr_data['link'] != null){
        prsWithFailures[pr_data['link']] = pr_data
      }
    } catch (groovy.json.JsonException e){
      print("Ignoring non-json comment: ${comment}")
    }
  }
  for (pr in prsWithFailures){
    pr_data = pr.value
    print("Adding recheck_all comment to ${pr_data['link']}")
    github.add_comment_to_pr("recheck_all", true, pr_data['repo'], pr_data['prnum'])
  }

}

/**
 * Run snapshot thaw file if it exists.
 */
void runThawIfSnapshot(){
  String thawFileName = "/gating/thaw/run"
  stage("Execute snapshot thaw if ${thawFileName} present"){
    sh """#!/bin/bash -eu
          if [[ -f ${thawFileName} ]]; then
            echo "Executing thaw."
            ${thawFileName}
          else
            echo "No thaw file found."
          fi
    """
  }
}

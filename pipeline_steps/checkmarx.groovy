import java.security.SecureRandom
import com.rackspace.exceptions.REException

// Upload all folders in the current working directory to checkmarx and scan for vulnerabilities.
// If you wish to scan a subdir of the working dir, call this function within dir("subdir"){}
def scan(String scan_type, String repo_name, String exclude_folders){
    withCredentials([
        string(
            credentialsId: 'CHECKMARX_RE_TEAM_ID',
            variable: 'groupId'
        ),
        string(
            credentialsId: 'CHECKMARX_SERVER',
            variable: 'serverUrl'
        )
    ]){
        presets = [
        // values generated using the snippet generator
        // ${jenkins}/pipeline-syntax/
        // sample step > step: general build step
        //    Build Step > Execute Checkmarx Scan
        "default": "36",
        "pci": "5",
        "all": "1"
        ]
        if (!presets.keySet().contains(scan_type)){
            throw new Exception("Invalid scan type: ${scan_type}, should be default or pci")
        }
        // This step has a habit of throwing NPEs, retry it. RE
        waitTime = 8
        // Initialize a secure random object
        SecureRandom random = new SecureRandom()
        retry(7) {
            // Try within retry so that sleep can be added on failure.
            // This may help if the issue is at the remote end.
            try {
                step([$class: 'CxScanBuilder',
                    avoidDuplicateProjectScans: false, // duplicate detection isn't great and kills scans of the same project with different parameters
                    comment: '',
                    credentialsId: '',
                    excludeFolders: exclude_folders,
                    excludeOpenSourceFolders: '',
                    exclusionsSetting: 'job',
                    failBuildOnNewResults: true,
                    failBuildOnNewSeverity: 'LOW',
                    filterPattern: '''!**/_cvs/**/*, !**/.svn/**/*,   !**/.hg/**/*,   !**/.git/**/*,  !**/.bzr/**/*, !**/bin/**/*,
                    !**/obj/**/*,  !**/backup/**/*, !**/.idea/**/*, !**/*.DS_Store, !**/*.ipr,     !**/*.iws,
                    !**/*.bak,     !**/*.tmp,       !**/*.aac,      !**/*.aif,      !**/*.iff,     !**/*.m3u, !**/*.mid, !**/*.mp3,
                    !**/*.mpa,     !**/*.ra,        !**/*.wav,      !**/*.wma,      !**/*.3g2,     !**/*.3gp, !**/*.asf, !**/*.asx,
                    !**/*.avi,     !**/*.flv,       !**/*.mov,      !**/*.mp4,      !**/*.mpg,     !**/*.rm,  !**/*.swf, !**/*.vob,
                    !**/*.wmv,     !**/*.bmp,       !**/*.gif,      !**/*.jpg,      !**/*.png,     !**/*.psd, !**/*.tif, !**/*.swf,
                    !**/*.jar,     !**/*.zip,       !**/*.rar,      !**/*.exe,      !**/*.dll,     !**/*.pdb, !**/*.7z,  !**/*.gz,
                    !**/*.tar.gz,  !**/*.tar,       !**/*.gz,       !**/*.ahtm,     !**/*.ahtml,   !**/*.fhtml, !**/*.hdm,
                    !**/*.hdml,    !**/*.hsql,      !**/*.ht,       !**/*.hta,      !**/*.htc,     !**/*.htd, !**/*.war, !**/*.ear,
                    !**/*.htmls,   !**/*.ihtml,     !**/*.mht,      !**/*.mhtm,     !**/*.mhtml,   !**/*.ssi, !**/*.stm,
                    !**/*.stml,    !**/*.ttml,      !**/*.txn,      !**/*.xhtm,     !**/*.xhtml,   !**/*.class, !**/*.iml, !Checkmarx/Reports/*.*''',
                    fullScanCycle: 10,
                    generatePdfReport: true,
                    groupId: groupId,
                    includeOpenSourceFolders: '',
                    osaArchiveIncludePatterns: '*.zip, *.war, *.ear, *.tgz',
                    password: '',
                    preset: presets[scan_type],
                    projectName: repo_name,
                    serverUrl: serverUrl,
                    sourceEncoding: '1',
                    username: '',
                    vulnerabilityThresholdEnabled: true,
                    highThreshold: 0,
                    lowThreshold: 0,
                    mediumThreshold: 0,
                    vulnerabilityThresholdResult: 'FAILURE',
                    waitForResultsEnabled: true]
                )
            } catch (Exception e){
                print ("Caught exception while running checkmarx scan: "+e)
                sleep(time: waitTime, unit: "SECONDS")
                // Exponential backoff - double the wait for each retry with a
                // bit of additional random skew, range is [1, 12]
                waitTime = waitTime * 2 + random.nextInt(12) + 1
                // exception must propagate back to the retry call
                throw e
            } //try
        } // retry

        if (Result.fromString(currentBuild.currentResult).isWorseThan(Result.fromString("SUCCESS"))) {
            throw new REException("Checkmarx Scan Threshold Exceeded for repo: " + repo_name)
        }
    } // withCredentials
}

return this

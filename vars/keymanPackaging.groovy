#!/usr/bin/groovy
// Copyright (c) 2019 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

import sil.pipeline.GitHub
import sil.pipeline.Utils

def supportedDistros = 'xenial bionic'
def defaultDistrosToPackage = supportedDistros
def arches_tobuild = 'amd64 i386'

def call(Map params = [:]) {
  def gitHub = new GitHub()
  def utils = new Utils()

  if (gitHub.isPRBuild() && !utils.isManuallyTriggered() && !gitHub.isPRFromTrustedUser()) {
    // ask for permission to build PR from this untrusted user
    pullRequest.comment('A team member has to approve this pull request on the CI server before it can be built...')
    input(message: "Build ${env.BRANCH_NAME} from ${env.CHANGE_AUTHOR} (${env.CHANGE_URL})?")
  }

  ansiColor('xterm') {
    timestamps {
      timeout(time: 60, unit: 'MINUTES', activitiy: true) {
        properties([
          [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: "https://github.com/keymanapp/keyman"],
          parameters([
            string(name: 'DistributionsToPackage', defaultValue: defaultDistrosToPackage, description: 'The distributions to build packages for (separated by space)', trim: false),
					  string(name: "ArchesToPackage", defaultValue: arches_tobuild, description:
						"The architectures to build packages for (separated by space)"),
					  choice(name: 'PackageBuildKind', choices: ['Nightly', 'ReleaseCandidate', 'Release'], description: 'What kind of build is this? A nightly build will have a version suffix like +nightly2019... appended, a release (or a release candidate) will just have the version number.')
          ]),
          // Trigger on GitHub push
          pipelineTriggers([[$class: 'GitHubPushTrigger']])
        ])

        node('packager') {
          stage('checkout source') {
            checkout scm
            stash name: 'sourcetree', includes: 'linux/*,resources/*,common/*'
          }
        }

        def extraBuildArgs = gitHub.isPRBuild() ? '--no-upload' : ''

        // temporary hack while testing pipelines - don't upload package:
        extraBuildArgs = '--no-upload'

        def tasks = [:]
        for (p in ['keyman-keyboardprocessor', 'kmflcomp', 'libkmfl', 'ibus-kmfl', 'keyman-config', 'ibus-keyman']) {
          // don't inline this!
          def packageName = p

          def subDirName
          switch (packageName) {
            case 'keyman-keyboardprocessor':
              subDirName = 'common/engine/keyboardprocessor'
              break
            default:
              subDirName = "linux/${packageName}"
              break
          }

          def fullPackageName
          switch (utils.getBranch()) {
            case 'master':
            default:
              fullPackageName = "${packageName}-alpha"
              break
            case 'beta':
              fullPackageName = "${packageName}-beta"
              break
            case ~/stable.*/:
              fullPackageName = packageName
              break
          }

          tasks["Package build of ${packageName}"] = {
            node('packager') {
              stage("making source package for ${fullPackageName}") {
                unstash name: 'sourcetree'

                sh """#!/bin/bash
# make source package
cd linux
rm -f ${fullPackageName}-packageversion.properties
./scripts/jenkins.sh ${fullPackageName} \$DEBSIGNKEY
buildret="\$?"

if [ "\$buildret" == "0" ]; then echo "PackageVersion=\$(for file in `ls -1 builddebs/${fullPackageName}*_source.build`;do basename \$file _source.build;done|cut -d "_" -f2|cut -d "-" -f1)" > ${fullPackageName}-packageversion.properties; fi
exit \$buildret
"""
              } /* stage */

              if (fileExists("linux/${fullPackageName}-packageversion.properties")) {
                currentBuild.displayName = readFile "linux/${fullPackageName}-packageversion.properties"
              }

              stage("building ${packageName}") {
                sh """#!/bin/bash
if [ "\$PackageBuildKind" = "Release" ]; then
	BUILD_PACKAGE_ARGS="--suite-name main"
elif [ "\$PackageBuildKind" = "ReleaseCandidate" ]; then
	BUILD_PACKAGE_ARGS="--suite-name proposed"
fi

case "${packageName}" in
keyman-keyboardprocessor|ibus-keyman)
  # Don't build these packages on xenial
  build_distros=\${DistributionsToPackage/\{xenial\}/}
  ;;
*)
  build_distros=\$DistributionsToPackage
  ;;
esac

cd ${subDirName}

\$HOME/ci-builder-scripts/bash/build-package --dists "\$DistributionsToPackage" --arches "\$ArchesToPackage" --main-package-name "${fullPackageName}" --supported-distros "${supportedDistros}" --debkeyid \$DEBSIGNKEY --build-in-place \$BUILD_PACKAGE_ARGS ${extraBuildArgs}
"""

                archiveArtifacts 'results/*'
              }
            }
          } /* tasks */
        } /* for */

        parallel tasks
      } /* timeout */
    } /* timestamps */
  } /* ansicolor */
}

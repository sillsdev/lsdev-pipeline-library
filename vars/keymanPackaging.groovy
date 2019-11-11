#!/usr/bin/groovy
// Copyright (c) 2019 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

import sil.pipeline.GitHub
import sil.pipeline.Utils

def call(body) {
  def supportedDistros = 'xenial bionic'

  // evaluate the body block, and collect configuration into the object
  def params = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = params
  body()

  def gitHub = new GitHub()
  def utils = new Utils()

  def distributionsToPackage = params.distributionsToPackage ?: 'xenial bionic'
  def arches = params.arches ?: 'amd64 i386'

  echo '#1'

  if (!gitHub.isPRBuild() && env.BRANCH_NAME != 'master' && env.BRANCH_NAME != 'beta' && env.BRANCH_NAME !=~ /stable/) {
    echo "Skipping build on non-supported branch ${env.BRANCH_NAME}"
    return
  }

  echo '#2'

  def exitJob = false
  ansiColor('xterm') {
    timestamps {
      properties([
        [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: "https://github.com/keymanapp/keyman"],
        parameters([
          string(name: 'DistributionsToPackage', defaultValue: distributionsToPackage, description: 'The distributions to build packages for (separated by space)', trim: false),
          string(name: "ArchesToPackage", defaultValue: arches, description:
          "The architectures to build packages for (separated by space)"),
          choice(name: 'PackageBuildKind', choices: ['Nightly', 'ReleaseCandidate', 'Release'], description: 'What kind of build is this? A nightly build will have a version suffix like +nightly2019... appended, a release (or a release candidate) will just have the version number.')
        ]),
        // Trigger on GitHub push
        pipelineTriggers([[$class: 'GitHubPushTrigger']])
      ])

      echo '#3'
      node('packager') {
        stage('checkout source') {
          checkout scm
          if (gitHub.isPRBuild()) {
            if (!utils.hasMatchingChangedFiles(pullRequest.files, /(linux|common)\/.*/)) {
              echo "Skipping PR since it didn't change any Linux-related files"
              pullRequest.createStatus('success', 'continuous-integration/jenkins/pr-merge', 'Skipping build since it didn\'t change any Linux-related files', env.BUILD_URL)
              currentBuild.result = 'SUCCESS'
              exitJob = true
              return
            }
            if (!utils.isManuallyTriggered() && !gitHub.isPRFromTrustedUser()) {
              // ask for permission to build PR from this untrusted user
              pullRequest.createStatus('pending', 'continuous-integration/jenkins/pr-merge', 'A team member has to approve this pull request on the CI server before it can be built...', env.BUILD_URL)
              input(message: "Build ${env.BRANCH_NAME} from ${env.CHANGE_AUTHOR} (${env.CHANGE_URL})?")
            }

            pullRequest.addLabels(['linux'])
          } else  if (!utils.hasMatchingChangedFiles(pullRequest.files, /(linux|common)\/.*/)) {
            echo "Skipping build since it didn't change any Linux-related files"
            currentBuild.result = 'SUCCESS'
            exitJob = true
            return
          }

          stash name: 'sourcetree', includes: 'linux/,resources/,common/'
        }
      }

      if (exitJob) {
        return
      }

      timeout(time: 60, unit: 'MINUTES', activity: true) {
        // install dependencies
        def matchingNodes = utils.getMatchingNodes('packager', true)
        def dependencyTasks = [:]
        for (int i = 0; i < matchingNodes.size(); i++) {
          def thisNode = matchingNodes[i]
          dependencyTasks["Install dependencies on ${thisNode}"] = {
            node(thisNode) {
              sh 'linux/build/agent/install-deps'
            }
          }
        }
        parallel dependencyTasks

        echo '#4'
        def extraBuildArgs = gitHub.isPRBuild() ? '--no-upload' : ''

        // temporary hack while testing pipelines - don't upload package:
        extraBuildArgs = '--no-upload'

        def tasks = [:]
        for (p in ['keyman-keyboardprocessor', 'kmflcomp', 'libkmfl', 'ibus-kmfl', 'keyman-config', 'ibus-keyman']) {
          // don't inline this!
          def packageName = p

          echo "#5: processing ${packageName}"

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
                echo "Making source package for ${fullPackageName}"
                unstash name: 'sourcetree'

                sh """#!/bin/bash
# make source package
ls -al
cd linux
rm -f ${packageName}-packageversion.properties
ls -al
./scripts/jenkins.sh ${packageName} \$DEBSIGNKEY
buildret="\$?"

if [ "\$buildret" == "0" ]; then echo "\$(for file in `ls -1 builddebs/${packageName}*_source.build`;do basename \$file _source.build;done|cut -d "_" -f2|cut -d "-" -f1)" > ${packageName}-packageversion.properties; fi
cat ${packageName}-packageversion.properties
exit \$buildret
"""
              } /* stage */

              if (fileExists("linux/${packageName}-packageversion.properties")) {
                currentBuild.displayName = readFile "linux/${packageName}-packageversion.properties"
              }

              stage("building ${packageName}") {
                echo "Building ${packageName}"
                sh """#!/bin/bash
if [ "\$PackageBuildKind" = "Release" ]; then
  BUILD_PACKAGE_ARGS="--suite-name main"
elif [ "\$PackageBuildKind" = "ReleaseCandidate" ]; then
  BUILD_PACKAGE_ARGS="--suite-name proposed"
fi

case "${packageName}" in
keyman-keyboardprocessor|ibus-keyman)
  # Don't build these packages on xenial
  build_distros=\${DistributionsToPackage/xenial/}
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

        echo '#6'
        parallel tasks
      } /* timeout */
    } /* timestamps */
  } /* ansicolor */

  if (exitJob) {
    return
  }

  echo '#7'
}

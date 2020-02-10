#!/usr/bin/groovy
// Copyright (c) 2019-2020 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import sil.pipeline.GitHub
import sil.pipeline.Utils

def call(body) {
  def sourcePackagerNode = 'packager && bionic'
  def binaryPackagerNode = 'packager'
  def supportedDistros = 'xenial bionic'
  def changedFileRegex = /(linux|common\/engine\/keyboardprocessor)\/.*|TIER.md|VERSION.md/

  // evaluate the body block, and collect configuration into the object
  def params = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = params
  body()

  def gitHub = new GitHub()
  def utils = new Utils()

  def distributionsToPackage = params.distributionsToPackage ?: 'xenial bionic'
  def arches = params.arches ?: 'amd64 i386'

  if (!gitHub.isPRBuild() && env.BRANCH_NAME != 'master' && env.BRANCH_NAME != 'beta' && env.BRANCH_NAME !=~ /stable/) {
    echo "Skipping build on non-supported branch ${env.BRANCH_NAME}"
    return
  }

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

      def tier
      node('master') {
        stage('checkout source') {
          checkout scm

          sh 'git fetch origin --tags'

          if (gitHub.isPRBuild()) {
            if (!utils.hasMatchingChangedFiles(pullRequest.files, changedFileRegex)) {
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

            // pullRequest.addLabels(['linux'])
          } else if (!utils.isManuallyTriggered()) {
            def changeLogSets = currentBuild.changeSets
            def files = new ArrayList()
            for (int i = 0; i < changeLogSets.size(); i++) {
                def entries = changeLogSets[i].items
                for (int j = 0; j < entries.length; j++) {
                    def entry = entries[j]
                    files = files.plus(new ArrayList(entry.affectedFiles))
                }
            }

            echo "Changed files:"
            for (int i = 0; i < files.size(); i++) {
              echo "    ${i}: ${files[i].path}"
            }

            if (!utils.hasMatchingChangedFiles(files, changedFileRegex)) {
              echo "Skipping build since it didn't change any Linux-related files"
              currentBuild.result = 'SUCCESS'
              exitJob = true
              return
            }
          } else {
            echo "Manually triggered build - skipping check for changed Linux files"
          }

          currentBuild.displayName = sh(
            script: "cat VERSION.md",
            returnStdout: true,
          ).trim() + "-${env.BUILD_NUMBER}"

          echo "Setting build name to ${currentBuild.displayName}"

          tier = sh(
              script: "cat TIER.md",
              returnStdout: true,
            ).trim()

          stash name: 'sourcetree', includes: 'linux/,resources/,common/,TIER.md,VERSION.md'
        }
      }

      if (exitJob) {
        return
      }

      timeout(time: 60, unit: 'MINUTES', activity: true) {
        // install dependencies
        def matchingNodes = utils.getMatchingNodes(sourcePackagerNode, true)
        def dependencyTasks = [:]
        for (int i = 0; i < matchingNodes.size(); i++) {
          def thisNode = matchingNodes[i]
          dependencyTasks["Install dependencies on ${thisNode}"] = {
            node(thisNode) {
              unstash name: 'sourcetree'
              sh 'linux/build/agent/install-deps'
            }
          }
        }
        parallel dependencyTasks

        def extraBuildArgs = gitHub.isPRBuild() ? '--no-upload' : ''

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
            case 'beta':
              fullPackageName = "${packageName}-${tier}"
              break
            case ~/stable.*/:
              fullPackageName = packageName
              break
            case ~/PR-.*/:
              fullPackageName = "${packageName}-${tier}-pr"
              break
            default:
              echo "Unknown branch ${utils.getBranch()}"
              currentBuild.result = 'FAILURE'
              return
          }

          tasks["Package build of ${packageName}"] = {
            node(sourcePackagerNode) {
              stage("making source package for ${fullPackageName}") {
                echo "Making source package for ${fullPackageName}"
                unstash name: 'sourcetree'

                sh """#!/bin/bash
cd linux
./scripts/jenkins.sh ${packageName} \$DEBSIGNKEY
"""
                stash name: "${packageName}-srcpkg", includes: "${subDirName}/${packageName}_*"
              } /* stage */
            } /* node */

            for (d in distributionsToPackage.tokenize()) {
              for (a in arches.tokenize()) {
                // don't inline these two lines!
                def dist = d
                def arch = a

                node(binaryPackagerNode) {
                  stage("building ${packageName} (${dist}/${arch})") {
                    if ((packageName == 'ibus-keyman' || packageName == 'keyman-keyboardprocessor') && dist == 'xenial') {
                      org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(STAGE_NAME)
                    } else {
                      echo "Building ${packageName} (${dist}/${arch})"
                      unstash name: "${packageName}-srcpkg"

                      def buildResult = sh(
                        script: """#!/bin/bash
# Check that we actually want to build this combination!
echo "dist=${dist}; DistributionsToPackage=\$DistributionsToPackage"
if [[ "\$DistributionsToPackage" != *${dist}* ]] || [[ "\$ArchesToPackage" != *${arch}* ]]; then
  echo "Not building ${dist} for ${arch} - not selected"
  exit 50
fi

if [ "\$PackageBuildKind" = "Release" ]; then
  BUILD_PACKAGE_ARGS="--suite-name main"
elif [ "\$PackageBuildKind" = "ReleaseCandidate" ]; then
  BUILD_PACKAGE_ARGS="--suite-name proposed"
fi

basedir=\$(pwd)
cd ${subDirName}

\$HOME/ci-builder-scripts/bash/build-package --dists "${dist}" --arches "${arch}" --main-package-name "${fullPackageName}" --supported-distros "${supportedDistros}" --debkeyid \$DEBSIGNKEY --build-in-place \$BUILD_PACKAGE_ARGS ${extraBuildArgs}
""",
                        returnStatus: true)
                      if (buildResult == 50) {
                        org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(STAGE_NAME)
                      } else if (buildResult != 0) {
                        error "Package build of ${packageName} (${dist}/${arch}) failed"
                      } else {
                        archiveArtifacts 'results/*'
                      }
                    } /* if/else */
                  } /* stage */
                } /* node */
              } /* tasks */
            } /* for arch */
          } /* for dist */
        } /* for package */

        tasks.failFast = true
        parallel tasks
      } /* timeout */
    } /* timestamps */
  } /* ansicolor */

  if (exitJob) {
    return
  }
}

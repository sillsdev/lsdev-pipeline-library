#!/usr/bin/groovy
// Copyright (c) 2019-2023 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import sil.pipeline.GitHub
import sil.pipeline.Utils

// package builds for Keyman 14+
def call(body) {
  def sourcePackagerNode = 'keyman-source'
  def binaryPackagerNode = 'keyman'
  def binaryPackagerNodeJammy = 'keyman && CanBuildJammy'
  def supportedDistros = 'bionic focal jammy lunar mantic'
  def x64OnlyDistros = 'focal jammy lunar mantic'
  def changedFileRegex = /(linux|common\/engine\/keyboardprocessor|common\/core\/desktop|core)\/.*|TIER.md|VERSION.md/
  def defaultArches = 'amd64 i386'

  // evaluate the body block, and collect configuration into the object
  def params = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = params
  body()

  def gitHub = new GitHub()
  def utils = new Utils()

  def distributionsToPackage = params.distributionsToPackage ?: supportedDistros
  def arches = params.arches ?: defaultArches
  def packagesToBuild = params.packagesToBuild ?: ['keyman', 'kmflcomp', 'libkmfl', 'ibus-kmfl']

  def isAlpha  = env.BRANCH_NAME == 'master'
  def isBeta   = env.BRANCH_NAME == 'beta'
  def isStable = env.BRANCH_NAME ==~ /stable(-.+)?/
  if (!gitHub.isPRBuild() && !isAlpha && !isBeta && !isStable) {
    echo "Skipping build on non-supported branch ${env.BRANCH_NAME}"
    return
  }

  def exitJob = false
  ansiColor('xterm') {
    timestamps {
      withCredentials([string(credentialsId: 'trigger-token', variable: 'TriggerToken')]) {
        properties([
          [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: 'https://github.com/keymanapp/keyman'],
          pipelineTriggers([
            // Trigger on GitHub push
            [$class: 'GitHubPushTrigger'],
            // Trigger on URL
            [$class: 'GenericTrigger',
              genericVariables: [
                [key: 'project', value: '$.project'],
                [key: 'branch', value: '$.branch'],
                [key: 'force', value: '$.force'],
                [key: 'tag', value: '$.tag'],
                [key: 'tag2', value: '$.tag2'],
              ],
              causeString: 'URL triggered on $branch',
              token: TriggerToken,
              printContributedVariables: true,
              printPostContent: true,
              silentResponse: false,
              regexpFilterText: '$project',
              regexpFilterExpression: "^pipeline-keyman-packaging/${BRANCH_NAME}\$",
            ],
          ]),
          parameters([
            string(name: 'DistributionsToPackage', defaultValue: distributionsToPackage, description: 'The distributions to build packages for (separated by space)', trim: false),
            string(name: 'ArchesToPackage', defaultValue: arches, description: 'The architectures to build packages for (separated by space)'),
            string(name: 'project', defaultValue: env.project ? env.project : '', description: 'The project to build'),
            string(name: 'branch', defaultValue: env.branch ? env.project : '', description: 'The base branch to build'),
            booleanParam(name: 'force', defaultValue: env.force == 'true', description: 'true to force a build'),
            string(name: 'tag', defaultValue: (utils.isUrlTriggered() && env.tag2) || (utils.isManuallyTriggered() && utils.isReplay()) ? env.tag : '', description: 'The tag to build'),
          ])
        ])
      }

      // For a new branch it is necessary to build this job at least once so that the
      // generic trigger (code above) learns to listen for this branch. The multibranch
      // pipeline job will trigger this build when it detects a new PR. Since we don't
      // know if the PR contains any relevant code and we shouldn't decide this (it's
      // done in a script in the Keyman repo that runs on TC), we exit here.
      if (!utils.isManuallyTriggered() && !utils.isUrlTriggered()) {
        echo 'Exiting - job is neither triggered manually nor by TC script'
        exitJob = true
        return
      }

      haveTag = (utils.isUrlTriggered() && env.tag2) || (utils.isManuallyTriggered() && env.tag)

      def tier
      def repoSuffix

      // set default tier in case TIER.md is missing (currently on beta and stable branch)
      switch (utils.getBranch()) {
        case ~/stable.*/:
          tier = 'stable'
          repoSuffix = ''
          break
        case 'beta':
          tier = 'beta'
          repoSuffix = '-proposed'
          break
        // case 'master':
        // case ~/PR-.*/:
        default:
          tier = 'alpha'
          repoSuffix = '-experimental'
          break
      }

      node('linux') {
        stage('checkout source') {
          checkout scm

          sh 'git fetch -q origin -p --tags && git clean -dxf'

          if (haveTag) {
            sh "git checkout ${env.tag}"
          }

          if (gitHub.isPRBuild() && !utils.isManuallyTriggered()) {
            if (!utils.hasMatchingChangedFiles(pullRequest.files, changedFileRegex)) {
              echo "Skipping PR since it didn't change any Linux-related files"
              pullRequest.createStatus('success', 'continuous-integration/jenkins/pr-merge', 'Skipping build since it didn\'t change any Linux-related files', env.BUILD_URL)
              currentBuild.result = 'SUCCESS'
              exitJob = true
              return
            }
            if (!gitHub.isPRFromTrustedUser()) {
              // ask for permission to build PR from this untrusted user
              pullRequest.createStatus('pending', 'continuous-integration/jenkins/pr-merge', 'A team member has to approve this pull request on the CI server before it can be built...', env.BUILD_URL)
              input(message: "Build ${env.BRANCH_NAME} from ${env.CHANGE_AUTHOR} (${env.CHANGE_URL})?")
            }

            // pullRequest.addLabels(['linux'])
          } else if (!utils.isManuallyTriggered() && !env.force) {
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
          } else if (env.force) {
            echo "Forced build - skipping check for changed Linux files"
          } else {
            echo "Manually triggered build - skipping check for changed Linux files"
          }

          def version = sh(
            script: "cat VERSION.md",
            returnStdout: true,
          ).trim()
          if (!(version ==~ /[0-9]+.+/)) {
            version = sh(
              script: "cat resources/VERSION.md",
              returnStdout: true,
            ).trim()
          }
          currentBuild.displayName = "${version}-${env.BUILD_NUMBER}"

          echo "Setting build name to ${currentBuild.displayName}"

          if (fileExists('TIER.md')) {
            tier = sh(
                script: "cat TIER.md",
                returnStdout: true,
              ).trim()
          }

          stash name: 'sourcetree', includes: 'linux/,debian/,resources/,common/,core/,TIER.md,VERSION.md'
          stash name: 'packages', includes: 'results/*', allowEmpty: true
        }
      }

      if (exitJob) {
        return
      }

      if (gitHub.isPRBuild()) {
        pullRequest.createStatus('pending', 'Test: Keyman packaging (Linux)', 'Jenkins build started', env.BUILD_URL)
      }

      try {
        timeout(time: 60, unit: 'MINUTES', activity: true) {
          def tasks = [:]
          for (p in packagesToBuild) {
            // don't inline this!
            def packageName = p

            def fullPackageName
            def buildPackageArgs
            def preReleaseTag = ''
            switch (utils.getBranch()) {
              case 'master':
                fullPackageName = "${packageName}-${tier}"
                buildPackageArgs = '--suite-name experimental'
                break
              case 'beta':
                fullPackageName = "${packageName}-${tier}"
                buildPackageArgs = '--suite-name proposed'
                break
              case ~/stable.*/:
                fullPackageName = packageName
                buildPackageArgs = '--suite-name main'
                break
              case ~/PR-.*/:
                fullPackageName = "${packageName}-${tier}-pr"
                buildPackageArgs = '--suite-name experimental'
                preReleaseTag = "--prerelease-tag ~${env.BRANCH_NAME}"
                break
              default:
                echo "Unknown branch ${utils.getBranch()}"
                currentBuild.result = 'FAILURE'
                return
            }

            def subDirName
            node(sourcePackagerNode) {
              stage("making source package for ${fullPackageName}") {
                echo "Making source package for ${fullPackageName}"
                sh 'rm -rf *'
                unstash name: 'sourcetree'

                switch (packageName) {
                  case 'keyman':
                    subDirName = ''
                    break
                  case 'keyman-keyboardprocessor':
                    subDirName = fileExists('common/core/desktop') ?
                      'common/core/desktop/' : 'common/engine/keyboardprocessor/'
                    break
                  default:
                    subDirName = fileExists("linux/legacy/${packageName}") ? "linux/legacy/${packageName}/" : "linux/${packageName}/"
                    break
                }

                sh """#!/bin/bash
cd linux
./scripts/jenkins.sh ${packageName} \$DEBSIGNKEY
"""
                stash name: "${packageName}-srcpkg", includes: "${subDirName}${packageName}_*, ${subDirName}debian/"
              } /* stage */
            } /* node */

            for (d in env.DistributionsToPackage.tokenize()) {
              for (a in arches.tokenize()) {
                // don't inline these two lines!
                def dist = d
                def arch = a

                if (arch == 'i386' && x64OnlyDistros.contains(dist)) {
                  // we don't build for 32-bit on focal and later
                  continue
                }

                def nodeToUse = (dist == 'mantic' || dist == 'lunar' || dist == 'jammy') ? binaryPackagerNodeJammy : binaryPackagerNode;

                tasks["Package build of ${packageName} for ${dist}/${arch}"] = {
                  node(nodeToUse) {
                    stage("building ${packageName} (${dist}/${arch})") {
                      echo "Building ${packageName} (${dist}/${arch})"

                      def retryCount = 0
                      retry(3) {
                        if (retryCount > 0) {
                          // we're retrying a second or third time. Wait for a minute - the
                          // failure might have been caused by some server updates or internet
                          // troubles which might work again after a while.
                          sleep(60 /*seconds*/)
                        }
                        retryCount++
                        sh 'rm -rf *'

                        unstash name: "${packageName}-srcpkg"

                        def buildResult = sh(
                          script: """#!/bin/bash
# Check that we actually want to build this combination!
echo "dist=${dist}; DistributionsToPackage=\$DistributionsToPackage"
if [[ "\$DistributionsToPackage" != *${dist}* ]] || [[ "\$ArchesToPackage" != *${arch}* ]]; then
echo "Not building ${dist} for ${arch} - not selected"
exit 50
fi

basedir=\$(pwd)
[ "${subDirName}" != "" ] && cd ${subDirName}

\$HOME/ci-builder-scripts/bash/build-package --dists "${dist}" --arches "${arch}" --main-package-name "${fullPackageName}" --supported-distros "${supportedDistros}" --debkeyid \$DEBSIGNKEY --build-in-place --no-upload ${preReleaseTag} ${buildPackageArgs}
""",
                          returnStatus: true)
                        if (buildResult == 50) {
                          // buildResult 50 means that we don't have to build for this
                          // architecture (i386) because the architecture is listed as
                          // 'all' and so gets build when we build for amd64.
                          org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(STAGE_NAME)
                        } else if (buildResult != 0) {
                          error "Package build of ${packageName} (${dist}/${arch}) failed in the previous step (exit code ${buildResult})"
                        } else {
                          lock('packages') {
                            unstash name: 'packages'
                            stash name: 'packages', includes: 'results/*'
                          }
                          archiveArtifacts artifacts: 'results/*'
                        }
                        sh 'rm -rf *'
                      } // retry
                    } /* stage */
                  } /* node */
                } /* tasks */
              } /* for arch */
            } /* for dist */
          } /* for package */

          tasks.failFast = true
          parallel tasks

          // Verify API
          node(binaryPackagerNodeJammy) {
            stage("Verifying API for libkmnkbp0.so") {
              unstash name: "keyman-srcpkg"

              if (fileExists('debian/libkmnkbp0-0.symbols')) {
                unstash name: 'packages'
                echo "Verifying API for libkmnkbp0.so"
                def version = sh(
                  script: "dpkg-parsechangelog --show-field Version | cut -d'-' -f 1",
                  returnStdout: true,
                ).trim()
                def tmpDir = sh(script: """#!/bin/bash
                  mktemp -d
                  """, returnStdout: true).trim()

                def result = sh(script: """#!/bin/bash
mkdir -p ${tmpDir}
dpkg -x results/libkmnkbp0-0*jammy*_amd64.deb ${tmpDir}/
cp debian/libkmnkbp0-0.symbols .
dpkg-gensymbols -v${version} -plibkmnkbp0-0 -e${tmpDir}/usr/lib/x86_64-linux-gnu/libkmnkbp0.so* -Olibkmnkbp0-0.symbols -c4
RESULT=\$?
rm -rf ${tmpDir}
exit \$RESULT
""",
                  returnStatus: true)
                archiveArtifacts artifacts: "libkmnkbp0-0.symbols"
                if (result != 0) {
                  echo "API verification failed for libkmnkbp0.so"
                  error "API verification failed for libkmnkbp0.so"
                }
              } else {
                echo "Skipping API verification - no symbols file found"
              }
            }
          }

          // Upload packages
          if (!gitHub.isPRBuild() && haveTag) {
            node(binaryPackagerNode) {
              unstash name: 'packages'
              for (d in env.DistributionsToPackage.tokenize()) {
                for (a in arches.tokenize()) {
                  // don't inline these two lines!
                  def dist = d
                  def arch = a
                  stage("Uploading packages for ${dist}/${arch} to ${dist}${repoSuffix}") {
                    sh """#!/bin/bash
cd results/
if ls *${dist}*${arch}.changes > /dev/null 2>&1; then
  for pkg in *${dist}*${arch}.changes; do
    pkgname=\${pkg%%_*}
    mapfile -t debs < <(dcmd --deb \$pkg)
    if [ "\${pkgname:0:3}" == "lib" ]; then
      # "libg" and "libk" instead of "l"
      subdir=\${pkgname:0:4}
    else
      subdir=\${pkgname:0:1}
    fi

    if wget --spider http://linux.lsdev.sil.org/ubuntu/pool/main/\${subdir}/\${pkgname}/\${debs[0]} 2>/dev/null; then
      echo "Skipping upload of \${pkg} - already exists"
    else
      dput -U llso:ubuntu/${dist}${repoSuffix} \${pkg}
    fi
  done
else
  echo "No packages for ${dist}/${arch} to upload"
fi
"""
                  } // stage
                } // for arch
              } // for dist
            } // node
          } // if !isPR
        } /* timeout */

        if (gitHub.isPRBuild()) {
          pullRequest.createStatus('success', 'Test: Keyman packaging (Linux)', 'Jenkins build succeeded', env.BUILD_URL)
        } else if (haveTag) {
          currentBuild.description = "<span style='background-color: yellow'>${env.tag}</span>"
        }
      } catch(Exception ex) {
        currentBuild.result = 'FAILURE'
        if (gitHub.isPRBuild()) {
          pullRequest.createStatus('failure', 'Test: Keyman packaging (Linux)', 'Jenkins build failed', env.BUILD_URL)
        } else {
          mail subject: "[keyman-packaging] Jenkins package build ${env.BUILD_NUMBER} failed",
            body: "${env.BUILD_URL}",
            to: 'eb1@sil.org, marc_durdin@sil.org, darcy_wong@sil.org'
        }
      }
    } /* timestamps */
  } /* ansicolor */
}

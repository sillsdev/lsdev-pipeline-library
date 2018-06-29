#!/usr/bin/groovy
// Copyright (c) 2018 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

// Scripted Pipeline
// NOTE: If we'd use a declarative pipeline we wouldn't be able to split this job
// into multiple methods. The doc says (https://jenkins.io/doc/book/pipeline/shared-libraries/#defining-declarative-pipelines)
// "Only entire pipeline`s can be defined in shared libraries as of this time. This can only be
// done in `vars/*.groovy`, and only in a call method.

import sil.pipeline.BuildStages
import sil.pipeline.Utils

def call(body) {
	// evaluate the body block, and collect configuration into the object
	def params = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = params
	body()

	def winNodeSpec = params.winNodeSpec ?: 'windows'
	def winTool = params.winTool ?: 'msbuild'
	def linuxNodeSpec = params.linuxNodeSpec ?: 'linux'
	def linuxTool = params.linuxTool ?: 'xbuild'
	def uploadNuGet = params.uploadNuGet ?: false
	def configuration = params.configuration ?: 'Release'
	def nupkgPath = params.nupkgPath ?: "output/${configuration}/*.nupkg"

	def utils = new Utils()
	def buildStages = new BuildStages()
	def repo = utils.getRepoName()
	buildStages.initialize(repo, configuration)

	Map tasks = [failFast: true]

	tasks['Windows'] = buildStages.getWinBuildStage(winNodeSpec, winTool, uploadNuGet, nupkgPath)
	tasks['Linux'] = buildStages.getLinuxBuildStage(linuxNodeSpec, linuxTool)

	ansiColor('xterm') {
		timestamps {
			properties([
				[$class: 'GithubProjectProperty', displayName: '', projectUrlStr: "https://github.com/sillsdev/${repo}"],
				// Trigger on GitHub push
				pipelineTriggers([[$class: 'GitHubPushTrigger']])
			])
			timeout(time: 60, unit: 'MINUTES') {
				try {
					parallel(tasks)
				} catch(error) {
					currentBuild.result = "FAILED"
					throw error
				}

				if (uploadNuGet && currentBuild.result != "UNSTABLE" && currentBuild != "FAILED") {
					buildStages.uploadStagedNugetPackages(winNodeSpec, nupkgPath)
				}
			}
		}
	}
}

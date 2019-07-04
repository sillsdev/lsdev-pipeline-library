#!/usr/bin/groovy
// Copyright (c) 2018-2019 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

package sil.pipeline

String _repoName
String _configuration
String _buildFileName

def initialize(String repoName, String buildFileName, String configuration) {
	_repoName = repoName
	_buildFileName = buildFileName
	_configuration = configuration
}

def getWinBuildStage(String winNodeSpec, String winTool, Boolean uploadNuGet, String nupkgPath,
	Boolean clean, String frameworkLabel, Boolean restorePackages) {
	return {
		throttle([ 'one-per-node' ]) {
			node(winNodeSpec) {
				def msbuild = tool winTool
				def git = tool(name: 'Default', type: 'git')
				def buildFileName = _buildFileName.replace('/', '\\')
				def framework
				if (frameworkLabel != null) {
					framework = tool(name: frameworkLabel, type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool')
				}

				stage('Checkout Win') {
					checkout scm

					bat """
						"${git}" fetch origin
						"${git}" fetch origin --tags
						"""

					if (clean) {
						bat """
							"${git}" clean -dxf
							"""
					}
				}

				if (restorePackages) {
					stage('Package Restore Win') {
						echo "Restoring packages"
						bat """
							"${msbuild}" /t:Restore /property:Configuration=${_configuration} ${buildFileName}
							"""
					}
				}

				stage('Build Win') {
					echo "Building ${_repoName}"
					bat """
						"${msbuild}" /t:Build /property:Configuration=${_configuration} ${buildFileName}
						"""

					if (fileExists("output/${_configuration}/version.txt")) {
						version = readFile "output/${_configuration}/version.txt"
						currentBuild.displayName = "${version.trim()}-${env.BUILD_NUMBER}"
					}
				}

				stage('Tests Win') {
					try {
						echo "Running unit tests"
						bat """
							if exist "%TEMP%\\${_repoName}" del /s /f /q "%TEMP%\\${_repoName}"
							if not exist "%TEMP%\\${_repoName}" mkdir "%TEMP%\\${_repoName}"
							set TEMP=%TEMP%\\${_repoName}
							set TMP=%TEMP%
							"${msbuild}" /t:TestOnly /property:Configuration=${_configuration} ${buildFileName}
							del /s /f /q %TEMP%
							"""
						currentBuild.result = "SUCCESS"
					} catch(err) {
						currentBuild.result = "UNSTABLE"
					} finally {
						nunit testResultsPattern: '**/TestResults.xml'
					}
				}

				if (currentBuild.result != "UNSTABLE") {
					stage('Build NuGet Package') {
						echo "Building nuget package for ${_repoName}"
						bat """
							"${msbuild}" /t:Pack /property:Configuration=${_configuration} ${buildFileName}
							"""
					}

					if (uploadNuGet) {
						stash name: "nuget-packages", includes: nupkgPath
					}
				}
			}
		}
	}
}

def uploadStagedNugetPackages(String winNodeSpec, String nupkgPath) {
	node(winNodeSpec) {
		unstash "nuget-packages"
		def utils = new Utils()
		if (utils.isPullRequest()) {
			stage('Archive nuget') {
				archiveArtifacts nupkgPath
			}
		} else {
			stage('Upload nuget') {
				if (!fileExists("build/nuget.exe")) {
					echo "Download nuget"
					powershell 'Invoke-WebRequest https://dist.nuget.org/win-x86-commandline/latest/nuget.exe -OutFile build/nuget.exe'
				}
				echo "Upload nuget package"
				try {
					withCredentials([string(credentialsId: 'nuget-api-key', variable: 'NuGetApiKey')]) {
						bat """
							FOR %%i IN (${nupkgPath.replace('/', '\\')}) DO (
								IF NOT "%%~xi" == ".snupkg" (
									build\\nuget.exe push -Source https://api.nuget.org/v3/index.json %%i ${NuGetApiKey}
								)
							)
							"""
					}
				} catch (err) {
					echo "Uploading of nuget package failed (ignored): ${err}"
				}
				archiveArtifacts nupkgPath
			}
		}
	}
}

def getLinuxBuildStage(String linuxNodeSpec, String linuxTool, Boolean clean,
	String frameworkLabel, Boolean restorePackages) {
	return {
		throttle([ 'one-per-node' ]) {
			node(linuxNodeSpec) {
				wrap([$class: 'Xvfb']) {
					def msbuild = tool linuxTool
					def git = tool(name: 'Default', type: 'git')
					def framework
					if (frameworkLabel != null) {
						framework = tool(name: frameworkLabel, type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool')
					}

					stage('Checkout Linux') {
						checkout scm

						sh "${git} fetch origin"

						sh "${git} fetch origin --tags"

						if (clean) {
							sh "${git} clean -dxf"
						}
					}

					if (restorePackages) {
						stage('Package Restore Linux') {
							echo "Restoring packages"
							sh """#!/bin/bash
								"${msbuild}" /t:Restore /property:Configuration=${_configuration} ${_buildFileName}
								"""
						}
					}

					stage('Build Linux') {
						echo "Building ${_buildFileName}"
						sh """#!/bin/bash
							"${msbuild}" /t:Build /property:Configuration=${_configuration} ${_buildFileName}
							"""
					}

					stage('Tests Linux') {
						try {
							echo "Running unit tests"
							sh """#!/bin/bash
								"${msbuild}" /t:TestOnly /property:Configuration=${_configuration} ${_buildFileName}
								"""
							currentBuild.result = "SUCCESS"
						} catch(err) {
							currentBuild.result = "UNSTABLE"
						} finally {
							nunit testResultsPattern: '**/TestResults.xml'
						}
					}
				}
			}
		}
	}
}

return this
#!/usr/bin/groovy
// Copyright (c) 2018 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

package sil.pipeline

String _repoName
String _configuration

def initialize(String repoName, String configuration) {
	_repoName = repoName
	_configuration = configuration
}

def getWinBuildStage(String winNodeSpec, String winTool, Boolean uploadNuGet) {
	return {
		node(winNodeSpec) {
			def msbuild = tool winTool
			def git = tool(name: 'Default', type: 'git')

			stage('Checkout Win') {
				checkout scm

				bat """
					"${git}" fetch origin --tags
					"""
			}

			stage('Build Win') {
				echo "Building ${_repoName}"
				bat """
					"${msbuild}" /t:Build /property:Configuration=${_configuration} build/${_repoName}.proj
					"""

				version = readFile "output/${_configuration}/version.txt"
				currentBuild.displayName = "${version.trim()}-${env.BUILD_NUMBER}"
			}

			stage('Tests Win') {
				echo "Running unit tests"
				bat """
					"${msbuild}" /t:TestOnly /property:Configuration=${_configuration} build/${_repoName}.proj
					"""
			}

			if (uploadNuGet) {
				stage('Upload nuget') {
					def utils = new Utils()
					if (!utils.isPullRequest()) {
						echo "Upload nuget package"
						withCredentials([string(credentialsId: 'nuget-api-key', variable: 'NuGetApiKey')]) {
							bat """
								build\\nuget.exe push -Source https://www.nuget.org/api/v2/package output\\nugetbuild\\*\\*.nupkg ${NuGetApiKey}
								"""
						}
						archiveArtifacts 'output/nugetbuild/**/*.nupkg'
					}
				}
			}

			nunit testResultsPattern: '** /TestResults.xml'
		}
	}
}

def getLinuxBuildStage(String linuxNodeSpec, String linuxTool) {
	return {
		node(linuxNodeSpec) {
			def msbuild = tool linuxTool
			def git = tool(name: 'Default', type: 'git')

			stage('Checkout Linux') {
				checkout scm

				sh "${git} fetch origin --tags"
			}

			stage('Build Linux') {
				echo "Building ${_repoName}"
				sh """#!/bin/bash
					"${msbuild}" /t:Build /property:Configuration=${_configuration} build/${_repoName}.proj
					"""
			}

			stage('Tests Linux') {
				echo "Running unit tests"
				sh """#!/bin/bash
					"${msbuild}" /t:TestOnly /property:Configuration=${_configuration} build/${_repoName}.proj
					"""
			}

			nunit testResultsPattern: '**/TestResults.xml'
		}
	}
}

return this
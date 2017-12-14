#!/usr/bin/groovy
// Copyright (c) 2017 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

// NOTE: Currently (12/2017) it's not yet possible to do what we want (dynamically generate
// parallel tasks) with a declarative pipeline, so we use a scripted pipeline here.

def call(Map params = [:]) {
	def nodeLabel = params.containsKey('label') ? params.label : null
	def repo = params.containsKey('repo') ? params.repo : scm.getUserRemoteConfigs()[0].getUrl()
	def fullPath = new URI(repo).getPath()
	fullPath = fullPath.endsWith(".git") ? fullPath.substring(0, fullPath.length() - 4) : fullPath
	def defaultDir = fullPath.lastIndexOf('/') > 0 ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath
	def dir = params.containsKey('dir') ? params.dir : defaultDir
	def branch = params.containsKey('branch') ? params.branch : getBranch()

	def tasks = getTasks(nodeLabel, repo, dir, branch)

	timestamps {
		stage('Checkout') {
			return parallel(tasks)
		}
	}
}

private def getTasks(String nodeLabel, String repo, String dir, String branch) {
	def matchingNodes = getMatchingNodes(nodeLabel)
	def tasks = [:]
	for (int i = 0; i < matchingNodes.size(); i++) {
		def thisNode = matchingNodes[i]
		tasks["task-${thisNode}"] = {
			node(thisNode) {
				sh """#!/bin/bash -e
cd $HOME
[ ! -d "${dir}" ] && git clone --depth 1 --recurse-submodules --branch ${branch} ${repo} "${dir}"
cd "${dir}"
git fetch origin
git reset --hard origin/${branch}
"""
			}
		}
	}
	return tasks
}

@NonCPS
private def getMatchingNodes(String nodeLabel) {
	def matchingNodes = []
	jenkins.model.Jenkins.instance.nodes.each { n ->
		if (n.getAssignedLabels().any { x -> x.getExpression().equals(nodeLabel) }) {
			matchingNodes.add(n.getDisplayName())
		}
	}
	return matchingNodes
}

private def getBranch() {
	def branchName = scm.getBranches()[0].getName()
	return branchName.lastIndexOf('/') > 0 ? branchName.substring(branchName.lastIndexOf('/') + 1) : branchName
}

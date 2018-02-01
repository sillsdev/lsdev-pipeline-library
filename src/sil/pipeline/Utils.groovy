#!/usr/bin/groovy
// Copyright (c) 2018 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

package sil.pipeline

@NonCPS
def getMatchingNodes(String nodeLabel) {
	def matchingNodes = []
	jenkins.model.Jenkins.instance.nodes.each { n ->
		if (n.getAssignedLabels().any { x -> x.getExpression().equals(nodeLabel) }) {
			matchingNodes.add(n.getDisplayName())
		}
	}
	return matchingNodes
}

def getBranch() {
	def branchName = scm.getBranches()[0].getName()
	return branchName.lastIndexOf('/') > 0 ? branchName.substring(branchName.lastIndexOf('/') + 1) : branchName
}

return this

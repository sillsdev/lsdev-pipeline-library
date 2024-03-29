#!/usr/bin/groovy
// Copyright (c) 2018-2019 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

package sil.pipeline

@NonCPS
def getMatchingNodes(String nodeLabel, Boolean excludeOfflineNodes = false) {
  def matchingNodes = []
  jenkins.model.Jenkins.instance.nodes.each { n ->
    if (n.getAssignedLabels().any { x -> x.getExpression().equals(nodeLabel) } &&
      (!excludeOfflineNodes || n.getComputer().isOnline())) {
      matchingNodes.add(n.getDisplayName())
    }
  }
  return matchingNodes
}

String getBranch() {
  def branchName = scm.getBranches()[0].getName()
  return branchName.lastIndexOf('/') > 0 ? branchName.substring(branchName.lastIndexOf('/') + 1) : branchName
}

String getRepoName() {
  return scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last() - '.git'
}

Boolean isPullRequest() {
  return getBranch().startsWith("PR-")
}

// Returns true if any file exists that matches the file specification `spec`.
// Contrary to `fileExists`, this method can contain wildcards in the file spec.
def anyFileExists(spec) {
  if (spec.indexOf('*') < 0) {
    return fileExists(spec)
  }

  def beforeWildcard = spec.substring(0, spec.indexOf('*'))
  def basedir = beforeWildcard.substring(0, beforeWildcard.lastIndexOf('/'))
  def pattern = spec.substring(basedir.length() + 1)

  return new FileNameFinder().getFileNames(basedir, pattern).size() > 0
}

def downloadFile(address, filePath) {
  new File(filePath).withOutputStream { out ->
    out << new URL(address).openStream()
  }
}

String getUserTriggeringBuild() {
  userCause=currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
  return userCause ? userCause.userId : ''
}

Boolean isManuallyTriggered() {
  return currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause') ? true : false
}

Boolean isReplay() {
  return currentBuild.getBuildCauses('org.jenkinsci.plugins.workflow.cps.replay.ReplayCause') ? true : false
}

Boolean isUrlTriggered() {
  return currentBuild.getBuildCauses('org.jenkinsci.plugins.gwt.GenericCause') ? true : false
}

Boolean isGitHubTriggered() {
  return currentBuild.getBuildCauses('jenkins.branch.BranchIndexingCause') ? true : false
}

Boolean hasMatchingChangedFiles(files, regexString) {
  for (commitFile in files) {
    if (commitFile.filename =~ regexString) {
      return true
    }
  }
  return false
}

@NonCPS
def ArrayList getMatchingAgentsForReboot(String nodeLabel) {
  def labelExpression = hudson.model.Label.get(nodeLabel)
  def matchingAgents = []
  jenkins.model.Jenkins.get().nodes.each { n ->
    agent = n.getComputer()
    if (labelExpression.matches(n) && agent.isOnline()) {
      matchingAgents.add(agent)
    }
  }
  return matchingAgents
}

// To manually (un-)suspend an agent, run in Jenkins Script Console:
// jenkins.model.Jenkins.get().getNode('autopackager-2').getComputer().setAcceptingTasks(true)
@NonCPS
def acceptTasksOnAgents(ArrayList agents, Boolean acceptTasks) {
  for (i = 0; i < agents.size(); i++) {
    agents[i].setAcceptingTasks(acceptTasks)
  }
}

@NonCPS
def rebootMatchingAgents(ArrayList matchingAgents) {
  for (i = 0; i < matchingAgents.size(); ) {
    agent = matchingAgents[i]
    if (agent.countBusy() == 0) {
      println 'Rebooting ' + agent.getDisplayName()
      hudson.util.RemotingDiagnostics.executeGroovy('"sudo reboot".execute()', agent.getChannel());
      agent.setAcceptingTasks(true)
      matchingAgents.remove(i)
    } else {
      i++
    }
  }
  return true
}

return this

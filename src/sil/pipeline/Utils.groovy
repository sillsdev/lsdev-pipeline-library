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
  return currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause') != null
}

Boolean hasMatchingChangedFiles(regexString) {
  def changeLogSets = currentBuild.changeSets
  for (int i = 0; i < changeLogSets.size(); i++) {
      def entries = changeLogSets[i].items
      for (int j = 0; j < entries.length; j++) {
          def entry = entries[j]
          echo "${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
          def files = new ArrayList(entry.affectedFiles)
          for (int k = 0; k < files.size(); k++) {
              def file = files[k]
              echo "  ${file.editType.name} ${file.path}"
          }
      }
  }
  return true
}

return this

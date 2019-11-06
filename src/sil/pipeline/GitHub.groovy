#!/usr/bin/groovy
// Copyright (c) 2019 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

package sil.pipeline

import hudson.model.Job
import jenkins.scm.api.mixin.ChangeRequestSCMHead
import jenkins.scm.api.mixin.TagSCMHead
import jenkins.scm.api.SCMSourceOwners
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty
import org.jenkinsci.plugins.github_branch_source.Connector
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource

@NonCPS
private Boolean isPRBuildInternal(Job build_parent) {
  build_parent.getProperty(BranchJobProperty).branch.head in ChangeRequestSCMHead
}

Boolean isPRBuild() {
  isPRBuildInternal(currentBuild.rawBuild.parent)
}

@NonCPS
private Boolean isTagBuildInternal(Job build_parent) {
  build_parent.getProperty(BranchJobProperty).branch.head in TagSCMHead
}

Boolean isTagBuild() {
  isTagBuildInternal(currentBuild.rawBuild.parent)
}

Boolean isPRFromTrustedUser() {
  return isMemberOrCollaborator(env.CHANGE_AUTHOR, getRepoName())
}

private String getRepoName() {
  return env.JOB_NAME.tokenize('/').first()
}

Boolean isMemberOrCollaborator(String changeAuthor, repoName) {
  def ghSource = getGHSourceObject(repoName)

  def credentials = Connector.lookupScanCredentials(ghSource.getOwner(), 'https://api.github.com',ghSource.getCredentialsId())
  def github = Connector.connect('https://api.github.com', credentials)

  def fullName = ghSource.getRepoOwner() + "/" + ghSource.getRepository()
  def ghRepository = github.getRepository(fullName)

  def collaboratorNames = new HashSet<>(ghRepository.getCollaboratorNames())
  return collaboratorNames.contains(changeAuthor)
}

@NonCPS
private def getGHSourceObject(id) {
  for (owner in SCMSourceOwners.all()) {
    def source = owner.getSCMSources().stream()
      .filter { Objects.equals(id, it.getId()) && !(it in jenkins.scm.impl.NullSCMSource) }
      .findFirst()
      .orElse(null)
    if (source) {
      return source;
    }
  }
  return null;
}

private String getRepoURL() {
  def repoUrl = sh(
    script: 'git config --get remote.origin.url',
    returnStdout: true,
  ).trim()
  return repoUrl
}

private String getCommitSha() {
  def commitSha = sh(
    script: "git rev-parse HEAD^1",
    returnStdout: true,
  ).trim()
  return commitSha
}

void setBuildStatus(String message, String state, String context = 'continuous-integration/jenkins/pr-merge') {
  sh "git log -1 && git log -1 HEAD^ && git log -1 HEAD^2"
  // workaround https://issues.jenkins-ci.org/browse/JENKINS-38674
  repoUrl = getRepoURL()
  commitSha = getCommitSha()

  echo "repoUrl=${repoUrl}, commit=${commitSha}"

  step([
      $class: "GitHubCommitStatusSetter",
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
      commitShaSource: [$class: "ManuallyEnteredShaSource", sha: commitSha],
      contextSource: [$class: "ManuallyEnteredCommitContextSource", context: context],
      errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ])
}

return this

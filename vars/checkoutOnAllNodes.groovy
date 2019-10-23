#!/usr/bin/groovy
// Copyright (c) 2017 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

// NOTE: Currently (12/2017) it's not yet possible to do what we want (dynamically generate
// parallel tasks) with a declarative pipeline, so we use a scripted pipeline here.

import sil.pipeline.Utils

def call(Map params = [:]) {
  def utils = new Utils()
  def nodeLabel = params.containsKey('label') ? params.label : null
  def repo = params.containsKey('repo') ? params.repo : scm.getUserRemoteConfigs()[0].getUrl()
  def fullPath = new URI(repo).getPath()
  fullPath = fullPath.endsWith(".git") ? fullPath.substring(0, fullPath.length() - 4) : fullPath
  def defaultDir = fullPath.lastIndexOf('/') > 0 ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath
  def dir = params.containsKey('dir') ? params.dir : defaultDir
  def branch = params.containsKey('branch') ? params.branch : utils.getBranch()

  def tasks = getTasks(utils, nodeLabel, repo, dir, branch)

  ansiColor('xterm') {
    timestamps {
      stage('Checkout') {
        return parallel(tasks)
      }
    }
  }
}

private def getTasks(Utils utils, String nodeLabel, String repo, String dir, String branch) {
  def matchingNodes = utils.getMatchingNodes(nodeLabel, true)
  def tasks = [:]
  for (int i = 0; i < matchingNodes.size(); i++) {
    def thisNode = matchingNodes[i]
    tasks["task-${thisNode}"] = {
      node(thisNode) {
        sh """#!/bin/bash -e
cd $HOME
echo "HOME=$HOME,dir=${dir}"
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

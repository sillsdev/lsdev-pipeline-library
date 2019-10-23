#!/usr/bin/groovy
// Copyright (c) 2018 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

// NOTE: Currently (12/2017) it's not yet possible to do what we want (dynamically generate
// parallel tasks) with a declarative pipeline, so we use a scripted pipeline here.

import sil.pipeline.Utils

def call(Map params = [:]) {
  def nodeLabel = params.containsKey('label') ? params.label : null
  def command = params.containsKey('command') ? params.command : null

  def tasks = getTasks(nodeLabel, command)

  ansiColor('xterm') {
    timestamps {
      stage('Checkout') {
        return parallel(tasks)
      }
    }
  }
}

private def getTasks(String nodeLabel, String command) {
  def utils = new Utils()
  def matchingNodes = utils.getMatchingNodes(nodeLabel, true)
  def tasks = [:]
  for (int i = 0; i < matchingNodes.size(); i++) {
    def thisNode = matchingNodes[i]
    tasks["task-${thisNode}"] = {
      node(thisNode) {
        sh command
      }
    }
  }
  return tasks
}

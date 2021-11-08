#!/usr/bin/groovy
// Copyright (c) 2021 SIL International
// This software is licensed under the MIT license (http://opensource.org/licenses/MIT)

import sil.pipeline.Utils

def call(Map params = [:]) {
  def nodeLabel = params.label

  if (!nodeLabel)
    return null

  ansiColor('xterm') {
    timestamps {
      stage('Reboot') {
        return node {
          def utils = new Utils()
          def matchingAgents = utils.getMatchingAgentsForReboot(nodeLabel)

          println 'Rebooting the following agents:'
          for (i = 0; i < matchingAgents.size(); i++) {
            println '    ' + matchingAgents[i].getDisplayName()
          }

          utils.acceptTasksOnAgents(matchingAgents, false)

          while (matchingAgents.size() > 0) {
            utils.rebootMatchingAgents(matchingAgents)

            if (matchingAgents.size() > 0) {
              println 'Waiting for agents to finish current builds...'
              sleep(10 /* seconds */)
            }
          }
        }
      }
    }
  }
}

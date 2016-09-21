/*
 * Copyright (c) 2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 */
'use strict';

/**
 * This class is handling the agents retrieval
 * It sets to the array of agents
 * @author Ilya Buziuk
 */
export class CheAgent {

  /**
   * Default constructor that is using resource
   * @ngInject for Dependency injection
   */
   constructor ($resource) {

    // keep resource
    this.$resource = $resource;

    // agents
    this.agents = [];

    // remote call
    this.remoteAgentAPI = this.$resource('/api/agent',{}, {
      getAgents: {method: 'GET', url: '/api/agent', isArray: true
    }});
  }

  /**
   * Fetch the agents
   */
  fetchAgents() {
    let promise = this.remoteAgentAPI.getAgents().$promise;
    let updatedPromise = promise.then((agents) => {
      // reset global list
      this.agents.length = 0;

      agents.forEach((agent) => {
        this.agents.push(agent);
      });
    });

    return updatedPromise;
  }

  /**
   * Gets all agents
   * @returns {Array}
   */
  getAgents() {
    return this.agents;
  }
  
}
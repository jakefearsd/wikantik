/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.work;

/**
 * Who may approve a deployment. The product's gate decides whether an agent may at all; autonomy never opens it.
 * A deployment can carry items of several projects, so standing is asked of every one of them.
 */
public final class DeployGates {

    static final String PERSON = "A person approves deployments of this product.";
    static final String LEAD =
        "Approving this deployment takes a lead of every project it carries, or a wiki administrator.";

    private DeployGates() {
    }

    /**
     * Under a {@code human} gate: a wiki administrator, or a person who leads every project the deployment carries
     * an item from; never an agent. Under an {@code agent} gate: also anyone, person or agent, who is a member of
     * every such project (so the agent that requested it may approve it).
     */
    public static Decision mayApprove( final Actor a, final DeployGate gate, final boolean leadOfEveryProject,
                                       final boolean memberOfEveryProject ) {
        final boolean agent = a.kind() == Actor.Kind.AGENT;
        if ( agent && gate == DeployGate.HUMAN ) {
            return Decision.deny( PERSON );
        }
        if ( a.instanceAdmin() || ( !agent && leadOfEveryProject ) ) {
            return Decision.allow();
        }
        return gate == DeployGate.AGENT && memberOfEveryProject ? Decision.allow() : Decision.deny( LEAD );
    }
}

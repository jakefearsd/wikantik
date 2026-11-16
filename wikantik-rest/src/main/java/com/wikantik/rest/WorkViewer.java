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
package com.wikantik.rest;

/** Who a work response is for: their login (for {@code mine}/{@code self}), whether they may see logins, and, for an agent, its id. */
record WorkViewer( String login, boolean admin, Long agentId ) {

    WorkViewer( final String login, final boolean admin ) {
        this( login, admin, null );
    }

    /** Whether the viewer is this agent (compared by id: an agent's name is not its identity). */
    boolean isAgent( final long id ) {
        return agentId != null && agentId == id;
    }

    /** Whether the viewer is the person with this login (never an agent that happens to carry the same name). */
    boolean isPerson( final String otherLogin ) {
        return agentId == null && otherLogin.equals( login );
    }
}

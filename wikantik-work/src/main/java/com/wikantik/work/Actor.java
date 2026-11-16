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

import java.util.Set;

/** Who is acting: a person or an agent, with group memberships and the wiki-admin flag. */
public record Actor( String login, Set<String> groups, boolean admin, Kind kind, Long agentId ) {

    /** An agent is never an admin whatever flag it is built with: the flag is a person's, so it is dropped here. */
    public Actor {
        admin = admin && kind == Kind.HUMAN;
    }

    /** Whether the actor is a person or an agent. */
    public enum Kind { HUMAN, AGENT }

    /** An agent actor: never admin, in no groups; identified by its registry id. */
    public static Actor agent( final long agentId, final String name ) {
        return new Actor( name, Set.of(), false, Kind.AGENT, agentId );
    }

    /** Whether the actor is a person with the wiki-admin flag; the same as {@link #admin()}, which only a person can be. */
    public boolean instanceAdmin() {
        return admin;
    }

    /** A human actor. */
    public static Actor human( final String login, final Set<String> groups, final boolean admin ) {
        return new Actor( login, groups, admin, Kind.HUMAN, null );
    }
}

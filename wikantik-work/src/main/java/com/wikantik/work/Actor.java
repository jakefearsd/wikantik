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

/**
 * Who is acting: a person or an agent, with group memberships and the wiki-admin flag. An agent also carries its
 * {@code sponsor}: the person who stands behind it, resolved from the directory for this request (their groups and
 * admin flag as they are now). The sponsor only ever limits the agent (see {@code WorkStanding}); none of the
 * sponsor's groups or flags become the agent's. An agent with no sponsor has no standing anywhere.
 */
public record Actor( String login, Set<String> groups, boolean admin, Kind kind, Long agentId, Actor sponsor ) {

    /** An agent is never an admin whatever flag it is built with: the flag is a person's, so it is dropped here. */
    public Actor {
        admin = admin && kind == Kind.HUMAN;
        if ( sponsor != null && ( kind != Kind.AGENT || sponsor.kind() != Kind.HUMAN ) ) {
            throw new IllegalArgumentException( "Only an agent has a sponsor, and a sponsor is a person." );
        }
        if ( kind == Kind.SYSTEM && ( agentId != null || groups != null && !groups.isEmpty() ) ) {
            throw new IllegalArgumentException( "A system actor has no agent id and no groups." );
        }
    }

    /** Whether the actor is a person or an agent. */
    public enum Kind { HUMAN, AGENT, SYSTEM }

    /**
     * An agent actor with no sponsor resolved: enough for the store, which attributes writes by id, and for tests of
     * it. At the service it has no standing in any project.
     */
    public static Actor agent( final long agentId, final String name ) {
        return new Actor( name, Set.of(), false, Kind.AGENT, agentId, null );
    }

    /** An agent actor: never admin, in no groups; identified by its registry id, limited by its sponsor. */
    public static Actor agent( final long agentId, final String name, final Actor sponsor ) {
        return new Actor( name, Set.of(), false, Kind.AGENT, agentId, sponsor );
    }

    /** Whether the actor is a person with the wiki-admin flag; the same as {@link #admin()}, which only a person can be. */
    public boolean instanceAdmin() {
        return admin;
    }

    /** The code host acting through its webhook: never admin, no groups, no agent id; its login is {@code codehost:<host>}. */
    public static Actor system( final String host ) {
        return new Actor( "codehost:" + host, Set.of(), false, Kind.SYSTEM, null, null );
    }

    /** A human actor. */
    public static Actor human( final String login, final Set<String> groups, final boolean admin ) {
        return new Actor( login, groups, admin, Kind.HUMAN, null, null );
    }

    /**
     * How a stored actor of kind {@link Kind#SYSTEM} is shown: {@code codehost:github} is "GitHub", any other code
     * host "Code host". Never a lookup in the user directory: the stored text is not a login.
     */
    public static String label( final String storedActor ) {
        return "codehost:github".equals( storedActor ) ? "GitHub" : "Code host";
    }

    /** The shown name of an event's actor: an agent by its name, a system actor by {@link #label}, a person by the lookup. */
    static String shown( final Kind kind, final String stored, final java.util.function.Function< String, String > names ) {
        return switch ( kind ) {
            case AGENT -> stored;
            case SYSTEM -> label( stored );
            default -> names.apply( stored );
        };
    }
}

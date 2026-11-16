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

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An actor's standing: its effective role in a project and the projects it can see. The one place the sponsor
 * ceiling is applied, to reading as much as to acting.
 *
 * <p>A person's standing is their own member rows (direct or through a group). An agent's is the lower of two
 * things: what its own member row gives it, and what its sponsor, acting as themselves right now, would have. The
 * sponsor is a ceiling and never a source: an agent with no member row has nothing, whoever sponsors it; an agent
 * with no sponsor has nothing, whatever its rows say. A sponsor who is a wiki admin may do everything, so limits
 * nothing. Nothing here is stored or cached: the sponsor on the actor was resolved for this request.
 *
 * <p>No other class of the module asks a store for a role or for the visible projects
 * ({@code WorkStandingSourceRuleTest}).
 */
final class WorkStanding {

    private final WorkStore store;

    WorkStanding( final WorkStore store ) {
        this.store = store;
    }

    /** The actor's effective role in the project; empty when it has none (the project is then hidden from it). */
    Optional< WorkRole > roleIn( final long projectId, final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.AGENT ) {
            return store.roleFor( projectId, actor );
        }
        final Actor sponsor = actor.sponsor();
        if ( sponsor == null ) {
            return Optional.empty();
        }
        final Optional< WorkRole > own = store.roleFor( projectId, actor );
        if ( own.isEmpty() || sponsor.instanceAdmin() ) {
            return own;
        }
        final Optional< WorkRole > ceiling = store.roleFor( projectId, sponsor );
        if ( ceiling.isEmpty() ) {
            return Optional.empty();
        }
        return ceiling.get().atLeast( own.get() ) ? own : ceiling;
    }

    /** The projects the actor can see: for an agent, those it is a member of that its sponsor can see too. */
    List< Project > visibleProjects( final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.AGENT ) {
            return store.visibleProjects( actor );
        }
        final Actor sponsor = actor.sponsor();
        if ( sponsor == null ) {
            return List.of();
        }
        final List< Project > own = store.visibleProjects( actor );
        if ( own.isEmpty() || sponsor.instanceAdmin() ) {
            return own;
        }
        final Set< Long > sponsors = new HashSet<>();
        for ( final Project p : store.visibleProjects( sponsor ) ) {
            sponsors.add( p.id() );
        }
        return own.stream().filter( p -> sponsors.contains( p.id() ) ).toList();
    }
}

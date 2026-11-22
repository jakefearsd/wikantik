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

import com.wikantik.work.WorkScopes.Scope;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The rules of saved filters. They are people's: an agent has none. A shared filter is pinned to its own project and
 * searches that project only; a project the caller cannot see is as good as absent, for its shared filters too.
 */
final class SavedFilters {

    private static final int MAX_LISTED = 200;
    private static final int MAX_NAME = 80;
    /** Saved filters one person may keep, private and shared together. */
    static final int MAX_PER_OWNER = 100;
    private static final String AGENT_REFUSAL = "Saved filters belong to people.";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;

    SavedFilters( final WorkStore store, final WorkScopes scopes, final WorkPeople people ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
    }

    /** The caller's own filters and the shared filters of projects they can see; none for an agent. */
    List< SavedFilterView > saved( final Actor actor ) throws SQLException {
        if ( actor.kind() == Actor.Kind.AGENT ) {
            return List.of();
        }
        final Set< Long > visible = visibleIds( actor );
        final List< SavedFilterView > out = new ArrayList<>();
        for ( final SavedFilter f : store.notifyStores().filters().visibleTo( actor.login(), visible, MAX_LISTED ) ) {
            if ( f.projectId() == null || visible.contains( f.projectId() ) ) {
                out.add( viewOf( actor, f ) );
            }
        }
        return out;
    }

    SavedFilterView save( final Actor actor, final SavedFilterDraft draft ) throws SQLException {
        refuseAgent( actor );
        final String name = cleanName( draft.name() );
        if ( store.notifyStores().filters().countOwned( actor.login() ) >= MAX_PER_OWNER ) {
            throw new WorkValidationException( "name", "You have " + MAX_PER_OWNER
                + " saved filters; delete one before saving another." );
        }
        final String key = draft.projectKey() == null || draft.projectKey().isBlank() ? null : draft.projectKey();
        if ( draft.shared() && key == null ) {
            throw new WorkValidationException( "project", "A shared filter belongs to a project." );
        }
        final Scope scope = key == null ? null : projectScope( actor, key, draft.shared() );
        final SortedMap< String, String > stored = storable( actor, draft.filter(), draft.shared() ? scope : null );
        final SavedFilter saved = store.notifyStores().filters().insert( actor.login(), name,
            scope == null ? null : scope.project().id(), draft.shared(), stored );
        return new SavedFilterView( saved, true, true );
    }

    SavedFilterView rename( final Actor actor, final long id, final String name ) throws SQLException {
        refuseAgent( actor );
        final String clean = cleanName( name );
        final SavedFilter f = reachable( actor, id );
        if ( !f.owner().equals( actor.login() ) ) {
            throw new WorkDeniedException( "Only its owner may rename a saved filter." );
        }
        requireProjectVisible( actor, f );
        return new SavedFilterView( store.notifyStores().filters().rename( id, clean ), true, true );
    }

    void delete( final Actor actor, final long id ) throws SQLException {
        refuseAgent( actor );
        final SavedFilter f = reachable( actor, id );
        if ( f.owner().equals( actor.login() ) ) {
            requireProjectVisible( actor, f );
        } else {
            scopes.scope( actor, f.projectKey() ).require( Action.MANAGE_PROJECT, null );
        }
        store.notifyStores().filters().delete( id );
    }

    // ---- helpers

    private static void refuseAgent( final Actor actor ) {
        if ( actor.kind() == Actor.Kind.AGENT ) {
            throw new WorkDeniedException( AGENT_REFUSAL );
        }
    }

    private Set< Long > visibleIds( final Actor actor ) throws SQLException {
        final Set< Long > ids = new HashSet<>();
        for ( final Project p : scopes.standing().visibleProjects( actor ) ) {
            ids.add( p.id() );
        }
        return ids;
    }

    /** The filter if the caller may know it exists: their own, or a shared one of a project they can see. */
    private SavedFilter reachable( final Actor actor, final long id ) throws SQLException {
        final SavedFilter f = store.notifyStores().filters().byId( id ).orElseThrow( SavedFilters::notFound );
        if ( f.owner().equals( actor.login() ) ) {
            return f;
        }
        if ( !f.shared() || f.projectId() == null || !scopes.standing().seesProject( f.projectId(), actor ) ) {
            throw notFound();
        }
        return f;
    }

    /** A filter pinned to a project the caller can no longer see is as good as absent, even to its owner. */
    private void requireProjectVisible( final Actor actor, final SavedFilter f ) throws SQLException {
        if ( f.projectId() != null && !scopes.standing().seesProject( f.projectId(), actor ) ) {
            throw notFound();
        }
    }

    private static WorkNotFoundException notFound() {
        return new WorkNotFoundException( WorkScopes.NOT_FOUND );
    }

    private SavedFilterView viewOf( final Actor actor, final SavedFilter f ) throws SQLException {
        final boolean mine = f.owner().equals( actor.login() );
        return new SavedFilterView( f, mine, mine || ( f.shared() && leads( actor, f ) ) );
    }

    private boolean leads( final Actor actor, final SavedFilter f ) throws SQLException {
        return actor.instanceAdmin() || f.projectId() != null && scopes.standing().roleIn( f.projectId(), actor )
            .filter( r -> r.atLeast( WorkRole.LEAD ) ).isPresent();
    }

    /** The project's scope for a save: viewable; for a share also allowed and writable. */
    private Scope projectScope( final Actor actor, final String key, final boolean shared ) throws SQLException {
        final Scope scope = scopes.scope( actor, key );
        scope.require( Action.VIEW, null );
        if ( shared ) {
            scope.require( Action.SHARE_FILTER, null );
            scope.requireWritable();
        }
        return scope;
    }

    private static String cleanName( final String name ) {
        final String trimmed = name == null ? "" : name.trim();
        if ( trimmed.isEmpty() || trimmed.length() > MAX_NAME || trimmed.chars().anyMatch( Character::isISOControl ) ) {
            throw new WorkValidationException( "name", "A saved filter needs a name of at most 80 characters." );
        }
        return trimmed;
    }

    /** The parameters to store: validated, normalised, people as logins, a shared filter's project pinned. */
    private SortedMap< String, String > storable( final Actor actor, final Map< String, String > raw,
                                                  final Scope pinned ) {
        final Map< String, String > in = raw == null ? Map.of() : raw;
        for ( final String key : List.of( "cursor", "limit" ) ) {
            if ( in.containsKey( key ) ) {
                throw new WorkValidationException( key, "Not part of a saved filter." );
            }
        }
        final FindFilter parsed = FindParams.parse( in );
        final TreeMap< String, String > out = new TreeMap<>( FindParams.toParams( parsed ) );
        person( actor, out, "assignee" );
        person( actor, out, "reporter" );
        if ( pinned != null ) {
            pinProject( parsed, pinned, out );
        }
        return out;
    }

    private void person( final Actor actor, final Map< String, String > out, final String field ) {
        final String ref = out.get( field );
        if ( ref != null && !FindFilter.ME.equals( ref ) ) {
            out.put( field, people.login( actor, ref )
                .orElseThrow( () -> new WorkValidationException( field, "No one has that name." ) ) );
        }
    }

    private static void pinProject( final FindFilter parsed, final Scope pinned, final Map< String, String > out ) {
        final String key = pinned.project().key();
        final Set< String > named = new HashSet<>();
        parsed.projectKeys().forEach( k -> named.add( k.toUpperCase( Locale.ROOT ) ) );
        if ( !named.isEmpty() && !named.equals( Set.of( key.toUpperCase( Locale.ROOT ) ) ) ) {
            throw new WorkValidationException( "projects", "A shared filter searches its own project only." );
        }
        out.put( "projects", key );
    }
}

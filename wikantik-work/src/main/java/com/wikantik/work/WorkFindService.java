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
import java.util.List;
import java.util.Optional;

/**
 * The cross-project find and the saved filters, reached through {@link WorkFeatures#find()}. A find covers exactly
 * the projects the caller can see right now, resolved by {@link WorkStanding} on every page (a cursor never widens
 * what a caller can see); a project they cannot see answers as one that does not exist.
 */
public final class WorkFindService {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final ItemViews views;
    private final SavedFilters filters;

    WorkFindService( final WorkStore store, final WorkScopes scopes, final WorkPeople people, final ItemViews views ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
        this.views = views;
        this.filters = new SavedFilters( store, scopes, people );
    }

    /**
     * One page of the items the caller can see that match the filter.
     *
     * @throws WorkValidationException on field {@code cursor} when the marker is not valid for this search
     */
    public FindPage find( final Actor actor, final FindFilter filter ) throws SQLException {
        final List< Project > visible = scopes.standing().visibleProjects( actor );
        final Optional< FindSpec > spec = FindSpecs.resolve( actor, filter, visible, store.products(), people );
        if ( spec.isEmpty() ) {
            return FindPage.EMPTY;
        }
        final int limit = FindSpecs.limit( filter );
        final List< Item > rows = store.notifyStores().finder().find( spec.get() );
        final List< Item > kept = rows.size() > limit ? rows.subList( 0, limit ) : rows;
        final String next = rows.size() > limit
            ? FindCursor.after( kept.get( kept.size() - 1 ), filter.sort() ).encode() : null;
        return new FindPage( views.summariseAcross( kept ), next );
    }

    /** The caller's own saved filters and the shared filters of projects they can see; empty for an agent. */
    public List< SavedFilterView > saved( final Actor actor ) throws SQLException {
        return filters.saved( actor );
    }

    /**
     * Saves a filter. A shared one is pinned to its project and needs a member's standing there.
     *
     * @throws WorkConflictException reason {@code exists} when the caller already has a filter of that name
     */
    public SavedFilterView save( final Actor actor, final SavedFilterDraft draft ) throws SQLException {
        return filters.save( actor, draft );
    }

    /** Renames the caller's own filter. */
    public SavedFilterView rename( final Actor actor, final long id, final String name ) throws SQLException {
        return filters.rename( actor, id, name );
    }

    /** Deletes the caller's own filter, or a shared one as a lead of its project or a wiki admin. */
    public void delete( final Actor actor, final long id ) throws SQLException {
        filters.delete( actor, id );
    }
}

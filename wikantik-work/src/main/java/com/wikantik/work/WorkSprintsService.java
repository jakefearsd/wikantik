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

import com.wikantik.work.WorkScopes.ItemScope;
import com.wikantik.work.WorkScopes.Scope;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sprints, reached through {@link WorkFeatures#sprints()}. Every call resolves the sprint's project through
 * {@link WorkScopes}, so a sprint of a project the caller cannot see is "not found", exactly as an unknown id is.
 */
public final class WorkSprintsService {

    /** Velocity looks back over this many closed sprints. */
    private static final int VELOCITY_SPRINTS = 6;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale( 1 );
    private static final SprintTotals EMPTY = new SprintTotals( 0, ZERO, 0, ZERO );

    /** A sprint with what it holds now: items and points, and how many of each are done. */
    public record SprintView( Sprint sprint, int items, BigDecimal points, int doneItems, BigDecimal donePoints ) {
    }

    private final WorkStore store;
    private final WorkScopes scopes;
    private final ItemViews views;
    private final Clock clock;
    private final SprintReports reports;

    WorkSprintsService( final WorkStore store, final WorkScopes scopes, final ItemViews views, final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.views = views;
        this.clock = clock;
        this.reports = new SprintReports( store, views );
    }

    /** The project's sprints with their current totals: active, then planned, then closed. */
    public List< SprintView > list( final Actor actor, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        final Map< Long, SprintTotals > totals = store.sprints().totals( s.project().id() );
        final List< SprintView > out = new ArrayList<>();
        for ( final Sprint sprint : store.sprints().forProject( s.project().id() ) ) {
            out.add( view( sprint, totals.get( sprint.id() ) ) );
        }
        return out;
    }

    public SprintView get( final Actor actor, final long sprintId ) throws SQLException {
        final Sprint sprint = sprintOf( actor, sprintId ).sprint;
        return viewOf( sprint );
    }

    /** The last six closed sprints, oldest first. */
    public List< Velocity > velocity( final Actor actor, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        final List< Sprint > closed = new ArrayList<>( store.sprints().closed( s.project().id(), VELOCITY_SPRINTS ) );
        Collections.reverse( closed );
        final List< Velocity > out = new ArrayList<>();
        for ( final Sprint c : closed ) {
            out.add( new Velocity( c.id(), c.name(), c.endsOn(), orZero( c.committedItems() ),
                orZero( c.committedPoints() ), orZero( c.completedItems() ), orZero( c.completedPoints() ) ) );
        }
        return out;
    }

    /** The sprint's burndown and how its membership moved since the start; days are the clock's zone's days. */
    public SprintReport report( final Actor actor, final long sprintId ) throws SQLException {
        final Sprint sprint = sprintOf( actor, sprintId ).sprint();
        final SprintHistory history = store.sprints().eventsFor( sprint.projectId(), sprint.id() );
        return reports.report( sprint, viewOf( sprint ), history, LocalDate.now( clock ), clock.getZone() );
    }

    /** An epic's points through its children (P2-7); a task has none. */
    public BigDecimal childPoints( final Actor actor, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, itemKey );
        is.scope().require( Action.VIEW, is.item() );
        return store.sprints().childPoints( is.item().id() );
    }

    public SprintView create( final Actor actor, final String projectKey, final SprintDraft draft )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.MANAGE_SPRINT, null );
        s.requireWritable();
        final Sprint made = store.sprints().create( s.project(), SprintInputs.normalise( draft ), actor );
        return viewOf( made );
    }

    public SprintView update( final Actor actor, final long sprintId, final int expectedVersion,
                              final SprintDraft draft ) throws SQLException {
        final Found f = managed( actor, sprintId );
        final SprintDraft clean = SprintInputs.normalise( draft );
        return viewOf( store.sprints().update( f.sprint().id(), expectedVersion, clean, actor ) );
    }

    public SprintView start( final Actor actor, final long sprintId, final int expectedVersion )
            throws SQLException {
        final Found f = managed( actor, sprintId );
        return viewOf( store.sprints().start( f.sprint().id(), expectedVersion, actor ) );
    }

    /** Closes the sprint, moving its unfinished work to the planned sprint {@code moveOpenToSprintId}, or the backlog. */
    public SprintView close( final Actor actor, final long sprintId, final int expectedVersion,
                             final Long moveOpenToSprintId ) throws SQLException {
        final Found f = managed( actor, sprintId );
        return viewOf( store.sprints().close( f.sprint().id(), expectedVersion, moveOpenToSprintId, actor ) );
    }

    /** The sprint as the caller sees it, resolved inside its project's visibility; used by the report too. */
    Found sprintOf( final Actor actor, final long sprintId ) throws SQLException {
        final Sprint sprint = store.sprints().byId( sprintId )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        final Scope s = scopes.scope( actor, sprint.projectKey() );
        s.require( Action.VIEW, null );
        return new Found( s, sprint );
    }

    private Found managed( final Actor actor, final long sprintId ) throws SQLException {
        final Found f = sprintOf( actor, sprintId );
        f.scope().require( Action.MANAGE_SPRINT, null );
        f.scope().requireWritable();
        return f;
    }

    private SprintView viewOf( final Sprint sprint ) throws SQLException {
        return view( sprint, store.sprints().totals( sprint.projectId() ).get( sprint.id() ) );
    }

    private static SprintView view( final Sprint sprint, final SprintTotals totals ) {
        final SprintTotals t = totals == null ? EMPTY : totals;
        return new SprintView( sprint, t.items(), t.points(), t.doneItems(), t.donePoints() );
    }

    private static int orZero( final Integer n ) {
        return n == null ? 0 : n;
    }

    private static BigDecimal orZero( final BigDecimal n ) {
        return n == null ? ZERO : n;
    }

    /** A sprint with the scope of its project. */
    record Found( Scope scope, Sprint sprint ) {
    }
}

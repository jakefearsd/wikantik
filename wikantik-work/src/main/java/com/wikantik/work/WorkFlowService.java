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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Flow figures (throughput, cycle and lead time, work in progress) of one project, computed on every read from the
 * event log: nothing is stored. Every statement is pinned to the one project the caller's standing resolves; an unseen
 * project is "not found". Reached through {@link WorkFeatures#flow()}.
 */
public final class WorkFlowService {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final ItemViews views;
    private final Clock clock;

    WorkFlowService( final WorkStore store, final WorkScopes scopes, final ItemViews views, final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.views = views;
        this.clock = clock;
    }

    /**
     * The project's flow report for the window and filters in {@code query} (null for the last 90 days, unfiltered).
     * With a sprint and no dates the window is the sprint's own. A sprint that is unknown or another project's is
     * "not found"; a malformed label is a validation error naming {@code labels}.
     */
    public FlowReport project( final Actor actor, final String projectKey, final FlowQuery query ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        final FlowQuery q = query == null ? new FlowQuery( null, null, null, null, null, null ) : query;
        final Sprint sprint = sprintOf( s, q.sprintId() );
        final boolean sprintDates = sprint != null && q.from() == null && q.to() == null;
        final MetricWindow w = MetricWindow.of( sprintDates ? sprint.startsOn() : q.from(),
            sprintDates ? sprint.endsOn() : q.to(), clock );
        final ItemFilter pinned = views.pinned( actor, s, new ItemFilter( null, null, q.assignee(), null, null, null,
            null, null, 0, null, null, q.type(), q.label() == null ? null : Set.of( q.label() ),
            sprint == null ? null : new SprintRef( SprintRef.Kind.ID, sprint.id() ) ) );
        final WorkFlowStore flow = store.notifyStores().flow();
        final List< WorkFlowStore.ClosedItem > closed = flow.closedItems( pinned, w.start(), w.end() );
        return new FlowReport( w.from(), w.to(), w.zone().getId(), Flow.throughput( closed, w ),
            Flow.cycleTime( closed ), Flow.leadTime( closed ), Flow.wip( flow.openItems( pinned ), clock.instant() ) );
    }

    /**
     * The project's DORA report: the production deployments that carry its items and the changes of its items.
     * {@code query} null is the last 90 days across every product; a product that does not exist is "not found"
     * (an archived one is fine). A failure's end may be a deployment filed under another project, but only the
     * duration, a count and a flag ever leave the store.
     */
    public DoraReport dora( final Actor actor, final String projectKey, final DoraQuery query ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        final DoraQuery q = query == null ? new DoraQuery( null, null, null ) : query;
        final Product product = q.productKey() == null ? null : store.product( q.productKey().trim() )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        return new DoraReports( store.notifyStores().flow() ).build( MetricWindow.of( q.from(), q.to(), clock ),
            s.project().id(), product == null ? null : product.id(), product == null ? null : product.key() );
    }

    /**
     * One product's DORA report across every project. Wiki admins only ({@link WorkDeniedException} otherwise); an
     * unknown product is "not found".
     */
    public DoraReport doraForProduct( final Actor actor, final String productKey, final LocalDate from,
                                      final LocalDate to ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product product = store.product( productKey == null ? "" : productKey.trim() )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + productKey ) );
        return new DoraReports( store.notifyStores().flow() ).build( MetricWindow.of( from, to, clock ), null,
            product.id(), product.key() );
    }

    private Sprint sprintOf( final Scope s, final Long sprintId ) throws SQLException {
        if ( sprintId == null ) {
            return null;
        }
        return store.sprints().byId( sprintId ).filter( sp -> sp.projectId() == s.project().id() )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
    }
}

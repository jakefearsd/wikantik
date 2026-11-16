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
import com.wikantik.work.WorkService.ItemSummary;
import com.wikantik.work.WorkService.ItemView;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Claims, release, hand-off and the claimable lists: the claims facade, reached through
 * {@link WorkService#claims()}. Only agents claim; people are assigned work. Every change to a claim, status,
 * assignee or queue is written by the store with its event in the same transaction, so this class never changes
 * an item itself, it validates and delegates.
 */
public final class WorkClaimsService {

    /** The outcome of a claim: the item as it now stands and whether the agent's own claim was renewed. */
    public record ClaimResult( ItemView view, boolean renewed ) {
    }

    /**
     * A hand-off: the version the caller saw, the status to move to (null keeps it), exactly one of a person (by
     * display name; a login from a wiki admin) or a queue, and the note that explains it.
     */
    public record Handoff( int expectedVersion, WorkStatus status, String assignee, String queue, String note ) {
    }

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MAX_NOTE = 2000;
    private static final int SWEEP_BATCH = 500;
    private static final String CANNOT_CLAIM = "This item cannot be claimed right now.";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final ItemViews views;
    private final ItemPatchApplier applier;
    private final WorkSettings settings;
    private final GuardedListener listener;

    WorkClaimsService( final WorkStore store, final WorkScopes scopes, final WorkPeople people, final ItemViews views,
                       final ItemPatchApplier applier, final WorkSettings settings, final GuardedListener listener ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
        this.views = views;
        this.applier = applier;
        this.settings = settings;
        this.listener = listener;
    }

    /** How long a claim lasts before it lapses: configuration, not data, so it takes no actor. */
    public Duration claimExpiry() {
        return settings.claimExpiry();
    }

    // ---- lists

    /**
     * Unclaimed ready or review items waiting in a queue. An agent sees its own queues in the projects where it is
     * a {@code member}; a person sees every queue in the projects they can see. {@code limit} defaults to 20 and is
     * at most 50. A named project is resolved like any other (unknown and hidden are the same 404); a named queue
     * the agent does not serve yields nothing.
     */
    public List< ItemSummary > claimable( final Actor a, final String projectKey, final String queue,
                                          final int limit ) throws SQLException {
        if ( a.kind() == Actor.Kind.AGENT && a.agentId() == null ) {
            return List.of();
        }
        final Set< Long > ids = claimableProjects( a, projectKey );
        if ( ids.isEmpty() ) {
            return List.of();
        }
        final int n = limit <= 0 ? DEFAULT_LIMIT : Math.min( limit, MAX_LIMIT );
        final Long agentId = a.kind() == Actor.Kind.AGENT ? a.agentId() : null;
        final List< Item > found = store.claims().claimable( agentId, ids, PatchValues.blankToNull( queue ), n );
        return summarise( found );
    }

    private Set< Long > claimableProjects( final Actor a, final String projectKey ) throws SQLException {
        final boolean agent = a.kind() == Actor.Kind.AGENT;
        final Set< Long > ids = new LinkedHashSet<>();
        if ( projectKey != null && !projectKey.isBlank() ) {
            final Scope s = scopes.scope( a, projectKey );
            s.require( Action.VIEW, null );
            if ( s.project().archivedAt() == null && ( !agent || s.role() == WorkRole.MEMBER ) ) {
                ids.add( s.project().id() );
            }
            return ids;
        }
        for ( final Project p : store.visibleProjects( a ) ) {
            if ( p.archivedAt() == null && ( !agent || store.roleFor( p.id(), a ).orElse( null ) == WorkRole.MEMBER ) ) {
                ids.add( p.id() );
            }
        }
        return ids;
    }

    private List< ItemSummary > summarise( final List< Item > items ) throws SQLException {
        final Map< Long, List< Item > > byProject = new LinkedHashMap<>();
        for ( final Item i : items ) {
            byProject.computeIfAbsent( i.projectId(), k -> new ArrayList<>() ).add( i );
        }
        final Map< Long, ItemSummary > byId = new HashMap<>();
        for ( final Map.Entry< Long, List< Item > > e : byProject.entrySet() ) {
            views.summarise( e.getKey(), e.getValue() ).forEach( x -> byId.put( x.item().id(), x ) );
        }
        return items.stream().map( i -> byId.get( i.id() ) ).toList();
    }

    /** The items the agent holds, in the projects it can still see; empty for a person. */
    public List< Item > myClaims( final Actor a ) throws SQLException {
        if ( a.kind() != Actor.Kind.AGENT || a.agentId() == null ) {
            return List.of();
        }
        final Set< Long > ids = new LinkedHashSet<>();
        for ( final Project p : store.visibleProjects( a ) ) {
            ids.add( p.id() );
        }
        return store.claims().claimsHeldBy( a.agentId(), ids );
    }

    // ---- claim and release

    /**
     * Claims the item for the agent, or renews the agent's own claim. A refusal is a
     * {@link WorkConflictException} (reason {@code not_claimable}) whose message says why.
     */
    public ClaimResult claim( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        final Scope s = is.scope();
        s.require( Action.CLAIM, is.item() );
        s.requireWritable();
        final Item before = is.item();
        final Optional< Item > won = store.claims().claim( before, a, settings.claimExpiry() );
        if ( won.isEmpty() ) {
            listener.claim( ClaimOutcome.REFUSED );
            throw new WorkConflictException( refusal( a, before ), WorkConflictException.NOT_CLAIMABLE );
        }
        final boolean renewed = before.claimedByAgent() != null && before.claimedByAgent().equals( a.agentId() );
        listener.claim( renewed ? ClaimOutcome.RENEWED : ClaimOutcome.CLAIMED );
        return new ClaimResult( views.view( s, won.get() ), renewed );
    }

    /** Why a claim was refused, from the item as it is now; never names a login. */
    private String refusal( final Actor a, final Item before ) throws SQLException {
        final Item now = store.item( before.key() ).orElse( before );
        if ( now.claimedByAgent() != null && now.claimedByAgent().equals( a.agentId() ) ) {
            return CANNOT_CLAIM;
        }
        if ( now.claimedByAgent() != null && !now.claimedByAgent().equals( a.agentId() ) ) {
            final String holder = store.agent( now.claimedByAgent() ).map( Agent::name ).orElse( "another agent" );
            return "This item is claimed by " + holder + " until " + now.claimExpiresAt() + ".";
        }
        if ( now.status() != WorkStatus.READY && now.status() != WorkStatus.REVIEW ) {
            return "Only a ready or review item can be claimed; this one is " + now.status().wire() + ".";
        }
        if ( store.isBlocked( now.id() ) ) {
            return "This item is blocked by an open item.";
        }
        final List< String > served = store.agent( a.agentId() ).map( Agent::queues ).orElse( List.of() );
        if ( now.queue() == null || !served.contains( now.queue() ) ) {
            return "This item is not in a queue you serve.";
        }
        return CANNOT_CLAIM;
    }

    /**
     * Releases the claim on the item. Any member who may move it can: a person may release any claim, an agent only
     * its own (the policy refuses another holder). No claim is {@code not_claimed}.
     */
    public ItemView release( final Actor a, final String itemKey, final String note ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        final Scope s = is.scope();
        s.require( Action.MOVE, is.item() );
        s.requireWritable();
        final Optional< Item > released = is.item().claimedByAgent() == null ? Optional.empty()
            : store.claims().release( is.item(), a, note );
        if ( released.isEmpty() ) {
            throw new WorkConflictException( "This item is not claimed.", WorkConflictException.NOT_CLAIMED );
        }
        listener.claim( ClaimOutcome.RELEASED );
        return views.view( s, released.get() );
    }

    // ---- hand-off

    /**
     * Moves the item to a person or a queue, with a note, in one step: exactly one {@code handoff} event, and any
     * claim on the item ends (its own {@code released} event). The target must differ from where the item is: to
     * change only the status of an item (same person or queue), use {@code updateItem}, which records a status event.
     */
    public ItemView handoff( final Actor a, final String itemKey, final Handoff h ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        final Scope s = is.scope();
        s.require( Action.MOVE, is.item() );
        s.require( Action.EDIT_ITEM, is.item() );
        s.requireWritable();
        final String person = PatchValues.blankToNull( h.assignee() );
        final String queue = PatchValues.blankToNull( h.queue() );
        if ( ( person == null ) == ( queue == null ) ) {
            throw new WorkValidationException( "assignee", "Name a person or a queue to hand off to, not both." );
        }
        requireNote( h.note() );
        final Map< String, Object > changes = new LinkedHashMap<>();
        if ( h.status() != null ) {
            changes.put( "status", h.status().wire() );
        }
        if ( queue != null ) {
            changes.put( "queue", queue );
        } else {
            changes.put( "assignee", people.assigneeLogin( a, person ) );
        }
        final Item next = applier.apply( s.project(), is.item(), changes, h.note() );
        if ( Objects.equals( WorkEventDiff.assignmentText( is.item() ), WorkEventDiff.assignmentText( next ) ) ) {
            throw new WorkValidationException( queue != null ? "queue" : "assignee",
                "The item is already assigned there." );
        }
        final Item saved = store.handoff( WorkInputs.withVersion( is.item(), h.expectedVersion() ), next, a, h.note() );
        reportEnded( is.item(), saved );
        return views.view( s, saved );
    }

    /** A change that took the claim off an item ends it: tell the listener once, after the store succeeded. */
    void reportEnded( final Item before, final Item after ) {
        if ( before.claimedByAgent() != null && after.claimedByAgent() == null ) {
            listener.claim( ClaimOutcome.RELEASED );
        }
    }

    private static void requireNote( final String note ) {
        if ( note == null || note.isBlank() ) {
            throw new WorkValidationException( "note", "A hand-off needs a note saying what the next one should do." );
        }
        if ( note.length() > MAX_NOTE ) {
            throw new WorkValidationException( "note", "A note may be at most " + MAX_NOTE + " characters." );
        }
    }

    // ---- expiry

    /** Expires lapsed claims (a system task, no caller); returns how many were released. */
    public int sweepExpiredClaims() throws SQLException {
        final List< Item > expired = store.claims().sweepExpiredClaims( SWEEP_BATCH );
        expired.forEach( i -> listener.claim( ClaimOutcome.EXPIRED ) );
        return expired.size();
    }
}

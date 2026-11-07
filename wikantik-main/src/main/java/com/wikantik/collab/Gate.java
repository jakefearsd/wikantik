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
package com.wikantik.collab;

import com.wikantik.collab.model.DraftSummary;
import com.wikantik.collab.model.PublishResponse;

import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * The refusals every request meets before it touches the document: the participant must exist (409 {@code rejoin},
 * or 403 {@code evicted} with the eviction's reason as {@code detail} while its tombstone is kept) and belong to the
 * caller's author key (403 {@code not-your-participant}, A6); a push must also be within its rate (429
 * {@code rate-limited}, A8) and the session not frozen (409 {@code frozen}). Called under the session lock.
 */
final class Gate {

    /** A refusal: the status and reason to answer with; {@code detail} only for 403 {@code evicted}. */
    record Refusal( int status, String reason, String detail ) {
        Refusal( final int status, final String reason ) {
            this( status, reason, null );
        }
    }

    private Gate() {
    }

    /**
     * Null when {@code participantId} is a live participant of {@code authorKey}; otherwise 403 {@code evicted} (its
     * tombstone is the caller's), 403 {@code not-your-participant}, or 409 {@code rejoin}.
     */
    static Refusal identity( final Roster roster, final String participantId, final String authorKey,
                             final Instant now ) {
        final Participant p = roster.get( participantId );
        if( p != null ) {
            return p.authorKey.equals( authorKey ) ? null : new Refusal( 403, CollabSession.NOT_YOURS );
        }
        final Roster.Tombstone t = roster.tombstone( participantId, now );
        if( t == null ) {
            return new Refusal( 409, CollabSession.REJOIN );
        }
        return t.authorKey().equals( authorKey ) ? new Refusal( 403, CollabSession.EVICTED, t.reason() )
            : new Refusal( 403, CollabSession.NOT_YOURS );
    }

    /**
     * The draft summary for an identity-checked participant (marked seen), else the refusal; under the session lock.
     * {@code admit} (the caller's rate budget) is asked after the identity check and before the snapshot is copied.
     */
    static DraftSummary summary( final Roster roster, final String participantId, final String authorKey,
                                 final Instant now, final Draft d, final BooleanSupplier admit ) {
        final Refusal refused = identity( roster, participantId, authorKey, now );
        if( refused != null ) {
            return DraftSummary.refused( refused.status(), refused.reason(), refused.detail() );
        }
        if( !admit.getAsBoolean() ) {
            return DraftSummary.refused( 429, "rate-limited", null );
        }
        roster.get( participantId ).touch( now );
        return new DraftSummary( 200, null, null, d.record.basePageVersion(), d.head, d.hasUnpublished(),
            d.contributorNames(), d.lastEditAt, d.record.baseBody(), d.doc, MetaJson.normalise( d.record.baseMeta() ),
            d.meta );
    }

    /** Null when the push may proceed; marks the participant seen and takes one push token. */
    static Refusal push( final Roster roster, final String participantId, final String authorKey, final Instant now,
                         final int pushesPerSecond, final boolean frozen ) {
        final Refusal who = identity( roster, participantId, authorKey, now );
        if( who != null ) {
            return who;
        }
        final Participant p = roster.get( participantId );
        p.touch( now );
        if( !p.tryAcquirePush( now, pushesPerSecond ) ) {
            return new Refusal( 429, "rate-limited" );
        }
        return frozen ? new Refusal( 409, CollabSession.FROZEN ) : null;
    }

    /** A discard's refusal, or null: 409 {@code frozen} on a frozen draft, 423 while a publish is pending. */
    static Refusal discard( final boolean frozen, final boolean publishPending ) {
        if( frozen ) {
            return new Refusal( 409, CollabSession.FROZEN );
        }
        return publishPending ? new Refusal( 423, CollabSession.IN_PROGRESS ) : null;
    }

    /** Records that the participant holds {@code version} when it is a real version (0..head). */
    static void confirm( final Participant p, final int version, final int head ) {
        if( version >= 0 && version <= head ) {
            p.confirm( version );
        }
    }

    /**
     * A publish's answer before the save (section 7.3), or null to save: 200 {@code unchanged} with nothing to publish
     * (E21), 400 {@code empty} for an empty draft (E1), 413 {@code oversize} for a draft over the page limit (7.4).
     */
    static PublishResponse publishable( final boolean unpublished, final boolean docEmpty, final boolean oversize,
                                        final int base ) {
        if( !unpublished ) {
            return new PublishResponse( 200, base, null, true );
        }
        if( docEmpty ) {
            return new PublishResponse( 400, base, "empty", false );
        }
        return oversize ? new PublishResponse( 413, base, "oversize", false ) : null;
    }
}

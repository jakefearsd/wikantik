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

import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pull answers (section 7.5 and 6.2 as amended): the text updates after a cursor, the presence map, the meta ops and the
 * events, and the waking of parked pulls when something they wait for moves. Guarded by the session lock; the returned
 * runnables complete waiters and are run by the session after it released the lock.
 */
final class Answers {

    private static final Logger LOG = LogManager.getLogger( Answers.class );
    /** The cursor of an answer that carries the presence map and nothing else (a removed or displaced pull). */
    static final PullCursor PRESENCE_ONLY = new PullCursor( 0, Long.MAX_VALUE, -1, Long.MAX_VALUE );

    private final Draft d;
    private final Roster roster;
    private final Waiters waiters;

    Answers( final Draft d, final Roster roster, final Waiters waiters ) {
        this.d = d;
        this.roster = roster;
        this.waiters = waiters;
    }

    /**
     * A 200 answer: the updates, the presence map when {@code presenceSince} is older than the sequence, the meta ops and
     * events after their cursors. 410 when the meta ops after the cursor are no longer held.
     */
    PullResponse answer( final List< StoredUpdate > updates, final String participantId, final PullCursor cursor ) {
        final List< MetaOp > ops = d.feed.metaOpsAfter( Math.min( cursor.metaSince(), d.metaSeq ), d.metaSeq,
            roster::nameOf );
        if( ops == null ) {
            return PullResponse.refused( 410, d.head, "resync" );
        }
        final List< Update > out = new ArrayList<>( updates.size() );
        for( final StoredUpdate u : updates ) {
            out.add( new Update( u.clientId(), u.changes() ) );
        }
        final long eventSeq = d.feed.eventSeq();
        return new PullResponse( 200, out, d.head, null,
            cursor.presenceSince() < d.presenceSeq ? roster.presenceExcept( participantId ) : Map.of(), d.presenceSeq, ops,
            d.metaSeq, cursor.eventsSince() < eventSeq ? d.feed.eventsAfter( cursor.eventsSince() ) : List.of(), eventSeq );
    }

    /** Catch-up for a pull behind the head: the ring when it reaches back far enough, else the store; 410 when truncated. */
    PullResponse catchUp( final PullCursor cursor, final String participantId ) {
        // heavy work under the lock: a store read for a client below the ring; rare by design (A10) and bounded by the
        // retained log, and it keeps the answer consistent with the head it reports
        final int since = cursor.since();
        if( d.ring.covers( since ) ) {
            return answer( d.ring.after( since ), participantId, cursor );
        }
        final List< StoredUpdate > log;
        try {
            log = SessionLoader.contiguousSince( d.store, d.id, since, d.head );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): catch-up read from {} failed: {}", d.id, d.pageName(), since, e.getMessage() );
            return PullResponse.refused( 503, d.head, CollabSession.STORE );
        }
        return log == null ? PullResponse.refused( 410, d.head, "resync" ) : answer( log, participantId, cursor );
    }

    /** True when a pull at head has something to answer at once: presence (as of {@code seen}), meta ops or events. */
    boolean ahead( final PullCursor cursor, final long seen ) {
        return cursor.presenceSince() < seen || cursor.metaSince() < d.metaSeq || cursor.eventsSince() < d.feed.eventSeq();
    }

    /** Parks {@code waiter}; the participant's earlier parked pull, if any, is answered (presence only). */
    void park( final String participantId, final PullCursor cursor, final Instant deadline, final PullWaiter waiter,
               final List< Runnable > completions ) {
        final Runnable displaced = waiters.park( participantId, cursor, deadline, waiter,
            answer( List.of(), participantId, PRESENCE_ONLY ) );
        if( displaced != null ) {
            completions.add( displaced );
        }
    }

    /** Removes a leaving participant's parked pull, answered with the presence map; null when it had none. */
    Runnable removeParked( final String participantId ) {
        return waiters.remove( participantId, answer( List.of(), participantId, PRESENCE_ONLY ) );
    }

    /** A join, leave or selection change: the sequence moves and every other parked pull behind it is answered. */
    void presenceChanged( final String changedBy, final List< Runnable > completions ) {
        d.presenceSeq++;
        completions.addAll( waiters.drainWhere( ( pid, c ) -> c.presenceSince() < d.presenceSeq && !pid.equals( changedBy ),
            ( pid, c ) -> answer( List.of(), pid, c ) ) );
    }

    /**
     * Answers every parked pull after {@code batch} was applied, from memory only: the ring, or the batch itself when it
     * was longer than the ring (amendment: no store read after a commit). Every waiter parked at the previous head.
     * With an empty batch, only the pulls behind on meta ops or events are answered.
     */
    void wakeAll( final List< StoredUpdate > batch, final List< Runnable > completions ) {
        if( batch.isEmpty() ) {
            completions.addAll( waiters.drainWhere(
                ( pid, c ) -> c.metaSince() < d.metaSeq || c.eventsSince() < d.feed.eventSeq(),
                ( pid, c ) -> answer( List.of(), pid, c ) ) );
            return;
        }
        completions.addAll( waiters.drainAll( ( participantId, c ) -> {
            final int since = c.since();
            if( d.ring.covers( since ) ) {
                return answer( d.ring.after( since ), participantId, c );
            }
            if( batch.get( 0 ).version() <= since + 1 ) {
                return answer( batch.stream().filter( u -> u.version() > since ).toList(), participantId, c );
            }
            LOG.warn( "collab session {}: parked pull of {} from {} is not in memory; answering resync", d.id, participantId,
                since );
            return PullResponse.refused( 410, d.head, "resync" );
        } ) );
    }

    /** The parked pulls whose long-poll timeout passed, removed; each runnable answers one of them. */
    List< Runnable > expire( final Instant now ) {
        return waiters.expire( now, ( participantId, c ) -> answer( List.of(), participantId, c ) );
    }
}

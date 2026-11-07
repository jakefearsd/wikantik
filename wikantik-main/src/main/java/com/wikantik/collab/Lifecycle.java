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

import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.SessionRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session lifecycle (Phase 2, docs/MultiEdit.MD A1, A5, A13 and section 7.4's delete and rename), apart from
 * {@link CollabSession} so its logic stays small: participant events, departures (left, timed out, evicted with a
 * reason and a tombstone, dropped when the session leaves memory, replaced by its own rejoin), rename and freeze.
 * Every method runs under the session lock; rename and freeze also under the base lock (the caller takes both). The
 * runnables added to {@code completions} answer parked pulls after the lock is released.
 *
 * <p>Events ({@link SessionFeed}): {@code participant-joined {participant}}, {@code participant-left {participantId,
 * reason}} (reason {@code left}, {@code timeout} or {@code evicted}), {@code evicted {participantId, reason}} before its
 * {@code participant-left}, {@code page-renamed {to}} and {@code page-deleted {}}. Each wakes every parked pull.</p>
 *
 * <p>A removed participant's parked pull is answered at once: a leaver's with the presence map, an evicted one's with
 * 403 {@code evicted} and the reason, a timed-out, dropped or replaced one's with 409 {@code rejoin}.</p>
 */
final class Lifecycle {

    private static final Logger LOG = LogManager.getLogger( Lifecycle.class );
    static final String LEFT = "left";
    static final String TIMEOUT = "timeout";
    static final String PARTICIPANT_LEFT = "participant-left";
    private static final String PARTICIPANT_ID = "participantId";
    private static final String REASON = "reason";

    private final Draft d;
    private final Roster roster;
    private final Answers answers;

    Lifecycle( final Draft d, final Roster roster, final Answers answers ) {
        this.d = d;
        this.roster = roster;
        this.answers = answers;
    }

    // ---- participants ------------------------------------------------------------------------------------------

    /** A participant joined: {@code participant-joined}, presence moves, every parked pull is answered. */
    void joined( final Participant p, final List< Runnable > completions ) {
        final Map< String, Object > who = new LinkedHashMap<>();
        who.put( PARTICIPANT_ID, p.id );
        who.put( "displayName", p.displayName );
        who.put( "color", p.color );
        d.feed.emit( "participant-joined", Map.of( "participant", who ) );
        moved( p.id, completions );
    }

    /**
     * The join's own stale entry (same author, holding the prior clientID, A13) leaves as timed out, so the rejoin gets
     * that clientID back and takes the entry's place instead of a second seat.
     */
    void replaceStale( final JoinRequest r, final Instant now, final List< Runnable > completions ) {
        final Participant stale = roster.staleEntryOf( r );
        if( stale != null ) {
            LOG.debug( "collab session {}: participant {} replaced by its own rejoin", d.id, stale.id );
            depart( stale.id, TIMEOUT, rejoin(), now, completions );
        }
    }

    /** The participant's own leave: {@code participant-left (left)}; its parked pull gets the presence map. */
    void left( final String participantId, final Instant now, final List< Runnable > completions ) {
        if( roster.remove( participantId, now ) != null ) {
            parkedAnswered( answers.removeParked( participantId ), completions );
            emitLeft( participantId, LEFT );
            moved( participantId, completions );
        }
    }

    /**
     * A server eviction for {@code reason}: the tombstone is laid (later requests answer 403 {@code evicted} with the
     * reason), {@code evicted} then {@code participant-left (evicted)} are emitted, and its parked pull is answered 403
     * {@code evicted} at once. Unknown ids are ignored.
     */
    void evict( final String participantId, final String reason, final Instant now,
                final List< Runnable > completions ) {
        if( roster.evict( participantId, reason, now ) == null ) {
            return;
        }
        parkedAnswered( answers.removeParked( participantId,
            PullResponse.refused( 403, d.head, CollabSession.EVICTED, reason ) ), completions );
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( PARTICIPANT_ID, participantId );
        f.put( REASON, reason );
        d.feed.emit( CollabSession.EVICTED, f );
        emitLeft( participantId, CollabSession.EVICTED );
        moved( participantId, completions );
    }

    /** The participants not seen since {@code horizon} leave as timed out (E13); their next request rejoins. */
    int expire( final Instant horizon, final Instant now, final List< Runnable > completions ) {
        final List< String > gone = new ArrayList<>();
        for( final Participant p : roster.all() ) {
            if( p.lastSeen().isBefore( horizon ) ) {
                gone.add( p.id );
            }
        }
        gone.forEach( id -> timeOut( id, now, completions ) );
        return gone.size();
    }

    /** One participant leaves as timed out: no tombstone, its next request rejoins. */
    void timeOut( final String participantId, final Instant now, final List< Runnable > completions ) {
        depart( participantId, TIMEOUT, rejoin(), now, completions );
    }

    /** Everyone leaves, without a tombstone: the session leaves memory and its participants rejoin a reload. */
    void dropAll( final Instant now, final List< Runnable > completions ) {
        for( final Participant p : List.copyOf( roster.all() ) ) {
            depart( p.id, TIMEOUT, rejoin(), now, completions );
        }
    }

    private void depart( final String participantId, final String why, final PullResponse parkedAnswer,
                         final Instant now, final List< Runnable > completions ) {
        if( roster.remove( participantId, now ) != null ) {
            parkedAnswered( answers.removeParked( participantId, parkedAnswer ), completions );
            emitLeft( participantId, why );
            moved( participantId, completions );
        }
    }

    private PullResponse rejoin() {
        return PullResponse.refused( 409, d.head, CollabSession.REJOIN );
    }

    private void emitLeft( final String participantId, final String why ) {
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( PARTICIPANT_ID, participantId );
        f.put( REASON, why );
        d.feed.emit( PARTICIPANT_LEFT, f );
    }

    private static void parkedAnswered( final Runnable parked, final List< Runnable > completions ) {
        if( parked != null ) {
            completions.add( parked );
        }
    }

    /** Presence moved and an event was emitted: every parked pull behind either is answered, once. */
    private void moved( final String changedBy, final List< Runnable > completions ) {
        answers.presenceChanged( changedBy, completions );
        answers.wakeAll( List.of(), completions );
    }

    // ---- rename and freeze -------------------------------------------------------------------------------------

    /**
     * The page was renamed to {@code newPageName} (A1): the store first, so a refusal (the new name has a draft) throws
     * {@link IllegalStateException} with nothing changed; then memory, the old name joins the former names, and a
     * {@code page-renamed} event wakes every parked pull. Renaming to the current name does nothing.
     */
    void rekey( final String newPageName, final List< Runnable > completions ) {
        final String old = d.pageName();
        if( old.equals( newPageName ) ) {
            return;
        }
        final SessionRecord next = d.record.withPageName( newPageName );
        d.store.updateSession( next );
        d.record = next;
        d.formerNames.add( old );
        d.formerNames.remove( newPageName );
        d.feed.emit( "page-renamed", Map.of( "to", newPageName ) );
        answers.wakeAll( List.of(), completions );
    }

    /**
     * The page was deleted (section 7.4): the status becomes {@code frozen} in the store, then in memory, and a
     * {@code page-deleted} event wakes every parked pull. A frozen session does nothing. A store failure propagates with
     * nothing changed.
     */
    void freeze( final List< Runnable > completions ) {
        if( d.frozen() ) {
            return;
        }
        final SessionRecord next = d.record.withStatus( SessionRecord.FROZEN );
        d.store.updateSession( next );
        d.record = next;
        announceDeleted( completions );
    }

    /** Emits {@code page-deleted} (also when a frozen draft is reloaded, so its joiners are told) and wakes pulls. */
    void announceDeleted( final List< Runnable > completions ) {
        d.deletedEventSeq = d.feed.emit( "page-deleted", Map.of() ).seq();
        answers.wakeAll( List.of(), completions );
    }

    /** The current name or a name the page held before a rename. */
    boolean answersTo( final String pageName ) {
        return d.pageName().equals( pageName ) || d.formerNames.contains( pageName );
    }

    /** A join's event cursor: the feed's, or just before the latest {@code page-deleted} on a frozen draft. */
    long joinEventSeq() {
        return d.frozen() && d.deletedEventSeq > 0 ? d.deletedEventSeq - 1 : d.feed.eventSeq();
    }
}

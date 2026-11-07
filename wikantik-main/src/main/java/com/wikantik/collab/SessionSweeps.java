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

import com.wikantik.collab.CollabSession.Hooks;
import com.wikantik.collab.CollabSession.Hooks.Member;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The registry's server-side participant removals: the presence timeout (E13, {@link Hooks#expirePresence}, no
 * tombstone), lost {@code edit} (A5/E30), a signed-out wiki session (A13) and a deleted user (evictions with a reason
 * and a tombstone,
 * {@link CollabSession#evict(String, String)}), and a session leaving memory ({@link Hooks#dropAll}, no
 * tombstone). Each answers the participant's parked pull.
 */
final class SessionSweeps {

    private static final Logger LOG = LogManager.getLogger( SessionSweeps.class );

    private SessionSweeps() {
    }

    /**
     * True (logged) while the session's publish is in its save: a wedged-session reload then would give the page two
     * writers, so it is deferred.
     */
    static boolean deferredByPublish( final CollabSession s ) {
        if( !Hooks.publishing( s ) ) {
            return false;
        }
        LOG.warn( "collab session {} ({}): store failures, but a publish is in its save; reload deferred", s.id(),
            s.pageName() );
        return true;
    }

    /** Participants not seen since {@code horizon}. */
    static void expirePresence( final CollabSession s, final Instant horizon ) {
        final int n = Hooks.expirePresence( s, horizon );
        if( n > 0 ) {
            LOG.info( "collab session {}: {} participant(s) timed out", s.id(), n );
        }
    }

    /**
     * Participants whose wiki session no longer has {@code edit} on the page (A5). A check that throws fails closed:
     * that participant is evicted (as {@code CollabSession}'s post-publish re-check does) and the sweep goes on.
     */
    static void recheckEdit( final CollabSession s, final CollabPermissions permissions ) {
        final String pageName = s.pageName();
        evictWhere( s, m -> !mayEdit( s, m, permissions, pageName ), CollabSession.LOST_EDIT );
    }

    private static boolean mayEdit( final CollabSession s, final Member m, final CollabPermissions permissions,
                                    final String pageName ) {
        try {
            return permissions.canEdit( m.wikiSession(), pageName );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the edit check of {} on {} failed; evicting: {}", s.id(), m.participantId(),
                pageName, e.toString() );
            return false;
        }
    }

    /** Every participant of {@code authorKey}; returns how many. */
    static int evictAuthor( final CollabSession s, final String authorKey ) {
        return evictWhere( s, m -> m.authorKey().equals( authorKey ), CollabSession.ACCOUNT_DELETED );
    }

    /** Every participant holding {@code wikiSession} (it expired, A13), signed in at join or not; returns how many. */
    static int evictSignedOut( final CollabSession s, final Object wikiSession ) {
        return wikiSession == null ? 0
            : evictWhere( s, m -> wikiSession.equals( m.wikiSession() ), CollabSession.SIGNED_OUT );
    }

    /**
     * The participants a logout signed out (a logout names no session, A13; ruling Q13); returns how many. Only one
     * whose wiki session was signed in when it joined is considered: it goes when that session no longer reads as signed
     * in, or now resolves to another author key (alice logged out, bob logged in on the same session). Someone who
     * joined without signing in is never evicted by a logout, even after signing in later. A check that throws keeps the
     * participant (logged): signing out is not a revocation, and the edit re-check still applies.
     */
    static int evictSignedOutAll( final CollabSession s, final CollabPermissions permissions ) {
        return evictWhere( s, m -> m.signedInAtJoin() && signedOutNow( s, m, permissions ), CollabSession.SIGNED_OUT );
    }

    private static boolean signedOutNow( final CollabSession s, final Member m, final CollabPermissions permissions ) {
        try {
            if( !permissions.signedIn( m.wikiSession() ) ) {
                return true;
            }
            final String now = permissions.authorKeyOf( m.wikiSession() );
            return now != null && !now.equals( m.authorKey() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the sign-in check of {} failed; kept: {}", s.id(), m.participantId(),
                e.toString() );
            return false;
        }
    }

    /**
     * Sums {@code f} over the opened, unclosed slots' sessions (a snapshot of {@code slots}), each session on its own:
     * one that throws is logged and skipped, so the others still run and nothing reaches the caller (user deletion,
     * logout, a rename).
     */
    static int forEachLive( final Collection< SessionSlot > slots, final ToIntFunction< CollabSession > f ) {
        int n = 0;
        for( final SessionSlot slot : List.copyOf( slots ) ) {
            final CollabSession s = slot.session();
            if( s != null && !slot.closed() ) {
                try {
                    n += f.applyAsInt( s );
                } catch( final RuntimeException e ) {
                    LOG.warn( "collab session {}: a registry-wide sweep failed for it; the other sessions go on: {}",
                        s.id(), e.toString() );
                }
            }
        }
        return n;
    }

    /** {@link CollabSession#settle}, a failure logged (the tick retries what is owed); always 0. */
    static int settleQuietly( final CollabSession s ) {
        try {
            s.settle();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: settling after a rename failed; the tick retries: {}", s.id(), e.toString() );
        }
        return 0;
    }

    /** Everyone: the session leaves memory. */
    static void evictAll( final CollabSession s ) {
        Hooks.dropAll( s );
    }

    /** Each participant on its own: one whose check or eviction fails is logged and the rest go on. */
    private static int evictWhere( final CollabSession s, final Predicate< Member > which, final String reason ) {
        int n = 0;
        for( final Member m : Hooks.members( s ) ) {
            try {
                if( which.test( m ) ) {
                    LOG.info( "collab session {}: participant {} evicted ({})", s.id(), m.participantId(), reason );
                    s.evict( m.participantId(), reason );
                    n++;
                }
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {}: evicting participant {} ({}) failed; the others go on: {}", s.id(),
                    m.participantId(), reason, e.toString() );
            }
        }
        return n;
    }
}

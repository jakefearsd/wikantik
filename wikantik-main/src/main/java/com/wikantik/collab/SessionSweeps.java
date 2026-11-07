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
import java.util.function.Predicate;

/**
 * The registry's server-side participant removals: the presence timeout (E13, {@link Hooks#expirePresence}, no
 * tombstone), lost {@code edit} (A5/E30) and a deleted user (evictions with a reason and a tombstone,
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

    /** Every participant of {@code authorKey}. */
    static void evictAuthor( final CollabSession s, final String authorKey ) {
        evictWhere( s, m -> m.authorKey().equals( authorKey ), CollabSession.ACCOUNT_DELETED );
    }

    /** Everyone: the session leaves memory. */
    static void evictAll( final CollabSession s ) {
        Hooks.dropAll( s );
    }

    private static void evictWhere( final CollabSession s, final Predicate< Member > which, final String reason ) {
        for( final Member m : Hooks.members( s ) ) {
            if( which.test( m ) ) {
                LOG.info( "collab session {}: participant {} evicted ({})", s.id(), m.participantId(), reason );
                s.evict( m.participantId(), reason );
            }
        }
    }
}

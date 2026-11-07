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
 * The registry's server-side participant evictions (presence timeout E13, lost {@code edit} A5/E30, a deleted user, a
 * session leaving memory): each goes through {@link CollabSession#evict}, which answers the participant's parked pull.
 */
final class SessionSweeps {

    private static final Logger LOG = LogManager.getLogger( SessionSweeps.class );

    private SessionSweeps() {
    }

    /** Participants not seen since {@code horizon}. */
    static void expirePresence( final CollabSession s, final Instant horizon ) {
        evictWhere( s, m -> m.lastSeen().isBefore( horizon ), "timed out" );
    }

    /**
     * Participants whose wiki session no longer has {@code edit} on the page (A5). A check that throws fails closed:
     * that participant is evicted (as {@code CollabSession}'s post-publish re-check does) and the sweep goes on.
     */
    static void recheckEdit( final CollabSession s, final CollabPermissions permissions ) {
        final String pageName = s.pageName();
        evictWhere( s, m -> !mayEdit( s, m, permissions, pageName ), "lost edit" );
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
        evictWhere( s, m -> m.authorKey().equals( authorKey ), "deleted user" );
    }

    /** Everyone: the session leaves memory. */
    static void evictAll( final CollabSession s ) {
        evictWhere( s, m -> true, "session left memory" );
    }

    private static void evictWhere( final CollabSession s, final Predicate< Member > which, final String why ) {
        for( final Member m : Hooks.members( s ) ) {
            if( which.test( m ) ) {
                LOG.info( "collab session {}: participant {} evicted ({})", s.id(), m.participantId(), why );
                s.evict( m.participantId() );
            }
        }
    }
}

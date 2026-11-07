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

import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PageState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

/**
 * The refusals {@link CollabSessionRegistry#join} makes before it touches a slot: 403 {@code forbidden} for a caller
 * without {@code edit}, or without {@code createPages} on a page that does not exist yet, then 409
 * {@code derived-page} (section 9). And, once the session is open, ruling F1's guard of a draft whose page is missing
 * ({@link #refuseMissingPagesDraft}).
 */
final class JoinRefusals {

    private static final Logger LOG = LogManager.getLogger( JoinRefusals.class );
    private static final String DERIVED_FROM = "derived_from";

    private JoinRefusals() {
    }

    /**
     * Null to go on, else the refusal. {@code latest} is null when the page does not exist. 403 comes first: a
     * non-editor learns nothing more.
     */
    static Refused refuse( final CollabPermissions permissions, final String pageName, final JoinRequest r,
                           final PageState latest ) {
        if( !mayJoin( permissions, r, pageName, latest == null ) ) {
            return new Refused( 403, CollabSessionRegistry.FORBIDDEN );
        }
        if( latest != null && latest.meta().containsKey( DERIVED_FROM ) ) {
            return new Refused( 409, CollabSessionRegistry.DERIVED );
        }
        return null;
    }

    /** {@code edit}, plus {@code createPages} for a new page; a check that throws fails closed (logged). */
    private static boolean mayJoin( final CollabPermissions permissions, final JoinRequest r, final String pageName,
                                    final boolean create ) {
        final Object who = r.wikiSession();
        try {
            return permissions.canEdit( who, pageName ) && ( !create || permissions.canCreate( who, pageName ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the permission check for {} joining {} failed; refusing: {}", r.authorKey(), pageName,
                e.toString() );
            return false;
        }
    }

    /**
     * The refusals made once the session is open, before the join: ruling F1's guard when the page is missing
     * ({@link #refuseMissingPagesDraft}), then the seats. One author holds at most
     * {@link CollabSessionRegistry#MAX_SEATS_PER_AUTHOR} (its seat last seen before {@code idleBefore} is reclaimed,
     * else 429 {@code session-full} for that author alone), and the session at most {@code maxParticipants} (a rejoin
     * replacing its own stale entry, A13, takes no new seat). Null to go on.
     */
    static Refused refuseInSession( final CollabPermissions permissions, final CollabSession s, final JoinRequest r,
                                    final boolean pageMissing, final Instant idleBefore, final int maxParticipants ) {
        final Refused guarded = pageMissing ? refuseMissingPagesDraft( permissions, s, r ) : null;
        if( guarded != null ) {
            return guarded;
        }
        if( !CollabSession.Hooks.seatFor( s, r, CollabSessionRegistry.MAX_SEATS_PER_AUTHOR, idleBefore ) ) {
            LOG.info( "collab: join of {} by {} refused: that author already holds {} active seats", s.pageName(),
                r.authorKey(), CollabSessionRegistry.MAX_SEATS_PER_AUTHOR );
            return new Refused( 429, CollabSessionRegistry.FULL );
        }
        final int seats = CollabSession.Hooks.members( s ).size() - ( CollabSession.Hooks.replacesStale( s, r ) ? 1 : 0 );
        return seats >= maxParticipants ? new Refused( 429, CollabSessionRegistry.FULL ) : null;
    }

    /**
     * The join itself: {@link Joined}, or 413 {@code oversize} for a seed over the page size limit, 400
     * {@code rejected} for another malformed seed, 503 {@code store} for a store failure (each logged).
     */
    static CollabSessionRegistry.JoinOutcome join( final CollabSession s, final JoinRequest r, final String pageName ) {
        try {
            return new Joined( s, s.join( r ) );
        } catch( final Draft.SeedTooLarge e ) {
            LOG.info( "collab: join of {} by {} refused: {}", pageName, r.authorKey(), e.getMessage() );
            return new Refused( 413, "oversize" );
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "collab: join of {} by {} refused: {}", pageName, r.authorKey(), e.toString() );
            return new Refused( 400, CollabSession.REJECTED );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: join of {} by {} failed in the store: {}", pageName, r.authorKey(), e.toString() );
            return new Refused( 503, CollabSession.STORE );
        }
    }

    /**
     * Ruling F1: the draft of a missing page (frozen, or based on a page that no longer exists) is joined only by its
     * existing participants, by administrators, and by callers whom the draft's base text's access rules would let
     * {@code view} and {@code edit} the page. Null to go on, else 403 {@code forbidden}. A check that throws refuses
     * (logged): the deleted page's rules must keep protecting its unpublished text.
     */
    static Refused refuseMissingPagesDraft( final CollabPermissions permissions, final CollabSession s,
                                            final JoinRequest r ) {
        final String base = CollabSession.Hooks.guardingBase( s, r.authorKey() );
        if( base == null ) {
            return null;
        }
        final Object who = r.wikiSession();
        try {
            if( permissions.isAdmin( who ) || permissions.mayAccessAsIf( who, s.pageName(), base ) ) {
                return null;
            }
            LOG.info( "collab: join of {} by {} refused: its page is gone and its last access rules do not admit them",
                s.pageName(), r.authorKey() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the access rules of the deleted page {} could not be evaluated for {}; refusing: {}",
                s.pageName(), r.authorKey(), e.toString() );
        }
        return new Refused( 403, CollabSessionRegistry.FORBIDDEN );
    }
}

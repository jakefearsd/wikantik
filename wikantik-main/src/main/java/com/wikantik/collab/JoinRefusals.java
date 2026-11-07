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
 * {@code derived-page} (section 9). And, once the session is open, the guard of rulings F1/F2 ({@link #refuseGuarded}),
 * the seats, and the join's own refusals.
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
     * The refusals made once the session is open, before the join: the guard of rulings F1 and F2
     * ({@link #refuseGuarded}), then the seats. One author holds at most
     * {@link CollabSessionRegistry#MAX_SEATS_PER_AUTHOR} (its seat last seen before {@code idleBefore} is reclaimed,
     * else 429 {@code session-full} for that author alone), and the session at most {@code maxParticipants} (a rejoin
     * replacing its own stale entry, A13, takes no new seat). Null to go on.
     */
    static Refused refuseInSession( final CollabPermissions permissions, final CollabSession s, final JoinRequest r,
                                    final boolean pageMissing, final Instant idleBefore, final int maxParticipants ) {
        final Refused guarded = refuseGuarded( permissions, s, r, pageMissing );
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
     * 503 {@code store} for a join of a page a rename is still moving a draft into (opening a session there now would
     * leave the renamed draft no home); a WARN at most once a minute per author ({@code warns}), the rest at DEBUG.
     */
    static Refused renamePending( final String pageName, final JoinRequest r, final WarnThrottle warns ) {
        final long quiet = warns.admit( "rename " + r.authorKey() );
        if( quiet == WarnThrottle.QUIET ) {
            LOG.debug( "collab: join of {} by {} refused: a rename into it is pending", pageName, r.authorKey() );
        } else {
            LOG.warn( "collab: join of {} by {} refused: a rename into it is pending{}", pageName, r.authorKey(),
                WarnThrottle.quietSuffix( quiet ) );
        }
        return new Refused( 503, CollabSession.STORE );
    }

    /**
     * The join itself: {@link Joined}, or 413 {@code oversize} for a seed over the page size limit, 400
     * {@code rejected} for another malformed seed (a WARN at most once a minute per author, {@code warns}), 503
     * {@code store} for a store failure (each logged).
     */
    static CollabSessionRegistry.JoinOutcome join( final CollabSession s, final JoinRequest r, final String pageName,
                                                   final WarnThrottle warns ) {
        try {
            return new Joined( s, s.join( r ) );
        } catch( final Draft.SeedTooLarge e ) {
            LOG.info( "collab: join of {} by {} refused: {}", pageName, r.authorKey(), e.getMessage() );
            return new Refused( 413, "oversize" );
        } catch( final IllegalArgumentException e ) {
            final long quiet = warns.admit( "seed " + r.authorKey() );
            if( quiet == WarnThrottle.QUIET ) {
                LOG.debug( "collab: join of {} by {} refused: {}", pageName, r.authorKey(), e.toString() );
            } else {
                LOG.warn( "collab: join of {} by {} refused: {}{}", pageName, r.authorKey(), e.toString(),
                    WarnThrottle.quietSuffix( quiet ) );
            }
            return new Refused( 400, CollabSession.REJECTED );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: join of {} by {} failed in the store: {}", pageName, r.authorKey(), e.toString() );
            return new Refused( 503, CollabSession.STORE );
        }
    }

    /**
     * Rulings F1 and F2: a guarded draft (once frozen, until it is published again; or based on a page that is now
     * missing) is joined only by its existing participants, by administrators, and by callers whom the guarding text's
     * access rules (the deleted page's full text, frontmatter included) would let {@code view} and {@code edit} the page.
     * A recreated page does not lift the guard, so its recreator gets no access to the old unpublished text. Null to go
     * on, else 403 {@code forbidden}. A check that throws refuses (logged): the deleted page's rules must keep protecting
     * its unpublished text.
     */
    static Refused refuseGuarded( final CollabPermissions permissions, final CollabSession s, final JoinRequest r,
                                  final boolean pageMissing ) {
        final String base = CollabSession.Hooks.guardingBase( s, r.authorKey(), pageMissing );
        if( base == null ) {
            return null;
        }
        final Object who = r.wikiSession();
        try {
            if( permissions.isAdmin( who ) || permissions.mayAccessAsIf( who, s.pageName(), base ) ) {
                return null;
            }
            LOG.info( "collab: join of {} by {} refused: the draft is guarded and its page's last rules do not admit them",
                s.pageName(), r.authorKey() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the access rules of the deleted page {} could not be evaluated for {}; refusing: {}",
                s.pageName(), r.authorKey(), e.toString() );
        }
        return new Refused( 403, CollabSessionRegistry.FORBIDDEN );
    }
}

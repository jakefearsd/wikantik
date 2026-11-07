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

import com.wikantik.collab.model.DraftListing;
import com.wikantik.collab.model.PurgeOutcome;
import com.wikantik.collab.model.SessionRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * {@link CollabSessionRegistry}'s administration of stored drafts (section 7.7, section 11): the drafts list, the
 * administrator's purge, and draft retention.
 *
 * <p><b>Removal</b>, shared by purge and retention, never publishes. A live session is removed under its slot lock:
 * every participant is evicted with {@link CollabSessionRegistry#PURGED} (their parked pulls answer 403
 * {@code evicted}), the slot is closed and unmapped, then the draft is deleted. A stored draft is removed under its
 * page's slot lock too, so a join cannot load it meanwhile (a join waiting on that slot retries from a fresh slot). A
 * session that is publishing, or holds a pending-publish marker, is refused (423 {@code publish-in-progress}), checked
 * again after the evictions for a publish that started just before them. A store failure answers 503 {@code store} and
 * keeps the draft; a session already out of memory reloads on the next join.</p>
 *
 * <p><b>Retention</b> ({@code wikantik.collab.draft.retentionDays}; 0 keeps drafts for ever): at most every
 * {@link #RETENTION_EVERY}, every stored draft whose last edit (else its creation) is older than the retention is
 * removed, unless it has live participants or a publish pending. A live session's own last-edit time is used, not the
 * stored one (written at snapshot cadence only). Each expiry is logged at INFO.</p>
 */
final class DraftAdmin {

    private static final Logger LOG = LogManager.getLogger( DraftAdmin.class );
    /** How often the retention sweep runs at most. */
    static final Duration RETENTION_EVERY = Duration.ofHours( 1 );
    static final String UNKNOWN = "unknown";
    /** Internal only: retention found a reason to keep the draft. */
    private static final String SPARED = "spared";
    private static final PurgeOutcome NOT_FOUND = new PurgeOutcome( 404, UNKNOWN, null, false );

    /** Whether a removal may go ahead: null to go ahead, else the answer. */
    private interface Veto {
        PurgeOutcome live( CollabSession s );

        PurgeOutcome stored( SessionRecord rec );
    }

    /** The purge refuses only a publish in progress. */
    private static final Veto PURGE = new Veto() {
        @Override
        public PurgeOutcome live( final CollabSession s ) {
            return s.view().publishPending() ? inProgress( s.pageName(), true ) : null;
        }

        @Override
        public PurgeOutcome stored( final SessionRecord rec ) {
            return rec.pendingPublishVersion() != null ? inProgress( rec.pageName(), false ) : null;
        }
    };

    private final CollabStore store;
    private final PageSlots slots;
    private final Map< UUID, SessionSlot > byId;
    private final DraftIndex index;
    /** The registry's unmapping of a closed slot. */
    private final BiConsumer< SessionSlot, CollabSession > unmap;
    /** When retention last ran. Tick thread only. */
    private Instant lastRetention;

    DraftAdmin( final CollabStore store, final PageSlots slots, final Map< UUID, SessionSlot > byId,
                final DraftIndex index, final BiConsumer< SessionSlot, CollabSession > unmap ) {
        this.store = store;
        this.slots = slots;
        this.byId = byId;
        this.index = index;
        this.unmap = unmap;
    }

    private static PurgeOutcome inProgress( final String pageName, final boolean live ) {
        return new PurgeOutcome( 423, CollabSession.IN_PROGRESS, pageName, live );
    }

    // ---- the drafts list ---------------------------------------------------------------------------------------

    /** Every stored draft, oldest edit first, overlaid with its live session's state. Store failures propagate. */
    List< DraftListing > drafts() {
        return store.sessions().stream().map( this::listing ).toList();
    }

    private DraftListing listing( final SessionRecord rec ) {
        final SessionSlot slot = byId.get( rec.id() );
        final CollabSession s = slot == null || slot.closed() ? null : slot.session();
        if( s == null ) {
            return new DraftListing( rec.id(), rec.pageName(), rec.status(), rec.createdBy(), rec.createdAt(),
                rec.lastEditAt(), false, 0, rec.pendingPublishVersion() != null );
        }
        final CollabSession.View v = s.view();
        return new DraftListing( rec.id(), v.pageName(), s.frozen() ? SessionRecord.FROZEN : SessionRecord.ACTIVE,
            rec.createdBy(), rec.createdAt(), v.lastEditAt() != null ? v.lastEditAt() : rec.lastEditAt(), true,
            v.participants(), v.publishPending() );
    }

    // ---- the draft-active index and the contributor ledger -----------------------------------------------------

    /** {@link CollabSessionRegistry#draftActive}: the live session, then the index. Never reads the store. */
    boolean draftActive( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        final CollabSession s = slot == null || slot.closed() ? null : slot.session();
        if( s != null && ( !CollabSession.Hooks.members( s ).isEmpty()
                || CollabSession.Hooks.hasUnpublishedChanges( s ) ) ) {
            return true;
        }
        return index.contains( pageName );
    }

    /** After a rename (done, owed or refused): re-reads both names' drafts for the index; a failure is left to the reload. */
    void reindex( final String from, final String to ) {
        for( final String name : List.of( from, to ) ) {
            try {
                index.set( name, store.sessionForPage( name ).isPresent() );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab: the draft index entry of {} could not be refreshed; the next reload does: {}", name,
                    e.toString() );
            }
        }
    }

    /** {@link CollabStore#contributorsByVersion}; empty (logged) when the store cannot tell. */
    Map< Integer, List< String > > contributorsByVersion( final String canonicalId ) {
        try {
            return store.contributorsByVersion( canonicalId );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the contributors of {} could not be read; answering none: {}", canonicalId,
                e.toString() );
            return Map.of();
        }
    }

    // ---- purge -------------------------------------------------------------------------------------------------

    /** An administrator's purge of draft {@code id} (see the class comment). */
    PurgeOutcome purge( final UUID id ) {
        final PurgeOutcome out = remove( id, PURGE );
        if( out.status() == 204 ) {
            LOG.info( "collab: draft {} of {} purged by an administrator (live: {})", id, out.pageName(),
                out.wasLive() );
        }
        return out;
    }

    private PurgeOutcome remove( final UUID id, final Veto veto ) {
        final SessionSlot live = byId.get( id );
        if( live != null ) {
            live.lock();
            try {
                final CollabSession s = live.session();
                if( !live.closed() && s != null && s.id().equals( id ) ) {
                    return removeLive( live, s, veto );
                }
            } finally {
                live.unlock();
            }
        }
        try {
            return removeStored( id, veto );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: draft {} could not be removed; kept: {}", id, e.toString() );
            return new PurgeOutcome( 503, CollabSession.STORE, null, false );
        }
    }

    /** Under the slot lock. */
    private PurgeOutcome removeLive( final SessionSlot slot, final CollabSession s, final Veto veto ) {
        PurgeOutcome refused = veto.live( s );
        if( refused != null ) {
            return refused;
        }
        SessionSweeps.evictEveryone( s, CollabSessionRegistry.PURGED );   // no new publish can start now
        refused = veto.live( s );
        if( refused != null ) {
            return refused;   // a publish started just before the evictions; its participants rejoin
        }
        slot.close();
        SessionSweeps.evictAll( s );
        unmap.accept( slot, s );
        return delete( s.id(), s.pageName(), true );
    }

    /**
     * A draft not in memory, under its page's slot lock; re-read there, as a rename may have moved it (then the new
     * name's slot is taken) or a join loaded it (then it is removed as a live one). An unopened slot is retired.
     */
    private PurgeOutcome removeStored( final UUID id, final Veto veto ) {
        String name = store.session( id ).map( SessionRecord::pageName ).orElse( null );
        while( name != null ) {
            final SessionSlot slot = slots.slotFor( name );
            slot.lock();
            try {
                if( slot.closed() ) {
                    continue;   // evicted while we waited: the next slot is a fresh one
                }
                final CollabSession s = slot.session();
                if( s != null && s.id().equals( id ) ) {
                    return removeLive( slot, s, veto );
                }
                if( s == null ) {
                    slot.close();
                    slots.unmap( slot );
                }
                final SessionRecord rec = store.session( id ).orElse( null );
                if( rec == null || rec.pageName().equals( name ) ) {
                    return rec == null ? NOT_FOUND : removeStoredLocked( rec, veto );
                }
                name = rec.pageName();
            } finally {
                slot.unlock();
            }
        }
        return NOT_FOUND;
    }

    private PurgeOutcome removeStoredLocked( final SessionRecord rec, final Veto veto ) {
        final PurgeOutcome refused = veto.stored( rec );
        return refused != null ? refused : delete( rec.id(), rec.pageName(), false );
    }

    private PurgeOutcome delete( final UUID id, final String pageName, final boolean wasLive ) {
        try {
            store.deleteSession( id );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: draft {} of {} could not be deleted; kept: {}", id, pageName, e.toString() );
            return new PurgeOutcome( 503, CollabSession.STORE, pageName, wasLive );
        }
        index.remove( pageName );
        return new PurgeOutcome( 204, null, pageName, wasLive );
    }

    // ---- retention ---------------------------------------------------------------------------------------------

    /**
     * The retention sweep, when {@code retentionDays > 0} and {@link #RETENTION_EVERY} has passed since the last run (at
     * once on the first); tick thread only. One draft failing is logged and the others go on.
     *
     * @throws RuntimeException when the store cannot list its drafts; the next run is due after the usual interval
     */
    void expireIfDue( final int retentionDays, final Instant now ) {
        if( retentionDays <= 0 || lastRetention != null && now.isBefore( lastRetention.plus( RETENTION_EVERY ) ) ) {
            return;
        }
        lastRetention = now;
        final Instant horizon = now.minus( Duration.ofDays( retentionDays ) );
        for( final SessionRecord rec : store.sessions() ) {
            if( rec.pendingPublishVersion() == null && before( rec.lastEditAt(), rec.createdAt(), horizon ) ) {
                try {
                    expire( rec, horizon, retentionDays );
                } catch( final RuntimeException e ) {
                    LOG.warn( "collab: expiring draft {} of {} failed; the next run retries: {}", rec.id(),
                        rec.pageName(), e.toString() );
                }
            }
        }
    }

    private static boolean before( final Instant lastEditAt, final Instant createdAt, final Instant horizon ) {
        return ( lastEditAt != null ? lastEditAt : createdAt ).isBefore( horizon );
    }

    private void expire( final SessionRecord rec, final Instant horizon, final int retentionDays ) {
        final PurgeOutcome out = remove( rec.id(), new Veto() {
            @Override
            public PurgeOutcome live( final CollabSession s ) {
                final CollabSession.View v = s.view();
                if( v.publishPending() ) {
                    return inProgress( v.pageName(), true );
                }
                final boolean keep = v.participants() > 0
                    || !before( v.lastEditAt() != null ? v.lastEditAt() : rec.lastEditAt(), rec.createdAt(), horizon );
                return keep ? new PurgeOutcome( 409, SPARED, v.pageName(), true ) : null;
            }

            @Override
            public PurgeOutcome stored( final SessionRecord now ) {
                return now.pendingPublishVersion() != null || !before( now.lastEditAt(), now.createdAt(), horizon )
                    ? new PurgeOutcome( 409, SPARED, now.pageName(), false ) : null;
            }
        } );
        if( out.status() == 204 ) {
            expired( rec, out, retentionDays );
        }
    }

    /** The expiry of one draft: logged, and the site of its audit entry. */
    private static void expired( final SessionRecord rec, final PurgeOutcome out, final int retentionDays ) {
        LOG.info( "collab: draft {} of {} expired (no edit for {} days; live: {}); deleted without publishing",
            rec.id(), out.pageName(), retentionDays, out.wasLive() );
    }
}

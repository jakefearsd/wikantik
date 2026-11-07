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

import com.wikantik.collab.model.DraftEntry;
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
 * session that is publishing, or holds a pending-publish marker, is refused (423 {@code publish-in-progress}); the
 * check and the evictions are one step under the session lock, so nobody is evicted when it refuses. A store failure
 * answers 503 {@code store} and keeps the draft; a session already out of memory reloads on the next join. A rename
 * still owed out of a removed draft's page is harmless: its retry finds no draft to move and is settled.</p>
 *
 * <p><b>Retention</b> ({@code wikantik.collab.draft.retentionDays}; 0 keeps drafts for ever): at most every
 * {@link #RETENTION_EVERY}, every stored draft idle for longer than the retention is removed, unless it has live
 * participants or a publish pending. A draft's last edit is the latest of the live session's own clock, the stored
 * marker (written at snapshot cadence and when a session leaves memory) and the newest edit in its log
 * ({@link CollabStore#draftEntries}), so neither a missed marker nor a crash ages a fresh draft. Each expiry is logged
 * at INFO.</p>
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

    /**
     * Every stored draft, oldest real edit first ({@link CollabStore#draftEntries}: no bodies are read), overlaid with
     * its live session's state. Store failures propagate.
     */
    List< DraftListing > drafts() {
        return store.draftEntries().stream().map( this::listing ).toList();
    }

    private DraftListing listing( final DraftEntry e ) {
        final SessionSlot slot = byId.get( e.id() );
        final CollabSession s = slot == null || slot.closed() ? null : slot.session();
        if( s == null ) {
            return new DraftListing( e.id(), e.pageName(), e.status(), e.createdBy(), e.createdAt(), e.lastEditAt(),
                false, 0, e.publishPending() );
        }
        final CollabSession.View v = s.view();
        final Instant last = v.lastEditAt() == null || e.lastEditAt() != null && e.lastEditAt().isAfter( v.lastEditAt() )
            ? e.lastEditAt() : v.lastEditAt();
        return new DraftListing( e.id(), v.pageName(), s.frozen() ? SessionRecord.FROZEN : SessionRecord.ACTIVE,
            e.createdBy(), e.createdAt(), last, true, v.participants(), v.publishPending() );
    }

    // ---- the draft-active index and the contributor ledger -----------------------------------------------------

    /**
     * {@link CollabSessionRegistry#draftActive}: a live session answers for itself (participants, or unpublished
     * changes); only a page without one is answered by the index. Never reads the store.
     */
    boolean draftActive( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        final CollabSession s = slot == null || slot.closed() ? null : slot.session();
        if( s != null ) {
            return !CollabSession.Hooks.members( s ).isEmpty() || CollabSession.Hooks.hasUnpublishedChanges( s );
        }
        return index.contains( pageName );
    }

    /**
     * After a rename (done, owed or refused): re-reads both names' stored drafts for the index; a failure is left to the
     * reload. A name with a live session is answered by the session, whatever the index says.
     */
    void reindex( final String from, final String to ) {
        for( final String name : List.of( from, to ) ) {
            try {
                index.set( name, store.sessionForPage( name ).map( SessionRecord::hasUnpublished ).orElse( false ) );
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

    // ---- a session leaving memory ------------------------------------------------------------------------------

    /**
     * Session {@code s} is leaving memory (under its slot lock, participants gone). An idle eviction without unpublished
     * changes deletes the draft (a failure keeps it, logged; the next join reloads it). Otherwise the draft markers are
     * stored first, as the drafts list, retention and the draft index read them from now on, and an idle eviction then
     * truncates the log (A10 Minor).
     */
    void unloaded( final CollabSession s, final boolean idle ) {
        final boolean unpublished = CollabSession.Hooks.hasUnpublishedChanges( s );
        if( idle && !unpublished ) {
            try {
                store.deleteSession( s.id() );
                index.remove( s.pageName() );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {} ({}): unchanged draft not deleted; the next join reloads it: {}", s.id(),
                    s.pageName(), e.toString() );
            }
            return;
        }
        CollabSession.Hooks.storeMarkers( s );
        index.set( s.pageName(), unpublished );
        if( idle ) {
            s.truncateLog();
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

    /** Under the slot lock (no join can add a participant meanwhile). */
    private PurgeOutcome removeLive( final SessionSlot slot, final CollabSession s, final Veto veto ) {
        final PurgeOutcome refused = veto.live( s );
        if( refused != null ) {
            return refused;
        }
        // the publish check and the evictions are one step: nobody is told "purged" for a draft that a publish keeps
        if( !CollabSession.Hooks.evictAllUnlessPublishing( s, CollabSessionRegistry.PURGED ) ) {
            return inProgress( s.pageName(), true );
        }
        slot.close();
        SessionSweeps.evictAll( s );
        unmap.accept( slot, s );
        final PurgeOutcome out = delete( s.id(), s.pageName(), true );
        if( out.status() != 204 ) {
            unloaded( s, false );   // kept, but out of memory: its markers and the index, as any unload
        }
        return out;
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
        for( final DraftEntry e : store.draftEntries() ) {
            if( !e.publishPending() && e.lastActivity().isBefore( horizon ) ) {
                try {
                    expire( e, horizon, retentionDays );
                } catch( final RuntimeException ex ) {
                    LOG.warn( "collab: expiring draft {} of {} failed; the next run retries: {}", e.id(), e.pageName(),
                        ex.toString() );
                }
            }
        }
    }

    /** True when the latest of the given edit times (nulls ignored), else {@code createdAt}, is before {@code horizon}. */
    private static boolean idleSince( final Instant horizon, final Instant createdAt, final Instant... edits ) {
        Instant last = createdAt;
        for( final Instant t : edits ) {
            if( t != null && t.isAfter( last ) ) {
                last = t;
            }
        }
        return last.isBefore( horizon );
    }

    /**
     * Removes {@code e}'s draft unless, re-checked under its slot lock, it has participants, a publish pending, or an
     * edit since {@code horizon}: by the live session's own clock, the stored marker, or the entry's logged edit.
     */
    private void expire( final DraftEntry e, final Instant horizon, final int retentionDays ) {
        final PurgeOutcome out = remove( e.id(), new Veto() {
            @Override
            public PurgeOutcome live( final CollabSession s ) {
                final CollabSession.View v = s.view();
                if( v.publishPending() ) {
                    return inProgress( v.pageName(), true );
                }
                final boolean keep = v.participants() > 0
                    || !idleSince( horizon, e.createdAt(), v.lastEditAt(), e.lastEditAt() );
                return keep ? new PurgeOutcome( 409, SPARED, v.pageName(), true ) : null;
            }

            @Override
            public PurgeOutcome stored( final SessionRecord now ) {
                return now.pendingPublishVersion() != null
                    || !idleSince( horizon, now.createdAt(), now.lastEditAt(), e.lastEditAt() )
                    ? new PurgeOutcome( 409, SPARED, now.pageName(), false ) : null;
            }
        } );
        if( out.status() == 204 ) {
            expired( e, out, retentionDays );
        }
    }

    /** The expiry of one draft: logged, and the site of its audit entry. */
    private static void expired( final DraftEntry rec, final PurgeOutcome out, final int retentionDays ) {
        LOG.info( "collab: draft {} of {} expired (no edit for {} days; live: {}); deleted without publishing",
            rec.id(), out.pageName(), retentionDays, out.wasLive() );
    }
}

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
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The mutable state of one session (section 7.1) and the store primitives that change it, store first and memory only
 * on success. Every field and method is guarded by the session lock: callers hold it.
 *
 * <p>Besides the text and meta state it holds the publish/merge coordination: {@code publishing} (a publish is between
 * its start and its end), {@code ticket} (the publish inside its save), {@code unfinished} (a saved publish whose
 * completion failed: its S_V for the pending-publish rule), {@code mergeQueued} (an external change arrived while
 * publishing, A3) and {@code mergeOwed} (a merge or the pending-publish rule failed and is retried by
 * {@code settle}).</p>
 *
 * <p>It also records the draft's contributors since the last publish ({@link Contributors}): every snapshot carries
 * them, and a {@code draft-state} event is emitted when the contributor set or {@code unpublished} changes
 * ({@link #announce}).</p>
 */
final class Draft {

    private static final Logger LOG = LogManager.getLogger( Draft.class );

    /** What a {@code draft-state} event reports: the contributor keys (first-contribution order) and unpublished. */
    record State( List< String > contributorKeys, boolean unpublished ) {
    }

    /** M_V and its meta seq, as the publish snapshot at V recorded them. */
    record AtPublish( Map< String, Object > meta, long metaSeq ) {
    }

    /** Moves the kept selections after an update moved the head ({@link Presence#follow}); set by the session. */
    interface Follow {
        void moved( int oldHead, List< StoredUpdate > applied, int newHead );
    }

    final UUID id;
    final CollabStore store;
    final Oversize oversize;
    final UpdateRing ring;
    final SessionFeed feed;

    SessionRecord record;
    String doc;
    int head;
    ChangeSet local;
    Map< String, Object > meta;
    long metaSeq;
    Instant lastEditAt;
    int lastSnapshotVersion;
    long presenceSeq;

    boolean publishing;
    /**
     * True while the last attempt to store the draft markers failed: truncation is skipped, so the logged edit times
     * (which {@code CollabStore#draftEntries} and {@code unpublishedByPage} read) outlive the stale marker.
     */
    boolean markersStale;
    PublishStart.Ticket ticket;
    PublishStart.Ticket unfinished;
    boolean mergeQueued;
    boolean mergeOwed;
    /**
     * Memory only: this session saw its page deleted (a freeze was attempted, or the orphan sweep found the page missing
     * with no rename owed). A merge that then finds the page back at the base version with other content is a recreate,
     * and guards the draft (security re-review 1).
     */
    boolean deleteSeen;
    /** The guard a publish in its save was allowed past (an administrator's, F3); only that guard is lifted. */
    String publishGuard;

    Follow follow = ( oldHead, applied, newHead ) -> { };
    /** The names the session's page held before its renames (post-join calls may still use them). */
    final Set< String > formerNames = new LinkedHashSet<>();
    /** The seq of the latest {@code page-deleted} event, 0 when none (a joiner into a frozen draft is shown it). */
    long deletedEventSeq;
    /** Who contributed since the last publish; replaced only when a publish commits. */
    Contributors contributors = Contributors.of( Map.of() );
    /** An author key's public name, null when it has none ({@link CollabIdentity}); set by the session. */
    Function< String, String > names = key -> null;

    Draft( final SessionRecord record, final CollabStore store, final long maxPageBytes, final int ringSize ) {
        this.id = record.id();
        this.record = record;
        this.store = store;
        this.oversize = new Oversize( maxPageBytes );
        this.ring = new UpdateRing( ringSize );
        this.feed = new SessionFeed( ringSize, List.of() );
    }

    String pageName() {
        return record.pageName();
    }

    /** The page was deleted under the draft: pushes and discards answer 409 {@code frozen}, a publish recreates it. */
    boolean frozen() {
        return SessionRecord.FROZEN.equals( record.status() );
    }

    /** A3: true, with the change queued for the publish's end, while a publish is running. */
    boolean queueIfPublishing() {
        if( publishing ) {
            mergeQueued = true;
        }
        return publishing;
    }

    /** The base page version and content hash, to tell whether a step changed the base. */
    String baseKey() {
        return record.basePageVersion() + ":" + record.baseContentHash();
    }

    void applyAccepted( final List< StoredUpdate > accepted, final String next ) {
        ChangeSet l = local;
        for( final StoredUpdate u : accepted ) {
            l = l.compose( u.changes() );
        }
        ring.addAll( accepted );
        local = l;
        doc = next;
        final int oldHead = head;
        head += accepted.size();
        follow.moved( oldHead, accepted, head );
    }

    boolean hasUnpublished() {
        return !local.empty() || !meta.equals( MetaJson.normalise( record.baseMeta() ) );
    }

    /** A publish is running, or its pending-publish marker is still stored. */
    boolean publishPending() {
        return publishing || record.pendingPublishVersion() != null;
    }

    /**
     * The first join into a fresh session (A11): {@code initialText} as a server update and {@code initialMeta} as meta
     * ops; a store failure propagates with nothing applied in memory that the store does not hold.
     */
    void seed( final JoinRequest r, final Instant now ) {
        final State before = state();
        final String text = r.initialText() == null ? null : seedTextChecked( r.initialText() );
        final List< StoredMetaOp > ops = r.initialMeta() == null ? List.of() : seedMetaOps( r );
        seedText( r, text, now );
        if( !ops.isEmpty() ) {
            store.appendMetaOps( id, ops );
            applyMetaOps( ops );
            contributors.record( List.of(), ops );
        }
        announce( before );
    }

    /**
     * A seed's text, LF-normalised, held to a push's rules before anything is stored (final review item 4): no lone
     * surrogate (400), and no more than {@code max(maxPageBytes, current size)} UTF-8 bytes ({@link SeedTooLarge}, 413).
     */
    private String seedTextChecked( final String initialText ) {
        final String text = CollabText.toLf( initialText );
        if( CollabText.hasLoneSurrogate( text ) ) {
            throw new IllegalArgumentException( "initialText holds a lone UTF-16 surrogate" );
        }
        if( Oversize.utf8( text ) > Math.max( oversize.maxPageBytes(), Oversize.utf8( doc ) ) ) {
            throw new SeedTooLarge();
        }
        return text;
    }

    /**
     * A seed's frontmatter as meta ops, held to a push's rules (final review item 4): every field must be one a meta edit
     * may set, set-field items are stored as strings (ruling Q7), and no resolved value may exceed
     * {@link MetaEdits#MAX_VALUE_BYTES} (ruling Q8); else 400.
     */
    private List< StoredMetaOp > seedMetaOps( final JoinRequest r ) {
        final Map< String, Object > wanted = new LinkedHashMap<>();
        r.initialMeta().forEach( ( field, value ) -> {
            final MetaEdit set = MetaEdit.set( field, value );
            final String why = MetaEdits.invalid( set );
            if( why != null ) {
                throw new IllegalArgumentException( "initialMeta: " + why );
            }
            wanted.put( field, MetaEdits.resolve( null, set ) );
        } );
        final List< StoredMetaOp > ops = SessionLoader.metaOps( meta, wanted, metaSeq, r.authorKey() );
        if( MetaEdits.oversized( ops ) != null ) {
            throw new IllegalArgumentException( "initialMeta: a value exceeds " + MetaEdits.MAX_VALUE_BYTES + " bytes" );
        }
        return ops;
    }

    /** A seed whose text is over the page size limit: 413 {@code oversize}, as a push that would grow past it. */
    static final class SeedTooLarge extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        SeedTooLarge() {
            super( "initialText exceeds the page size limit" );
        }
    }

    /** The seed's text, stored with the creator's key so it is credited to the creator (ruling R2). */
    private void seedText( final JoinRequest r, final String text, final Instant now ) {
        if( text != null ) {
            if( !text.equals( doc ) ) {
                final StoredUpdate u = new StoredUpdate( head + 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT,
                    r.authorKey(), TextDiff.changeSet( doc, text ) );
                store.appendUpdates( id, List.of( u ) );
                applyAccepted( List.of( u ), text );
                contributors.record( List.of( u ), List.of() );
                lastEditAt = now;
                oversize.update( doc, true );
            }
        }
    }

    /**
     * Whether the log holds a text update with {@code clientId} under {@code authorKey}: the ring first, then the store
     * (a rejoin after a reload reclaims its clientID this way). A store that cannot tell answers false (logged): the
     * join then gets a new clientID, and the client recovers its unconfirmed text as it would without the reclaim.
     */
    boolean logged( final String clientId, final String authorKey ) {
        final boolean inRing = ring.asList().stream().anyMatch( u -> StoredUpdate.TEXT.equals( u.kind() )
            && u.clientId().equals( clientId ) && authorKey.equals( u.authorKey() ) );
        if( inRing ) {
            return true;
        }
        try {
            return store.holdsUpdatesFrom( id, clientId, authorKey );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: could not tell whether {} may reclaim a clientID; issuing a new one: {}", id,
                authorKey, e.toString() );
            return false;
        }
    }

    // ---- draft state ---------------------------------------------------------------------------------------------

    State state() {
        return new State( contributors.authors(), hasUnpublished() );
    }

    /**
     * Emits {@code draft-state {unpublished, contributors, lastEditAt}} when the state differs from {@code before}: an
     * author's first contribution since the last publish, a publish, a discard, a merge that changed
     * {@code unpublished}; never on a plain edit. Names come from the identity, never the key ({@link Contributors#names}).
     * The caller wakes parked pulls.
     */
    void announce( final State before ) {
        final State now = state();
        if( now.equals( before ) ) {
            return;
        }
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "unpublished", now.unpublished() );
        f.put( "contributors", contributorNames() );
        if( lastEditAt != null ) {
            f.put( "lastEditAt", lastEditAt.toString() );   // absent until the first edit (events hold no nulls)
        }
        feed.emit( "draft-state", f );
    }

    /** The contributors' public names, first-contribution order. */
    List< String > contributorNames() {
        return Contributors.names( contributors.authors(), names );
    }

    // ---- snapshots and truncation ------------------------------------------------------------------------------

    boolean snapshotDue( final int every ) {
        return head - lastSnapshotVersion >= every;
    }

    /** The cadence snapshot, the draft markers, then truncation below {@code lowestConfirmed}. Failures only warn (E36). */
    void snapshot( final int lowestConfirmed ) {
        try {
            store.writeSnapshot( id, new Snapshot( head, metaSeq, record.basePageVersion(), doc, meta, local,
                contributors.snapshot() ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): snapshot at {} failed; retried on the next push: {}", id, pageName(), head,
                e.getMessage() );
            return;
        }
        lastSnapshotVersion = head;
        storeMarkers();
        truncate( lowestConfirmed );
    }

    /**
     * Stores the draft markers ({@code hasUnpublished}, {@code lastEditAt}) from memory: at snapshot cadence, and when the
     * session leaves memory (the drafts list and retention read them). A failure only warns: retention also reads the
     * update log's own times.
     */
    void storeMarkers() {
        try {
            final SessionRecord marked = SessionLoader.draftMarkers( record, hasUnpublished(), lastEditAt );
            store.updateSession( marked );
            record = marked;
            markersStale = false;
        } catch( final RuntimeException e ) {
            markersStale = true;
            LOG.warn( "collab session {} ({}): draft markers not stored; the log is not truncated until they are: {}",
                id, pageName(), e.getMessage() );
        }
    }

    /**
     * A10: truncates below {@code lowestConfirmed}, never above a pending publish's version (S_V must stay rebuildable
     * for the pending-publish rule, E39); the store clamps the cut to a snapshot. Returns the cut, or -1 (logged).
     */
    int truncate( final int lowestConfirmed ) {
        if( markersStale ) {
            // the logged edit times are the only record of the edits the stored marker missed (retention, the index)
            LOG.debug( "collab session {} ({}): truncation skipped until the draft markers are stored", id, pageName() );
            return -1;
        }
        final Integer pending = record.pendingPublishVersion();
        final int cut = pending == null ? lowestConfirmed : Math.min( lowestConfirmed, pending );
        try {
            store.truncateUpdatesBelow( id, cut );
            return cut;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): truncation below {} failed: {}", id, pageName(), cut, e.getMessage() );
            return -1;
        }
    }

    // ---- base changes ----------------------------------------------------------------------------------------------

    /**
     * Commits a base change (server updates, the record, a snapshot of the live head) in one store step; memory follows
     * only on success. A merge or rewrite may land the draft over the limit: it is applied anyway and the session enters
     * oversize state (section 7.4, E25), with an {@code oversize} event.
     */
    boolean commit( final List< StoredUpdate > extra, final String docAfter, final ChangeSet localAfter,
                    final SessionRecord updated ) {
        return commit( extra, docAfter, localAfter, updated, contributors );
    }

    /**
     * {@link #commit(List, String, ChangeSet, SessionRecord)} whose snapshot carries {@code contributorsAfter}, which
     * replaces the live contributors only when the store step succeeded (a publish's {@link Contributors#afterPublish}).
     */
    boolean commit( final List< StoredUpdate > extra, final String docAfter, final ChangeSet localAfter,
                    final SessionRecord updated, final Contributors contributorsAfter ) {
        final Snapshot snap = new Snapshot( head + extra.size(), metaSeq, updated.basePageVersion(), docAfter, meta,
            localAfter, contributorsAfter.snapshot() );
        try {
            store.commitBaseChange( id, extra, snap, updated );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): the base change to page version {} was not stored; nothing applied: {}", id,
                pageName(), updated.basePageVersion(), e.getMessage() );
            return false;
        }
        ring.addAll( extra );
        final int oldHead = head;
        head += extra.size();
        follow.moved( oldHead, extra, head );
        doc = docAfter;
        local = localAfter;
        record = updated;
        contributors = contributorsAfter;
        lastSnapshotVersion = head;
        final boolean was = oversize.active();
        oversize.update( doc, true );
        if( !was && oversize.active() ) {
            feed.emit( "oversize", Map.of( "bytes", Oversize.utf8( doc ), "limit", oversize.maxPageBytes() ) );
        }
        return true;
    }

    /** Server meta ops, stored first (before the base change they belong to), then applied; false on failure. */
    boolean appendMetaOps( final List< StoredMetaOp > ops ) {
        if( ops.isEmpty() ) {
            return true;
        }
        try {
            store.appendMetaOps( id, ops );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): server meta ops not stored; nothing applied: {}", id, pageName(),
                e.getMessage() );
            return false;
        }
        applyMetaOps( ops );
        return true;
    }

    /** Stored meta ops applied in memory: meta, metaSeq and the pull feed. */
    void applyMetaOps( final List< StoredMetaOp > ops ) {
        ops.forEach( op -> SessionLoader.applyMetaOp( meta, op ) );
        metaSeq += ops.size();
        feed.addMetaOps( ops );
    }

    /** W, the composition of the updates after {@code v} (from a document of {@code length}); null when not held. */
    ChangeSet composedSince( final int v, final int length ) {
        final List< StoredUpdate > after;
        if( v == head ) {
            after = List.of();
        } else if( ring.covers( v ) ) {
            after = ring.after( v );
        } else {
            try {
                after = SessionLoader.contiguousSince( store, id, v, head );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {}: reading the log after {} failed: {}", id, v, e.getMessage() );
                return null;
            }
        }
        if( after == null ) {
            return null;
        }
        ChangeSet w = ChangeSet.empty( length );
        for( final StoredUpdate u : after ) {
            w = w.compose( u.changes() );
        }
        return w;
    }

    /**
     * M_V of the publish marked at version {@code v} and its meta seq: those of the snapshot the publish wrote at
     * {@code v} before its marker. Falls back to the live meta and seq (logged) when that snapshot is absent, as for a
     * marker stored before publishes wrote one; the fold may then revert meta edits made during that save, and meta
     * contributors of that save are credited to it. A store failure propagates: the caller's pending-publish rule is
     * retried by {@code settle}.
     */
    AtPublish atPublish( final int v ) {
        final Optional< Snapshot > snap = store.snapshotAtOrBelow( id, v );
        if( snap.isPresent() && snap.get().version() == v ) {
            return new AtPublish( MetaJson.normalise( snap.get().meta() ), snap.get().metaSeq() );
        }
        LOG.warn( "collab session {} ({}): no snapshot at the published version {}; recovering with the live meta", id,
            pageName(), v );
        return new AtPublish( meta, metaSeq );
    }

    /** The document at version {@code v}: from the newest snapshot at or below it plus the log; null when not rebuildable. */
    String docAt( final int v ) {
        if( v == head ) {
            return doc;
        }
        try {
            final Optional< Snapshot > snap = store.snapshotAtOrBelow( id, v );
            if( snap.isEmpty() ) {
                return null;
            }
            String d = snap.get().body();
            int at = snap.get().version();
            for( final StoredUpdate u : store.updatesSince( id, at ) ) {
                if( u.version() > v ) {
                    break;
                }
                if( u.version() != at + 1 ) {
                    return null;
                }
                d = u.changes().apply( d );
                at = u.version();
            }
            return at == v ? d : null;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: rebuilding the document at {} failed: {}", id, v, e.getMessage() );
            return null;
        }
    }
}

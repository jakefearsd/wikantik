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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mutable state of one session (section 7.1) and the store primitives that change it, store first and memory only
 * on success. Every field and method is guarded by the session lock: callers hold it.
 *
 * <p>Besides the text and meta state it holds the publish/merge coordination: {@code publishing} (a publish is between
 * its start and its end), {@code ticket} (the publish inside its save), {@code unfinished} (a saved publish whose
 * completion failed: its S_V for the pending-publish rule), {@code mergeQueued} (an external change arrived while
 * publishing, A3) and {@code mergeOwed} (a merge or the pending-publish rule failed and is retried by
 * {@code settle}).</p>
 */
final class Draft {

    private static final Logger LOG = LogManager.getLogger( Draft.class );

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
    PublishStart.Ticket ticket;
    PublishStart.Ticket unfinished;
    boolean mergeQueued;
    boolean mergeOwed;

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

    void applyAccepted( final List< StoredUpdate > accepted, final String next ) {
        ChangeSet l = local;
        for( final StoredUpdate u : accepted ) {
            l = l.compose( u.changes() );
        }
        ring.addAll( accepted );
        local = l;
        doc = next;
        head += accepted.size();
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
        if( r.initialText() != null ) {
            final String text = CollabText.toLf( r.initialText() );
            if( CollabText.hasLoneSurrogate( text ) ) {
                throw new IllegalArgumentException( "initialText holds a lone UTF-16 surrogate" );
            }
            if( !text.equals( doc ) ) {
                final StoredUpdate u = new StoredUpdate( head + 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT,
                    r.authorKey(), TextDiff.changeSet( doc, text ) );
                store.appendUpdates( id, List.of( u ) );
                applyAccepted( List.of( u ), text );
                lastEditAt = now;
                oversize.update( doc, true );
            }
        }
        if( r.initialMeta() != null ) {
            final List< StoredMetaOp > ops = SessionLoader.metaOps( meta, MetaJson.normalise( r.initialMeta() ), metaSeq,
                r.authorKey() );
            if( !ops.isEmpty() ) {
                store.appendMetaOps( id, ops );
                applyMetaOps( ops );
            }
        }
    }

    // ---- snapshots and truncation ------------------------------------------------------------------------------

    boolean snapshotDue( final int every ) {
        return head - lastSnapshotVersion >= every;
    }

    /** The cadence snapshot, the draft markers, then truncation below {@code lowestConfirmed}. Failures only warn (E36). */
    void snapshot( final int lowestConfirmed ) {
        try {
            store.writeSnapshot( id, new Snapshot( head, metaSeq, record.basePageVersion(), doc, meta, local ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): snapshot at {} failed; retried on the next push: {}", id, pageName(), head,
                e.getMessage() );
            return;
        }
        lastSnapshotVersion = head;
        try {
            final SessionRecord marked = SessionLoader.draftMarkers( record, hasUnpublished(), lastEditAt );
            store.updateSession( marked );
            record = marked;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): draft markers not stored: {}", id, pageName(), e.getMessage() );
        }
        truncate( lowestConfirmed );
    }

    /**
     * A10: truncates below {@code lowestConfirmed}, never above a pending publish's version (S_V must stay rebuildable
     * for the pending-publish rule, E39); the store clamps the cut to a snapshot. Returns the cut, or -1 (logged).
     */
    int truncate( final int lowestConfirmed ) {
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
        final Snapshot snap = new Snapshot( head + extra.size(), metaSeq, updated.basePageVersion(), docAfter, meta,
            localAfter );
        try {
            store.commitBaseChange( id, extra, snap, updated );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): the base change to page version {} was not stored; nothing applied: {}", id,
                pageName(), updated.basePageVersion(), e.getMessage() );
            return false;
        }
        ring.addAll( extra );
        head += extra.size();
        doc = docAfter;
        local = localAfter;
        record = updated;
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

    private void applyMetaOps( final List< StoredMetaOp > ops ) {
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

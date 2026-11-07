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

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable creation and reload of a session's state (sections 7.6 and 7.7), separate from its live behaviour. */
final class SessionLoader {

    private static final Logger LOG = LogManager.getLogger( SessionLoader.class );

    /**
     * The state {@link #load} rebuilt. {@code ring} is the contiguous tail of the retained log, at most the ring size;
     * {@code contributors} the snapshot's plus every authored update and meta op after it.
     */
    record Loaded( SessionRecord record, String doc, int head, ChangeSet local, Map< String, Object > meta,
                   int snapshotVersion, List< StoredUpdate > ring, Contributors contributors ) {
    }

    private SessionLoader() {
    }

    /**
     * Writes a new session and its version-0 snapshot, with the base body normalised to LF (A9); returns the record as
     * stored. A failed snapshot removes the session again and rethrows.
     */
    static SessionRecord create( final SessionRecord rec, final CollabStore store ) {
        if( rec.headVersion() != 0 || rec.metaSeq() != 0 ) {
            throw new IllegalArgumentException( "a new collab session starts at head 0 and metaSeq 0: " + rec );
        }
        final String body = CollabText.toLf( rec.baseBody() );
        final SessionRecord normalised = body.equals( rec.baseBody() ) ? rec
            : rec.withBase( rec.basePageVersion(), body, rec.baseMeta(), CollabText.contentHash( body ) );
        store.createSession( normalised );
        try {
            store.writeSnapshot( normalised.id(), new Snapshot( 0, 0, normalised.basePageVersion(), body,
                normalised.baseMeta(), ChangeSet.empty( body.length() ) ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} for {}: the version-0 snapshot failed; removing the session: {}", normalised.id(),
                normalised.pageName(), e.getMessage() );
            try {
                store.deleteSession( normalised.id() );
            } catch( final RuntimeException cleanup ) {
                LOG.warn( "collab session {}: cleanup after a failed create also failed: {}", normalised.id(),
                    cleanup.getMessage() );
                e.addSuppressed( cleanup );
            }
            throw e;
        }
        return normalised;
    }

    /**
     * Doc and head from the latest snapshot plus the log after it, meta from the snapshot plus the meta ops after it,
     * local from the snapshot's local composed with the log (or {@code TextDiff.changeSet(baseBody, doc)} when the
     * snapshot belongs to another base), the ring from the log's tail, the contributors from the snapshot's plus the
     * authored updates and meta ops after it (ruling R5).
     *
     * @throws IllegalStateException when the session or its snapshot is missing, or the log is not contiguous from the
     *                               snapshot to the recorded head
     */
    static Loaded load( final UUID id, final CollabStore store, final int ringSize ) {
        final SessionRecord rec = store.session( id ).orElseThrow(
            () -> new IllegalStateException( "no collab session " + id + " to load" ) );
        final Snapshot snap = store.latestSnapshot( id ).orElseThrow(
            () -> new IllegalStateException( "collab session " + id + " has no snapshot" ) );
        String doc = snap.body();
        ChangeSet local = snap.local();
        int version = snap.version();
        final List< StoredUpdate > log = store.updatesSince( id, snap.version() );
        for( final StoredUpdate u : log ) {
            if( u.version() != version + 1 ) {
                throw new IllegalStateException( "collab log of " + id + " has a gap before version " + u.version() );
            }
            doc = u.changes().apply( doc );
            local = local.compose( u.changes() );
            version = u.version();
        }
        if( version != rec.headVersion() ) {
            throw new IllegalStateException( "collab log of " + id + " ends at " + version + " but the head is "
                + rec.headVersion() );
        }
        if( snap.basePageVersion() != rec.basePageVersion() || local.length() != rec.baseBody().length()
                || !local.apply( rec.baseBody() ).equals( doc ) ) {
            LOG.warn( "collab session {}: snapshot {} does not fit base version {}; recomputing local by diff", id,
                snap.version(), rec.basePageVersion() );
            local = TextDiff.changeSet( rec.baseBody(), doc );
        }
        final Map< String, Object > meta = new LinkedHashMap<>( snap.meta() );
        final List< StoredMetaOp > ops = store.metaOpsSince( id, snap.metaSeq() );
        ops.forEach( op -> applyMetaOp( meta, op ) );
        final Contributors contributors = Contributors.of( snap.contributors() );
        contributors.record( log, ops );
        return new Loaded( rec, doc, version, local, meta, snap.version(),
            tail( store.updatesSince( id, Math.max( 0, version - ringSize ) ) ), contributors );
    }

    /** {@code value} null removes the field (section 6.1). */
    static void applyMetaOp( final Map< String, Object > m, final StoredMetaOp op ) {
        if( op.value() == null ) {
            m.remove( op.field() );
        } else {
            m.put( op.field(), op.value() );
        }
    }

    /**
     * The field ops that turn {@code current} into {@code wanted} (A11's initial meta): a set for each field that is new
     * or differs, a removal for each field {@code wanted} lacks; sequence numbers from {@code metaSeq + 1}.
     */
    static List< StoredMetaOp > metaOps( final Map< String, Object > current, final Map< String, Object > wanted,
                                         final long metaSeq, final String authorKey ) {
        final List< StoredMetaOp > ops = new java.util.ArrayList<>();
        wanted.forEach( ( field, value ) -> {
            if( !current.containsKey( field ) || !java.util.Objects.equals( current.get( field ), value ) ) {
                ops.add( new StoredMetaOp( metaSeq + ops.size() + 1, authorKey, field, value ) );
            }
        } );
        for( final String field : current.keySet() ) {
            if( !wanted.containsKey( field ) ) {
                ops.add( new StoredMetaOp( metaSeq + ops.size() + 1, authorKey, field, null ) );
            }
        }
        return ops;
    }

    /** The record with the draft markers ({@code hasUnpublished}, {@code lastEditAt}) replaced. */
    static SessionRecord draftMarkers( final SessionRecord r, final boolean unpublished, final java.time.Instant lastEdit ) {
        return new SessionRecord( r.id(), r.pageName(), r.status(), r.basePageVersion(), r.baseBody(), r.baseMeta(),
            r.baseContentHash(), r.headVersion(), r.metaSeq(), unpublished, r.pendingPublishVersion(),
            r.pendingPublishBaseVersion(), r.pendingPublishAuthor(), r.pendingPublishSavedVersion(),
            r.pendingPublishCanonicalId(), r.createdBy(), r.createdAt(), lastEdit );
    }

    /**
     * Catch-up from the store (A10): the updates {@code since + 1 .. head}, or null when truncation removed some of them
     * (the 410 case).
     */
    static List< StoredUpdate > contiguousSince( final CollabStore store, final UUID id, final int since, final int head ) {
        final List< StoredUpdate > log = store.updatesSince( id, since ).stream().filter( u -> u.version() <= head ).toList();
        return log.size() == head - since && ( log.isEmpty() || log.get( 0 ).version() == since + 1 ) ? log : null;
    }

    /** The contiguous suffix of a log (it may start late after truncation, but never has an inner gap). */
    private static List< StoredUpdate > tail( final List< StoredUpdate > log ) {
        int from = log.size();
        while( from > 0 && ( from == log.size() || log.get( from - 1 ).version() == log.get( from ).version() - 1 ) ) {
            from--;
        }
        return log.subList( from, log.size() );
    }
}

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

import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Section 6.2's discard, with the session's base lock held (so the base cannot move): the draft text is replaced by the
 * base as one server update and the meta restored by server meta ops, stored in one all-or-nothing step and only then
 * applied in memory, followed by a best-effort snapshot (the base itself does not change). The diff runs outside the
 * session lock (as publish's does); a push landing meanwhile moves the head and the diff is taken again, under the lock
 * after {@value #ATTEMPTS} tries.
 */
final class DraftDiscard {

    private static final Logger LOG = LogManager.getLogger( DraftDiscard.class );
    private static final int ATTEMPTS = 3;

    private final Draft d;
    private final ReentrantLock lock;

    DraftDiscard( final Draft d, final ReentrantLock lock ) {
        this.d = d;
        this.lock = lock;
    }

    PushResponse reset( final String by, final Answers answers, final List< Runnable > completions ) {
        for( int attempt = 1; attempt <= ATTEMPTS; attempt++ ) {
            final String doc;
            final int head;
            final String baseBody;
            lock.lock();
            try {
                doc = d.doc;
                head = d.head;
                baseBody = d.record.baseBody();
            } finally {
                lock.unlock();
            }
            final ChangeSet back = diff( doc, baseBody );
            lock.lock();
            try {
                if( d.head == head ) {
                    return apply( back, baseBody, by, answers, completions );
                }
            } finally {
                lock.unlock();
            }
        }
        lock.lock();
        try {
            final String baseBody = d.record.baseBody();
            return apply( diff( d.doc, baseBody ), baseBody, by, answers, completions );
        } finally {
            lock.unlock();
        }
    }

    private static ChangeSet diff( final String doc, final String baseBody ) {
        return doc.equals( baseBody ) ? ChangeSet.empty( doc.length() ) : TextDiff.changeSet( doc, baseBody );
    }

    /** Under the session lock: {@code back} turns the live document into {@code baseBody}. */
    private PushResponse apply( final ChangeSet back, final String baseBody, final String by, final Answers answers,
                                final List< Runnable > completions ) {
        if( d.publishPending() ) {
            return PushResponse.refused( 423, d.head, CollabSession.IN_PROGRESS );
        }
        final List< StoredMetaOp > ops = SessionLoader.metaOps( d.meta, MetaJson.normalise( d.record.baseMeta() ),
            d.metaSeq, null );
        if( back.empty() && ops.isEmpty() && d.local.empty() ) {
            return PushResponse.accepted( d.head, d.metaSeq );
        }
        final List< StoredUpdate > extra = back.empty() ? List.of()
            : List.of( new StoredUpdate( d.head + 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, null, back ) );
        if( !store( extra, ops ) ) {
            return PushResponse.refused( 503, d.head, CollabSession.STORE );
        }
        d.ring.addAll( extra );
        d.head += extra.size();
        d.doc = baseBody;
        d.local = ChangeSet.empty( baseBody.length() );
        if( !ops.isEmpty() ) {
            d.applyMetaOps( ops );
        }
        d.oversize.update( d.doc, true );
        d.record = SessionLoader.draftMarkers( d.record, false, d.lastEditAt );
        snapshot();
        d.feed.emit( "draft-discarded", Map.of( "by", by ) );
        answers.wakeAll( extra, completions );
        return PushResponse.accepted( d.head, d.metaSeq );
    }

    /** The text and the meta in one store step (all or nothing); false (logged, nothing stored) on a failure. */
    private boolean store( final List< StoredUpdate > extra, final List< StoredMetaOp > ops ) {
        try {
            if( ops.isEmpty() ) {
                if( !extra.isEmpty() ) {
                    d.store.appendUpdates( d.id, extra );
                }
            } else {
                d.store.appendBatch( d.id, extra, ops );
            }
            return true;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): discard not stored, nothing applied: {}", d.id, d.pageName(),
                e.getMessage() );
            return false;
        }
    }

    /** A snapshot of the reset draft, so a reload starts from an empty {@code local}; a failure only warns (E36). */
    private void snapshot() {
        try {
            d.store.writeSnapshot( d.id, new Snapshot( d.head, d.metaSeq, d.record.basePageVersion(), d.doc, d.meta,
                d.local ) );
            d.lastSnapshotVersion = d.head;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): snapshot after the discard failed; the next cadence retries: {}", d.id,
                d.pageName(), e.getMessage() );
        }
    }
}

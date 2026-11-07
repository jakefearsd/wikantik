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

import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Section 7.4, external writes, as amended by A1 (detection by content hash) and A3 (each later version merged in order
 * against its predecessor). Runs with the session's base lock held; takes the session lock only around state, so the
 * page-store reads and the diff E happen outside it.
 */
final class ExternalMerge {

    /** How far a merge moved the session. */
    enum Result { NONE, BASE_ONLY, CHANGED, FAILED }

    private final Draft d;
    private final ReentrantLock lock;
    private final CollabPageWriter writer;
    private final CollabIdentity identity;
    private final Answers answers;

    ExternalMerge( final Draft d, final ReentrantLock lock, final CollabPageWriter writer, final CollabIdentity identity,
                   final Answers answers ) {
        this.d = d;
        this.lock = lock;
        this.writer = writer;
        this.identity = identity;
        this.answers = answers;
    }

    /**
     * Every version after the base up to {@code target}, in order, each merged against its predecessor (the base at that
     * point). A stale or repeated target merges nothing; an in-place overwrite of the base version (no new version, A1)
     * is merged by content hash. Page-store failures propagate.
     */
    Result mergeTo( final PageState target, final List< Runnable > completions ) {
        final int b;
        final String page;
        lock.lock();
        try {
            b = d.record.basePageVersion();
            page = d.pageName();
            if( target.version() < b || target.version() == b && sameAsBase( target ) ) {
                return Result.NONE;
            }
        } finally {
            lock.unlock();
        }
        final List< PageState > states = new ArrayList<>();
        for( int v = b + 1; v < target.version(); v++ ) {
            writer.version( page, v ).ifPresent( states::add );
        }
        states.add( target );
        Result result = Result.NONE;
        for( final PageState st : states ) {
            final Result r = mergeOne( st, completions );
            if( r == Result.FAILED ) {
                return r;
            }
            result = r.ordinal() > result.ordinal() ? r : result;
        }
        return result;
    }

    private boolean sameAsBase( final PageState s ) {
        return s.contentHash().equals( d.record.baseContentHash() )
            && MetaJson.normalise( s.meta() ).equals( MetaJson.normalise( d.record.baseMeta() ) );
    }

    /**
     * One version: {@code E = diff(baseBody, body)}, {@code E' = E.map(local)} appended as a server update,
     * {@code local = local.map(E, before=true)}, meta per field last-writer-wins (theirs when only they changed it, ours
     * with a conflict when both did), committed with a snapshot of the live head; an {@code external-change} event.
     */
    private Result mergeOne( final PageState st, final List< Runnable > completions ) {
        final String body = CollabText.toLf( st.body() );
        final String baseBody;
        final boolean same;
        lock.lock();
        try {
            baseBody = d.record.baseBody();
            same = CollabText.contentHash( body ).equals( d.record.baseContentHash() );
        } finally {
            lock.unlock();
        }
        // E: the base only changes under the base lock, which is held, so the diff runs outside the session lock
        final ChangeSet e = same ? ChangeSet.empty( baseBody.length() ) : TextDiff.changeSet( baseBody, body );
        lock.lock();
        try {
            final ChangeSet ePrime = e.map( d.local );
            final ChangeSet localAfter = d.local.map( e, true );
            final String docAfter = ePrime.apply( d.doc );
            final List< StoredUpdate > extra = ePrime.empty() ? List.of()
                : List.of( new StoredUpdate( d.head + 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, null, ePrime ) );
            final Map< String, Object > theirs = MetaJson.normalise( st.meta() );
            final MetaMerge.External mm = MetaMerge.external( MetaJson.normalise( d.record.baseMeta() ), d.meta, theirs );
            if( !d.appendMetaOps( MetaMerge.ops( mm.changes(), d.metaSeq ) ) ) {
                return Result.FAILED;
            }
            final SessionRecord updated = SessionLoader.draftMarkers( d.record.withBase( st.version(), body, st.meta(),
                CollabText.contentHash( body ) ), !localAfter.empty() || !d.meta.equals( theirs ), d.lastEditAt );
            if( !d.commit( extra, docAfter, localAfter, updated ) ) {
                return Result.FAILED;
            }
            if( !extra.isEmpty() || !mm.changes().isEmpty() || !mm.conflicts().isEmpty() ) {
                d.feed.emit( "external-change", event( st, ePrime, mm ) );
            }
            answers.wakeAll( extra, completions );
            return extra.isEmpty() && mm.changes().isEmpty() ? Result.BASE_ONLY : Result.CHANGED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The {@code external-change} event: the writer's public name ({@code agent} when it has none; never the login), the
     * version, the changed ranges in the merged document's coordinates, and the meta fields both sides changed.
     */
    private Map< String, Object > event( final PageState st, final ChangeSet ePrime, final MetaMerge.External mm ) {
        final String name = st.author() == null ? null : identity.displayName( st.author() );
        final List< List< Integer > > ranges = new ArrayList<>();
        for( final int[] r : ePrime.touchedRanges() ) {
            ranges.add( List.of( ePrime.mapPos( r[ 0 ], -1 ), ePrime.mapPos( r[ 1 ], 1 ) ) );
        }
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "source", name == null || name.isBlank() ? "agent" : name );
        f.put( "pageVersion", st.version() );
        f.put( "ranges", List.copyOf( ranges ) );
        f.put( "conflictFields", List.copyOf( mm.conflicts() ) );
        return f;
    }
}

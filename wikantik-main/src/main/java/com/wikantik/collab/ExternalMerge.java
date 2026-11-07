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
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Section 7.4, external writes, as amended by A1 (detection by content hash) and A3 (each later version merged in order
 * against its predecessor). Runs with the session's base lock held; takes the session lock only around state, so the
 * page-store reads and the diff E happen outside it.
 *
 * <p>A frozen draft whose page exists again (recreated by someone else) is merged with the recreated page as one
 * external change against the latest version, with no version walk (the version numbers restarted), and becomes active
 * again; its {@code external-change} event carries {@code recreated: true}. So is a page that was deleted and recreated
 * while the draft stayed active (the freeze's store write failed, or the delete event was missed): its versions
 * restarted, so its latest version is below the draft's base.</p>
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
     * point). A repeated target (the base version with the base's content hash and meta) merges nothing; an in-place
     * overwrite of the base version (no new version, A1) is merged by content hash. A target below the base is
     * {@link #belowBase}: stale, or a recreated page. Page-store failures propagate.
     */
    Result mergeTo( final PageState target, final List< Runnable > completions ) {
        final int b;
        final String page;
        final boolean frozen;
        lock.lock();
        try {
            frozen = d.frozen();
            b = d.record.basePageVersion();
            page = d.pageName();
            if( !frozen && target.version() == b && sameAsBase( target ) ) {
                return Result.NONE;
            }
        } finally {
            lock.unlock();
        }
        if( frozen ) {
            return mergeOne( target, true, completions ) == Result.FAILED ? Result.FAILED : Result.CHANGED;
        }
        if( target.version() < b ) {
            return belowBase( page, b, completions );
        }
        final List< PageState > states = new ArrayList<>();
        for( int v = b + 1; v < target.version(); v++ ) {
            writer.version( page, v ).ifPresent( states::add );
        }
        states.add( target );
        Result result = Result.NONE;
        for( final PageState st : states ) {
            final Result r = mergeOne( st, false, completions );
            if( r == Result.FAILED ) {
                return r;
            }
            result = r.ordinal() > result.ordinal() ? r : result;
        }
        return result;
    }

    /**
     * A target below the base {@code b}. A version number alone does not say it was merged (A1): while the page's latest
     * version is at or above the base, the target is a stale or repeated event of the base's own version history and
     * merges nothing (the latest version is merged by its own event, or by {@code settle}). A latest version below the
     * base means the page was deleted and recreated under an active draft and its versions restarted: the recreated page
     * is merged as one external change against its latest version ({@code recreated: true}, no version walk), by content
     * hash, so its text is merged when it differs from the base, its version becomes the base either way (the next
     * publish saves on it), and the draft's local edits are rebased on top. A gone page merges nothing (the delete
     * listener freezes the draft).
     *
     * <p>Caveat: a latest version below the base is also what {@code deleteVersion} of the newest version produces (the
     * page's latest falls back to an older version), so that is read as "recreated" too: the older version's text is
     * merged as one external change with {@code recreated: true}. That is the right text for the draft to follow; only
     * the flag's name is loose.</p>
     */
    private Result belowBase( final String page, final int b, final List< Runnable > completions ) {
        final Optional< PageState > latest = writer.latest( page );
        if( latest.isEmpty() || latest.get().version() >= b ) {
            return Result.NONE;
        }
        return mergeOne( latest.get(), true, completions );
    }

    private boolean sameAsBase( final PageState s ) {
        return s.contentHash().equals( d.record.baseContentHash() )
            && MetaJson.normalise( s.meta() ).equals( MetaJson.normalise( d.record.baseMeta() ) );
    }

    /**
     * One version: {@code E = diff(baseBody, body)}, {@code E' = E.map(local)} appended as a server update,
     * {@code local = local.map(E, before=true)}, meta per field last-writer-wins (theirs when only they changed it, ours
     * with a conflict when both did), committed with a snapshot of the live head; an {@code external-change} event. A
     * {@code recreated} page also makes the session active again and always emits its event.
     */
    private Result mergeOne( final PageState st, final boolean recreated, final List< Runnable > completions ) {
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
            final Draft.State before = d.state();
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
            if( !d.commit( extra, docAfter, localAfter, rebased( st, body, recreated, localAfter, theirs ) ) ) {
                return Result.FAILED;
            }
            if( recreated || !extra.isEmpty() || mm.touched() ) {
                d.feed.emit( "external-change", event( st, ePrime, mm, recreated ) );
            }
            d.announce( before );   // a merge credits nobody, but may change unpublished
            answers.wakeAll( extra, completions );
            return extra.isEmpty() && mm.changes().isEmpty() ? Result.BASE_ONLY : Result.CHANGED;
        } finally {
            lock.unlock();
        }
    }

    /** Under the lock: the record on the merged version's base (active again for a recreated page), with its markers. */
    private SessionRecord rebased( final PageState st, final String body, final boolean recreated,
                                   final ChangeSet localAfter, final Map< String, Object > theirs ) {
        final SessionRecord based = d.record.withBase( st.version(), body, st.meta(), CollabText.contentHash( body ) );
        return SessionLoader.draftMarkers( recreated ? based.withStatus( SessionRecord.ACTIVE ) : based,
            !localAfter.empty() || !d.meta.equals( theirs ), d.lastEditAt );
    }

    /**
     * The {@code external-change} event: the writer's public name ({@code agent} when it has none; never the login), the
     * page version, the collab head right after the server update ({@code version}), the changed ranges in that
     * document's coordinates, the meta fields both sides changed, and {@code recreated: true} for a recreated page.
     * Called under the lock, after the commit.
     */
    private Map< String, Object > event( final PageState st, final ChangeSet ePrime, final MetaMerge.External mm,
                                         final boolean recreated ) {
        final String name = st.author() == null ? null : identity.displayName( st.author() );
        final List< List< Integer > > ranges = new ArrayList<>();
        for( final int[] r : ePrime.touchedRanges() ) {
            ranges.add( List.of( ePrime.mapPos( r[ 0 ], -1 ), ePrime.mapPos( r[ 1 ], 1 ) ) );
        }
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "source", name == null || name.isBlank() ? "agent" : name );
        f.put( "pageVersion", st.version() );
        f.put( "version", d.head );
        f.put( "ranges", List.copyOf( ranges ) );
        f.put( "conflictFields", List.copyOf( mm.conflicts() ) );
        if( recreated ) {
            f.put( "recreated", true );
        }
        return f;
    }
}

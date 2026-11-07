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
package com.wikantik.collab.testing;

import com.wikantik.collab.MetaJson;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Section 13.0's session invariants, checked after every simulated step. A failure is an {@link AssertionError} whose
 * message starts {@code "invariant N (text)"}.
 *
 * <p>Differences worth knowing: invariant 2 tolerates log truncation (the ring need only equal the overlapping suffix of the retained log;
 * every logged update, text or server, consumes a version); invariant 3 compares doc and local by effect (the simulator
 * has no meta yet), needs a snapshot at or below the head on the session's current base page version (creation writes
 * one at version 0 and every base change writes one with it) and the log contiguous from that snapshot to the head; invariants 2 to 4 are skipped for a poisoned stub;
 * invariant 4 on a frozen draft (its page deleted) wants no page at all; invariant 5 uses the oversize ceiling.</p>
 *
 * <p>Phase 2 adds three. <b>Invariant 7</b>: every live participant held {@code edit} at its last check
 * ({@link InvariantView#editGrants()}). <b>Invariant 8</b> (presence, A12): every server-held selection lies within its
 * document and a selection with no unconfirmed updates is at the head (its document is then the head's); at quiescence
 * each live participant's server-held selection equals its client's ({@link #presenceAtQuiescence}). A selection with
 * unconfirmed updates is in coordinates of a document the server may not have yet (the version plus the client's
 * unsent edits), so only its version is checked. <b>Invariant 9</b> (metadata, A14): at quiescence every client displays
 * the server's metadata ({@link #metaAtQuiescence}), and after every step every item an accepted ADD put in a set field
 * is present unless a later REMOVE, SET or server op of that field took it out ({@link #addsSurvive}); the scheduler
 * keeps that ledger.</p>
 */
public final class CollabInvariants {

    private CollabInvariants() {
    }

    public static void check( final InvariantView v ) {
        check( v, false );
    }

    public static void check( final InvariantView v, final boolean checkReplay ) {
        inv1( v );
        if( !v.poisoned() ) {
            inv2( v );
            if( checkReplay ) {
                inv3( v );
            }
            inv4( v );
        }
        inv5( v );
        inv6( v );
        inv7( v );
        inv8( v );
    }

    private static AssertionError fail( final int n, final String text, final String detail ) {
        return new AssertionError( "invariant " + n + " (" + text + ") violated: " + detail );
    }

    private static void inv1( final InvariantView v ) {
        final String text = "local.apply(baseBody) equals doc";
        final String applied;
        try {
            applied = v.local().apply( v.baseBody() );
        } catch( final IllegalArgumentException e ) {
            throw fail( 1, text, "local does not apply to baseBody: " + e.getMessage() );
        }
        if( !applied.equals( v.doc() ) ) {
            throw fail( 1, text, "applied=" + show( applied ) + " doc=" + show( v.doc() ) );
        }
    }

    private static void inv2( final InvariantView v ) {
        final String text = "head matches the store, the retained log is contiguous to head, the ring is its suffix";
        final int stored = v.store().session( v.sessionId() ).map( SessionRecord::headVersion ).orElse( -1 );
        if( stored != v.head() ) {
            throw fail( 2, text, "head=" + v.head() + " store headVersion=" + stored );
        }
        final List< StoredUpdate > log = v.store().updatesSince( v.sessionId(), 0 );
        for( int i = 1; i < log.size(); i++ ) {
            if( log.get( i ).version() != log.get( i - 1 ).version() + 1 ) {
                throw fail( 2, text, "retained log has a gap before version " + log.get( i ).version() );
            }
        }
        if( !log.isEmpty() && log.get( log.size() - 1 ).version() != v.head() ) {
            throw fail( 2, text, "retained log ends at " + log.get( log.size() - 1 ).version() + " but head=" + v.head() );
        }
        final List< StoredUpdate > ring = v.ring();
        for( int i = 1; i < ring.size(); i++ ) {
            if( ring.get( i ).version() != ring.get( i - 1 ).version() + 1 ) {
                throw fail( 2, text, "ring has a gap before version " + ring.get( i ).version() );
            }
        }
        if( !ring.isEmpty() && ring.get( ring.size() - 1 ).version() != v.head() ) {
            throw fail( 2, text, "ring ends at " + ring.get( ring.size() - 1 ).version() + " but head=" + v.head() );
        }
        final Map< Integer, StoredUpdate > byVersion = new HashMap<>();
        log.forEach( u -> byVersion.put( u.version(), u ) );
        final int firstRetained = log.isEmpty() ? Integer.MAX_VALUE : log.get( 0 ).version();
        for( final StoredUpdate r : ring ) {
            if( r.version() >= firstRetained && !r.equals( byVersion.get( r.version() ) ) ) {
                throw fail( 2, text, "ring entry " + r + " differs from the retained log entry " + byVersion.get( r.version() ) );
            }
        }
    }

    private static void inv3( final InvariantView v ) {
        final String text = "replaying snapshot + log reproduces doc and local (by effect)";
        final Optional< Snapshot > snap = v.store().latestSnapshot( v.sessionId() );
        if( snap.isEmpty() ) {
            throw fail( 3, text, "no snapshot in the store" );
        }
        final Snapshot s = snap.get();
        if( s.basePageVersion() != v.basePageVersion() ) {
            // every base change writes its snapshot in the same step (commitBaseChange), and so does creation (v0)
            throw fail( 3, text, "the latest snapshot (version " + s.version() + ") is on base page version "
                + s.basePageVersion() + " but the session is on " + v.basePageVersion() );
        }
        String doc = s.body();
        ChangeSet local = s.local();
        int expected = s.version() + 1;
        try {
            for( final StoredUpdate u : v.store().updatesSince( v.sessionId(), s.version() ) ) {
                if( u.version() != expected ) {
                    throw fail( 3, text, "the log after snapshot " + s.version() + " is not contiguous: version "
                        + u.version() + " where " + expected + " was due" );
                }
                expected++;
                doc = u.changes().apply( doc );
                local = local.compose( u.changes() );
            }
            if( expected - 1 != v.head() ) {
                throw fail( 3, text, "the log after snapshot " + s.version() + " ends at " + ( expected - 1 )
                    + " but head=" + v.head() );
            }
            if( !doc.equals( v.doc() ) ) {
                throw fail( 3, text, "replayed doc=" + show( doc ) + " doc=" + show( v.doc() ) );
            }
            // by effect, not structure: a reload recomputes local with TextDiff (section 7.1)
            if( local.length() != v.baseBody().length() || !local.apply( v.baseBody() ).equals( v.doc() ) ) {
                throw fail( 3, text, "replayed local does not turn baseBody into doc: " + local );
            }
        } catch( final IllegalArgumentException e ) {
            throw fail( 3, text, "replay failed: " + e.getMessage() );
        }
    }

    private static void inv4( final InvariantView v ) {
        if( v.publishPending() || v.externalChangePending() ) {
            return;
        }
        final String text = "basePageVersion equals the writer's latest version";
        if( v.frozen() ) {
            // the page was deleted under the draft: nothing may exist under its name until a recreate unfreezes it
            final Optional< PageState > there = v.writer().latest( v.pageName() );
            if( there.isPresent() ) {
                throw fail( 4, text, "the draft is frozen but page " + v.pageName() + " exists at version "
                    + there.get().version() );
            }
            return;
        }
        final int latest = v.writer().latest( v.pageName() ).map( PageState::version ).orElse(0);
        if( latest != v.basePageVersion() ) {
            throw fail( 4, text, "basePageVersion=" + v.basePageVersion() + " writer latest=" + latest );
        }
    }

    private static void inv5( final InvariantView v ) {
        final long bytes = v.doc().getBytes( StandardCharsets.UTF_8 ).length;
        final String text = "utf8Bytes(doc) <= maxPageBytes unless in oversize state, where it is <= the ceiling";
        if( !v.oversize() && bytes > v.maxPageBytes() ) {
            throw fail( 5, text, bytes + " > " + v.maxPageBytes() );
        }
        if( v.oversize() && bytes > v.oversizeCeiling() ) {
            throw fail( 5, text, "oversize state grew: " + bytes + " > ceiling " + v.oversizeCeiling() );
        }
    }

    private static void inv6( final InvariantView v ) {
        if( CollabText.hasLoneSurrogate( v.doc() ) ) {
            throw fail( 6, "no lone UTF-16 surrogates in doc", "doc=" + show( v.doc() ) );
        }
    }

    private static void inv7( final InvariantView v ) {
        for( final Map.Entry< String, Boolean > e : v.editGrants().entrySet() ) {
            if( !Boolean.TRUE.equals( e.getValue() ) ) {
                throw fail( 7, "every live participant held an edit grant at its last check", "participant " + e.getKey()
                    + " is still live after its last edit check failed" );
            }
        }
    }

    private static final String INV8 = "every server-held selection lies within its document; one with no unconfirmed"
        + " updates is at the head";

    private static void inv8( final InvariantView v ) {
        for( final Map.Entry< String, Selection > e : v.selections().entrySet() ) {
            final Selection s = e.getValue();
            if( s.version() > v.head() ) {
                throw fail( 8, INV8, "participant " + e.getKey() + "'s selection " + s + " is ahead of head " + v.head() );
            }
            if( s.unconfirmed() == 0 ) {
                if( s.version() != v.head() ) {
                    throw fail( 8, INV8, "participant " + e.getKey() + "'s selection " + s + " has no unconfirmed updates"
                        + " but is not at head " + v.head() );
                }
                if( Math.max( s.anchor(), s.head() ) > v.doc().length() ) {
                    throw fail( 8, INV8, "participant " + e.getKey() + "'s selection " + s + " is outside the head document"
                        + " of length " + v.doc().length() );
                }
            }
        }
    }

    /**
     * Invariant 8 at quiescence: each live participant named in {@code clientSelections} (participant id to its client's
     * last selection) holds exactly that selection on the server.
     */
    public static void presenceAtQuiescence( final InvariantView v, final Map< String, Selection > clientSelections ) {
        for( final Map.Entry< String, Selection > e : clientSelections.entrySet() ) {
            final Selection held = v.selections().get( e.getKey() );
            if( !e.getValue().equals( held ) ) {
                throw fail( 8, "at quiescence each live participant's server-held selection equals its client's",
                    "participant " + e.getKey() + ": server holds " + held + ", its client last had " + e.getValue() );
            }
        }
    }

    /** Invariant 9 at quiescence: every client (name to the metadata it displays) displays the server's metadata. */
    public static void metaAtQuiescence( final InvariantView v, final Map< String, Map< String, Object > > displayed ) {
        final Map< String, Object > server = MetaJson.normalise( v.meta() );
        for( final Map.Entry< String, Map< String, Object > > e : displayed.entrySet() ) {
            final Map< String, Object > shown = MetaJson.normalise( e.getValue() );
            if( !server.equals( shown ) ) {
                throw fail( 9, "at quiescence every client displays the server's metadata", "client " + e.getKey()
                    + " displays " + shown + " but the server holds " + server );
            }
        }
    }

    /**
     * Invariant 9's ledger: every item in {@code mustHold} (field to the items an accepted ADD put there and no later
     * REMOVE, SET or server op of that field took out) is in the server's value of that field, compared as strings (a
     * scalar is a one-item list).
     */
    public static void addsSurvive( final InvariantView v, final Map< String, Set< String > > mustHold ) {
        for( final Map.Entry< String, Set< String > > e : mustHold.entrySet() ) {
            final List< String > held = items( v.meta().get( e.getKey() ) );
            for( final String item : e.getValue() ) {
                if( !held.contains( item ) ) {
                    throw fail( 9, "every accepted ADD survives unless a later REMOVE, SET or server op of its field took"
                        + " it out", "item " + show( item ) + " of " + e.getKey() + " is missing: the server holds " + held );
                }
            }
        }
    }

    /** A set field's items as strings: a list item by item, a scalar as one item, absent as none. */
    public static List< String > items( final Object value ) {
        final Object v = MetaJson.normaliseValue( value );
        final List< String > out = new ArrayList<>();
        if( v instanceof List< ? > l ) {
            l.forEach( x -> out.add( Objects.toString( x ) ) );
        } else if( v != null ) {
            out.add( Objects.toString( v ) );
        }
        return out;
    }

    private static String show( final String s ) {
        return s.length() > 80 ? s.substring( 0, 80 ) + "..." : s;
    }
}

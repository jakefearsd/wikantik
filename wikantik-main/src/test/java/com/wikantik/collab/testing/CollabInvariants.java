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

import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Section 13.0's session invariants, checked after every simulated step. A failure is an {@link AssertionError} whose
 * message starts {@code "invariant N (text)"}.
 *
 * <p>Differences worth knowing: invariant 2 tolerates log truncation (the ring need only equal the overlapping suffix of the retained log;
 * every logged update, text or server, consumes a version); invariant 3 compares doc and local by effect (the simulator
 * has no meta yet), needs a snapshot at or below the head on the session's current base page version (creation writes
 * one at version 0 and every base change writes one with it) and the log contiguous from that snapshot to the head; invariants 2 to 4 are skipped for a poisoned stub;
 * invariant 5 uses the oversize ceiling; invariant 7 is a no-op until
 * presence exists (Phase 2).</p>
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
        // invariant 7 (presence participants hold an edit grant): no presence yet, nothing to check
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

    private static String show( final String s ) {
        return s.length() > 80 ? s.substring( 0, 80 ) + "..." : s;
    }
}

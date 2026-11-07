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
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Section 13.0's session invariants, checked after every simulated step. A failure is an {@link AssertionError} whose
 * message starts {@code "invariant N (text)"}.
 *
 * <p>Differences worth knowing: invariant 2 counts every logged update (text and server kinds both consume a
 * version); invariant 3 compares doc and local only (the simulator has no meta yet) and needs a snapshot at or below
 * the head, which the stub writes at creation and at each publish; invariant 5's "size never grows in oversize state"
 * is a push-time rule (asserted in the stub's tests), not checkable from a single view; invariant 7 is a no-op until
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
        inv2( v );
        if( checkReplay ) {
            inv3( v );
        }
        inv4( v );
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
        final String text = "head equals the logged update count and the ring is a contiguous suffix of the log";
        final List< StoredUpdate > log = v.store().updatesSince( v.sessionId(), 0 );
        if( log.size() != v.head() ) {
            throw fail( 2, text, "head=" + v.head() + " store log has " + log.size() + " updates" );
        }
        for( int i = 0; i < log.size(); i++ ) {
            if( log.get( i ).version() != i + 1 ) {
                throw fail( 2, text, "store log is not contiguous from 1 at index " + i );
            }
        }
        final List< StoredUpdate > ring = v.ring();
        if( ring.size() > log.size() ) {
            throw fail( 2, text, "ring (" + ring.size() + ") is longer than the log (" + log.size() + ")" );
        }
        final List< StoredUpdate > suffix = log.subList( log.size() - ring.size(), log.size() );
        if( !suffix.equals( ring ) ) {
            throw fail( 2, text, "ring " + ring + " is not the log suffix " + suffix );
        }
    }

    private static void inv3( final InvariantView v ) {
        final String text = "replaying snapshot + log reproduces doc and local";
        final Optional< Snapshot > snap = v.store().latestSnapshot( v.sessionId() );
        if( snap.isEmpty() ) {
            throw fail( 3, text, "no snapshot in the store" );
        }
        final Snapshot s = snap.get();
        String doc = s.body();
        ChangeSet local = s.local();
        try {
            for( final StoredUpdate u : v.store().updatesSince( v.sessionId(), s.version() ) ) {
                doc = u.changes().apply( doc );
                local = local.compose( u.changes() );
            }
        } catch( final IllegalArgumentException e ) {
            throw fail( 3, text, "replay failed: " + e.getMessage() );
        }
        if( !doc.equals( v.doc() ) ) {
            throw fail( 3, text, "replayed doc=" + show( doc ) + " doc=" + show( v.doc() ) );
        }
        if( !local.equals( v.local() ) ) {
            throw fail( 3, text, "replayed local=" + local + " local=" + v.local() );
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
        if( !v.oversize() && bytes > v.maxPageBytes() ) {
            throw fail( 5, "utf8Bytes(doc) <= maxPageBytes unless in oversize state", bytes + " > " + v.maxPageBytes() );
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

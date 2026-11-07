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

import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.StoredUpdate;

import java.util.List;

/**
 * A12 selection mapping. A selection is in the coordinates of the client's document: the document at its synced
 * {@code version} plus its {@code unconfirmed} local updates. With no unconfirmed updates the server maps it exactly
 * through the updates since {@code version}; otherwise it is kept as sent (rendering, and receiver-side mapping, are
 * Phase 2).
 */
final class Presence {

    private Presence() {
    }

    /**
     * The selection to keep, or null to ignore it (out of range, or a version the session cannot place).
     *
     * @param since   the updates after {@code s.version()} up to the head, or null when they are not in memory
     * @param docLen  the head document's length
     * @param head    the head version
     */
    static Selection map( final Selection s, final List< StoredUpdate > since, final int docLen, final int head ) {
        if( s == null || s.version() > head ) {
            return null;
        }
        if( s.unconfirmed() > 0 ) {
            return s;
        }
        if( s.version() == head ) {
            return inRange( s, docLen ) ? s : null;
        }
        if( since == null || since.isEmpty() || since.get( 0 ).version() != s.version() + 1 ) {
            return null;
        }
        if( !inRange( s, since.get( 0 ).changes().length() ) ) {
            return null;
        }
        int anchor = s.anchor();
        int headPos = s.head();
        for( final StoredUpdate u : since ) {
            anchor = u.changes().mapPos( anchor, -1 );
            headPos = u.changes().mapPos( headPos, -1 );
        }
        return new Selection( anchor, headPos, head, 0 );
    }

    /** A selection sent with a push whose updates were exactly its unconfirmed ones: it now sits at the new head. */
    static Selection afterOwnPush( final Selection s, final int baseVersion, final int accepted, final int docLen,
                                   final int head ) {
        if( s != null && s.version() == baseVersion && s.unconfirmed() == accepted && accepted > 0 ) {
            final Selection at = new Selection( s.anchor(), s.head(), head, 0 );
            return inRange( at, docLen ) ? at : null;
        }
        return null;
    }

    /**
     * Keeps {@code s} as {@code p}'s selection when it can be placed (see {@link #map}); null is ignored. True when the
     * kept selection changed (the presence sequence moves).
     */
    static boolean keep( final Participant p, final Selection s, final UpdateRing ring, final int docLen, final int head ) {
        if( s == null ) {
            return false;
        }
        final List< StoredUpdate > since = s.version() < head && ring.covers( s.version() ) ? ring.after( s.version() ) : null;
        return set( p, map( s, since, docLen, head ) );
    }

    /** Sets a non-null selection; true when it differs from the one kept. */
    static boolean set( final Participant p, final Selection s ) {
        if( s == null || s.equals( p.selection() ) ) {
            return false;
        }
        p.selection( s );
        return true;
    }

    private static boolean inRange( final Selection s, final int len ) {
        return s.anchor() <= len && s.head() <= len;
    }
}

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

import com.wikantik.collab.model.PublishResponse;

import java.time.Instant;

/**
 * The refusals every request meets before it touches the document: the participant must exist (409 {@code rejoin})
 * and belong to the caller's author key (403 {@code not-your-participant}, A6); a push must also be within its rate
 * (429 {@code rate-limited}, A8) and the session not frozen (409 {@code frozen}). Called under the session lock.
 */
final class Gate {

    /** A refusal: the status and reason to answer with. */
    record Refusal( int status, String reason ) {
    }

    private Gate() {
    }

    /** Null when {@code p} exists and is {@code authorKey}'s. */
    static Refusal identity( final Participant p, final String authorKey ) {
        if( p == null ) {
            return new Refusal( 409, CollabSession.REJOIN );
        }
        return p.authorKey.equals( authorKey ) ? null : new Refusal( 403, CollabSession.NOT_YOURS );
    }

    /** Null when the push may proceed; marks the participant seen and takes one push token. */
    static Refusal push( final Participant p, final String authorKey, final Instant now, final int pushesPerSecond,
                         final boolean frozen ) {
        final Refusal who = identity( p, authorKey );
        if( who != null ) {
            return who;
        }
        p.touch( now );
        if( !p.tryAcquirePush( now, pushesPerSecond ) ) {
            return new Refusal( 429, "rate-limited" );
        }
        return frozen ? new Refusal( 409, "frozen" ) : null;
    }

    /** Records that the participant holds {@code version} when it is a real version (0..head). */
    static void confirm( final Participant p, final int version, final int head ) {
        if( version >= 0 && version <= head ) {
            p.confirm( version );
        }
    }

    /**
     * A publish's answer before the save (section 7.3), or null to save: 200 {@code unchanged} with nothing to publish
     * (E21), 400 {@code empty} for an empty draft (E1), 413 {@code oversize} for a draft over the page limit (7.4).
     */
    static PublishResponse publishable( final boolean unpublished, final boolean docEmpty, final boolean oversize,
                                        final int base ) {
        if( !unpublished ) {
            return new PublishResponse( 200, base, null, true );
        }
        if( docEmpty ) {
            return new PublishResponse( 400, base, "empty", false );
        }
        return oversize ? new PublishResponse( 413, base, "oversize", false ) : null;
    }
}

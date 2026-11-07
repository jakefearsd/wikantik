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
import com.wikantik.collab.model.Selection;

import java.time.Duration;
import java.time.Instant;

/**
 * One editor tab in a session (section 7.1): bound to the author key that joined (A6) and to the clientID it was issued.
 * Mutable, guarded by the session lock.
 *
 * <p>{@code confirmed} is the highest version this participant has shown it holds (the {@code since} of a pull or the
 * base version of a push); A10's truncation never cuts above the lowest one. The push limiter is a token bucket of
 * {@code pushesPerSecond} tokens refilled continuously from the injected clock (A8).</p>
 */
final class Participant {

    final String id;
    final String authorKey;
    final String clientId;
    final String displayName;
    final String color;
    final Object wikiSession;
    /** Whether the wiki session was signed in when this participant joined (ruling Q13); set once at join. */
    private boolean signedInAtJoin;
    private Instant lastSeen;
    private int confirmed;
    private Selection selection;
    private double tokens;
    private Instant refilledAt;

    Participant( final String id, final JoinRequest r, final String clientId, final String displayName, final String color,
                 final Instant now, final int confirmed, final int pushesPerSecond ) {
        this.id = id;
        this.authorKey = r.authorKey();
        this.clientId = clientId;
        this.displayName = java.util.Objects.requireNonNull( displayName, "displayName" );
        this.color = color;
        this.wikiSession = r.wikiSession();
        this.lastSeen = now;
        this.confirmed = confirmed;
        this.tokens = pushesPerSecond;
        this.refilledAt = now;
    }

    boolean signedInAtJoin() {
        return signedInAtJoin;
    }

    void signedInAtJoin( final boolean signedIn ) {
        signedInAtJoin = signedIn;
    }

    void touch( final Instant now ) {
        lastSeen = now;
    }

    Instant lastSeen() {
        return lastSeen;
    }

    void confirm( final int version ) {
        confirmed = Math.max( confirmed, version );
    }

    int confirmed() {
        return confirmed;
    }

    Selection selection() {
        return selection;
    }

    void selection( final Selection s ) {
        selection = s;
    }

    /** Takes one push token, refilling first; false when the participant is over its rate. */
    boolean tryAcquirePush( final Instant now, final int pushesPerSecond ) {
        final long nanos = Math.max( 0L, Duration.between( refilledAt, now ).toNanos() );
        tokens = Math.min( pushesPerSecond, tokens + nanos * (double) pushesPerSecond / 1_000_000_000d );
        refilledAt = now;
        if( tokens < 1d ) {
            return false;
        }
        tokens -= 1d;
        return true;
    }
}

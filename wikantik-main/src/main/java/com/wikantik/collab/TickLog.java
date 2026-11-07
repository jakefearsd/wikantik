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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the registry's tick steps so that nothing escapes and a failing step stays quiet (Phase 3 Task 4, tick log
 * hygiene): each step has its own warning clock, so one step's warning never mutes another's, and a step that keeps
 * failing logs once per {@link #EVERY} (DEBUG in between). An {@link Error} is logged at ERROR, also once per window,
 * with a clock of its own per error class: a different error in the same step is logged at once. One instance per
 * session slot, plus one for the registry-wide steps; tick thread only.
 */
final class TickLog {

    private static final Logger LOG = LogManager.getLogger( TickLog.class );
    /** A repeating tick failure is logged at most this often per step. */
    static final Duration EVERY = Duration.ofMinutes( 1 );

    /** When each step (and each error class within a step) last logged above DEBUG. */
    private final Map< String, Instant > lastLogged = new ConcurrentHashMap<>();

    /**
     * Runs step {@code what} of {@code subject} (a session or the registry); a failure is logged as the class comment
     * says and the next tick retries.
     */
    @SuppressWarnings( "PMD.AvoidCatchingThrowable" )
    void run( final String subject, final String what, final Instant now, final Runnable r ) {
        try {
            r.run();
        } catch( final Throwable t ) {
            failed( subject, what, now, t );
        }
    }

    private void failed( final String subject, final String what, final Instant now, final Throwable t ) {
        final boolean error = t instanceof Error;
        if( !due( error ? what + '|' + t.getClass().getName() : what, now ) ) {
            LOG.debug( "{}: tick step '{}' failed again: {}", subject, what, t.toString() );
        } else if( error ) {
            // LOG.error justified: an Error escaped a tick step (stack overflow, out of memory); operator attention
            LOG.error( "{}: tick step '{}' failed with an error; retried next tick (repeats muted for a minute)",
                subject, what, t );
        } else {
            LOG.warn( "{}: tick step '{}' failed; retried next tick (repeats muted for a minute): {}", subject, what,
                t.toString(), t );
        }
    }

    /** True at most once per {@link #EVERY} for {@code key}. */
    private boolean due( final String key, final Instant now ) {
        final Instant last = lastLogged.get( key );
        if( last != null && now.isBefore( last.plus( EVERY ) ) ) {
            return false;
        }
        lastLogged.put( key, now );
        return true;
    }
}

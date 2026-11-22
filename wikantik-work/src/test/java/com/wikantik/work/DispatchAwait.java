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
package com.wikantik.work;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Repeats a dispatch step until the state a test expects is reached, or {@link #LIMIT} passes. The dispatch window
 * ends at the snapshot's xmin, and the test database is shared with other forks whose open transactions hold xmin
 * back, so one call can legitimately deliver nothing. Use it wherever a test asserts that something WAS dispatched;
 * a test asserting something is NOT dispatched while a transaction it holds open blocks it stays a single call.
 */
final class DispatchAwait {

    static final Duration LIMIT = Duration.ofSeconds( 10 );
    private static final long PAUSE_MILLIS = 50;

    /** One dispatch call; returns the number of events it read. */
    @FunctionalInterface
    interface Step {
        int run() throws SQLException;
    }

    /** A condition over the database. */
    @FunctionalInterface
    interface Reached {
        boolean check() throws SQLException;
    }

    private DispatchAwait() {
    }

    /** Calls the step until the events it read add up to at least {@code expected}; returns that sum. */
    static int events( final Step step, final int expected ) throws SQLException {
        final long deadline = System.nanoTime() + LIMIT.toNanos();
        int sum = 0;
        while ( true ) {
            sum += step.run();
            if ( sum >= expected || System.nanoTime() >= deadline ) {
                return sum;
            }
            pause();
        }
    }

    /** Calls the step until the condition holds; fails the test when it does not within the limit. */
    static void until( final Step step, final Reached reached ) throws SQLException {
        final long deadline = System.nanoTime() + LIMIT.toNanos();
        while ( true ) {
            step.run();
            if ( reached.check() ) {
                return;
            }
            if ( System.nanoTime() >= deadline ) {
                throw new AssertionError( "the expected dispatch state was not reached within " + LIMIT );
            }
            pause();
        }
    }

    /**
     * Waits, without dispatching, until the event's transaction is below the snapshot's xmin, so the next dispatch
     * call sees it (a transaction older than the event can no longer appear once none is open).
     */
    static void windowPast( final DataSource ds, final long eventId ) throws SQLException {
        windowClear( ds, "id = " + eventId );
    }

    /** Waits until every event in the log is below the snapshot's xmin. */
    static void windowPastAll( final DataSource ds ) throws SQLException {
        windowClear( ds, "TRUE" );
    }

    private static void windowClear( final DataSource ds, final String where ) throws SQLException {
        final long deadline = System.nanoTime() + LIMIT.toNanos();
        while ( WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE " + where
                + " AND tx >= pg_snapshot_xmin(pg_current_snapshot())" ) > 0 ) {
            if ( System.nanoTime() >= deadline ) {
                throw new AssertionError( "the dispatch window did not pass the event within " + LIMIT );
            }
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep( PAUSE_MILLIS );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new AssertionError( "interrupted while waiting for the dispatch window", e );
        }
    }
}

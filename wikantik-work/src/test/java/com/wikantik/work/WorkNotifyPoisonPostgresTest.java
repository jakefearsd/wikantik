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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A poison event (one that deterministically cannot be planned) is skipped and recorded instead of being retried for
 * ever; a transient failure skips nothing. The (tx, id) cursor stays correct: later events are delivered, nothing
 * twice.
 */
@RequiresPostgres
class WorkNotifyPoisonPostgresTest {

    /** Tells otto of every event; throws {@code failure} for the poison event. */
    private static final class Planner implements WorkNotificationStore.NoticePlanner {
        final Set< Long > poison = new java.util.HashSet<>();
        RuntimeException runtime = new IllegalStateException( "cannot plan this one" );
        SQLException checked;
        final List< Long > skipped = new ArrayList<>();

        @Override
        public Map< String, NoticeReason > plan( final WorkEvent e, final EventFacts facts ) throws SQLException {
            if ( poison.contains( e.id() ) ) {
                if ( checked != null ) {
                    throw checked;
                }
                throw runtime;
            }
            final Map< String, NoticeReason > out = new TreeMap<>();
            out.put( "otto", NoticeReason.WATCHING );
            return out;
        }

        @Override
        public void skipped( final WorkEvent e, final Throwable cause ) {
            skipped.add( e.id() );
        }
    }

    private NotifyFixtures fx;
    private WorkNotificationStore store;
    private Planner planner;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyFixtures();
        PostgresTestDb.truncate( "work_notify_skipped" );
        store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        planner = new Planner();
        assertEquals( 0, store.dispatch( 10, planner ) ); // plants the cursor
    }

    private long[] events( final int n ) throws SQLException {
        final long[] ids = new long[ n ];
        for ( int i = 0; i < n; i++ ) {
            ids[ i ] = fx.event( WorkEvent.EDITED, "bob", null );
        }
        DispatchAwait.windowPastAll( fx.ds );
        return ids;
    }

    private String cursor() throws SQLException {
        return fx.cursorEventId();
    }

    private long noticesFor( final long event ) throws SQLException {
        return fx.count( "work_notification WHERE event_id = " + event );
    }

    @Test
    void aPoisonEventInTheMiddleIsSkippedAndTheRestAreDelivered() throws SQLException {
        final long[] e = events( 3 );
        planner.poison.add( e[ 1 ] );
        assertEquals( 3, store.dispatch( 10, planner ) );
        assertEquals( 1, noticesFor( e[ 0 ] ) );
        assertEquals( 0, noticesFor( e[ 1 ] ) );
        assertEquals( 1, noticesFor( e[ 2 ] ) );
        assertEquals( 1, fx.count( "work_notify_skipped WHERE event_id = " + e[ 1 ]
            + " AND error_class = 'java.lang.IllegalStateException' AND error_message = 'cannot plan this one'" ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ) );
        assertEquals( List.of( e[ 1 ] ), planner.skipped );
        assertEquals( String.valueOf( e[ 2 ] ), cursor() );
    }

    @Test
    void nothingIsDeliveredTwiceAfterASkip() throws SQLException {
        final long[] e = events( 3 );
        planner.poison.add( e[ 0 ] );
        assertEquals( 3, store.dispatch( 10, planner ) );
        assertEquals( 0, store.dispatch( 10, planner ), "the cursor moved past everything" );
        assertEquals( 2, fx.count( "work_notification" ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ) );
        assertEquals( 1, planner.skipped.size() );
    }

    @Test
    void aPoisonEventAtTheEndMovesTheCursorOverIt() throws SQLException {
        final long[] e = events( 2 );
        planner.poison.add( e[ 1 ] );
        assertEquals( 2, store.dispatch( 10, planner ) );
        assertEquals( String.valueOf( e[ 1 ] ), cursor() );
        assertEquals( 1, noticesFor( e[ 0 ] ) );
        assertEquals( 0, store.dispatch( 10, planner ) );
    }

    @Test
    void aSmallBatchStillFallsBackAndNeverGoesPastTheLimit() throws SQLException {
        final long[] e = events( 4 );
        planner.poison.add( e[ 0 ] );
        assertEquals( 2, store.dispatch( 2, planner ), "the batch of two: one skipped, one delivered" );
        assertEquals( String.valueOf( e[ 1 ] ), cursor() );
        assertEquals( 2, store.dispatch( 2, planner ) );
        assertEquals( 3, fx.count( "work_notification" ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ) );
    }

    @Test
    void aLongErrorMessageIsTruncated() throws SQLException {
        final long[] e = events( 1 );
        planner.poison.add( e[ 0 ] );
        planner.runtime = new IllegalStateException( "x".repeat( 5000 ) );
        store.dispatch( 10, planner );
        assertEquals( 500, WorkAgentFixtures.scalar( fx.ds, "SELECT length(error_message) FROM work_notify_skipped" ) );
    }

    @Test
    void aTransientFailureSkipsNothingAndIsRetried() throws SQLException {
        final long[] e = events( 3 );
        planner.poison.add( e[ 1 ] );
        planner.runtime = new WorkUnavailableException( "directory is down" );
        final String before = cursor();
        assertThrows( WorkUnavailableException.class, () -> store.dispatch( 10, planner ) );
        assertEquals( 0, fx.count( "work_notify_skipped" ) );
        assertEquals( 0, fx.count( "work_notification" ), "the batch rolled back whole" );
        assertEquals( before, cursor() );
        assertTrue( planner.skipped.isEmpty() );
        planner.poison.clear();
        assertEquals( 3, store.dispatch( 10, planner ) );
        assertEquals( 3, fx.count( "work_notification" ) );
    }

    @Test
    void aConnectionLevelSqlErrorIsTransient() throws SQLException {
        final long[] e = events( 2 );
        planner.poison.add( e[ 0 ] );
        planner.checked = new SQLException( "connection reset", "08006" );
        assertThrows( SQLException.class, () -> store.dispatch( 10, planner ) );
        assertEquals( 0, fx.count( "work_notify_skipped" ) );
        assertEquals( 0, fx.count( "work_notification" ) );
    }

    @Test
    void aTransientFailureAfterEarlierEventsWereFinishedOneByOneKeepsTheirProgressOnly() throws SQLException {
        final long[] e = events( 3 );
        planner.poison.add( e[ 0 ] );
        // The batch fails on the poison event (skipped alone); the second event then fails transiently.
        final WorkNotificationStore.NoticePlanner flaky = new WorkNotificationStore.NoticePlanner() {
            @Override
            public Map< String, NoticeReason > plan( final WorkEvent ev, final EventFacts f ) throws SQLException {
                if ( ev.id() == e[ 1 ] && planner.skipped.size() == 1 ) {
                    throw new WorkUnavailableException( "directory is down" );
                }
                return planner.plan( ev, f );
            }

            @Override
            public void skipped( final WorkEvent ev, final Throwable cause ) {
                planner.skipped( ev, cause );
            }
        };
        assertThrows( WorkUnavailableException.class, () -> store.dispatch( 10, flaky ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ), "the real poison event was recorded" );
        assertEquals( String.valueOf( e[ 0 ] ), cursor() );
        assertEquals( 2, store.dispatch( 10, planner ), "the two after it are delivered on the retry" );
        assertEquals( 2, fx.count( "work_notification" ) );
    }

    @Test
    void aMessageWithANulCharacterStillGetsRecorded() throws SQLException {
        final long[] e = events( 2 );
        planner.poison.add( e[ 0 ] );
        planner.runtime = new IllegalStateException( "bad\u0000value" );
        assertEquals( 2, store.dispatch( 10, planner ) );
        assertEquals( 1, fx.count( "work_notify_skipped WHERE error_message = 'badvalue'" ) );
    }
}

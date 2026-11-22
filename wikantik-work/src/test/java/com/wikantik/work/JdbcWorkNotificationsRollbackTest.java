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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dispatch step for a batch is one transaction: a fault at any statement leaves rows and cursor untouched. */
@RequiresPostgres
class JdbcWorkNotificationsRollbackTest {

    private static final int MAX_POINTS = 60;

    private NotifyFixtures fx;
    private FaultInjectingDataSource faulty;

    private static final WorkNotificationStore.NoticePlanner MENTIONS = ( e, f ) -> {
        final Map< String, NoticeReason > out = new TreeMap<>();
        f.mentioned().forEach( m -> out.put( m, NoticeReason.MENTIONED ) );
        return out;
    };

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyFixtures();
        faulty = new FaultInjectingDataSource( fx.ds );
    }

    private String cursor() throws SQLException {
        try ( var c = fx.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT tx::text || ':' || event_id FROM work_notify_cursor" ) ) {
            return rs.next() ? rs.getString( 1 ) : "none";
        }
    }

    @Test
    void everyFailurePointLeavesNothing() throws SQLException {
        final WorkNotificationStore clean = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        assertEquals( 0, clean.dispatch( 10, MENTIONS ) );
        final long comment = fx.comment( "carol", false, "otto" );
        final long event = fx.event( WorkEvent.COMMENTED, "carol", String.valueOf( comment ) );
        DispatchAwait.windowPast( fx.ds, event ); // every injected call below must see the event, none may read 0
        final String before = cursor();
        final WorkNotificationStore store = new JdbcWorkStore( faulty ).notifyStores().notifications();
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                assertEquals( 1, store.dispatch( 10, MENTIONS ) );
            } catch ( final RuntimeException expected ) {
                assertEquals( "boom " + n, expected.getMessage() );
                exercised++;
                assertEquals( 0, fx.count( "work_notification" ), "notification rows after failing at " + n );
                assertEquals( 0, fx.count( "work_watch" ), "watch rows after failing at " + n );
                assertEquals( before, cursor(), "cursor after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 5, "failure points exercised: " + exercised );
            assertEquals( 1, fx.count( "work_notification" ) );
            assertEquals( 2, fx.count( "work_watch" ) ); // carol (commenter) and otto (mentioned)
            assertTrue( !before.equals( cursor() ) );
            return;
        }
        throw new AssertionError( "dispatch still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void aFailingPlannerLeavesNothing() throws SQLException {
        final WorkNotificationStore store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        assertEquals( 0, store.dispatch( 10, MENTIONS ) );
        DispatchAwait.windowPast( fx.ds, fx.event( WorkEvent.EDITED, "bob", null ) );
        final String before = cursor();
        assertThrows( IllegalStateException.class, () -> store.dispatch( 10, ( e, f ) -> {
            throw new IllegalStateException( "planner" );
        } ) );
        assertEquals( before, cursor() );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, MENTIONS ), 1 ) );
    }

    @Test
    void markEmailedWithADigestIsAllOrNothing() throws SQLException {
        final long event = fx.event( WorkEvent.EDITED, "bob", null );
        final long note = WorkAgentFixtures.scalar( fx.ds, "INSERT INTO work_notification (recipient, event_id, project_id, "
            + "item_id, reason, created_at) VALUES ('otto', " + event + ", " + fx.project + ", " + fx.item
            + ", 'watching', '2026-03-02T09:59:00Z') RETURNING id" );
        final WorkNotificationStore store = new JdbcWorkStore( faulty ).notifyStores().notifications();
        final java.time.Instant at = java.time.Instant.parse( "2026-03-02T10:00:00Z" );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                store.markEmailed( "otto", java.util.List.of( note ), at, java.time.LocalDate.parse( "2026-03-02" ) );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, fx.count( "work_notification WHERE emailed_at IS NOT NULL" ), "stamped after failing at " + n );
                assertEquals( 0, fx.count( "work_notify_pref WHERE last_digest_on IS NOT NULL" ), "digest after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 2, "failure points exercised: " + exercised );
            assertEquals( 1, fx.count( "work_notification WHERE emailed_at IS NOT NULL" ) );
            assertEquals( 1, fx.count( "work_notify_pref WHERE last_digest_on IS NOT NULL" ) );
            return;
        }
        throw new AssertionError( "markEmailed still failing after " + MAX_POINTS + " statements" );
    }
}

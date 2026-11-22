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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The cursor is the writing transaction's id: an event that commits late is neither skipped nor repeated. */
@RequiresPostgres
class WorkDispatchOrderPostgresTest {

    private NotifyFixtures fx;
    private WorkNotificationStore store;
    private final List< Long > planned = new ArrayList<>();

    private final WorkNotificationStore.NoticePlanner zed = ( e, f ) -> {
        planned.add( e.id() );
        return Map.of( "zed", NoticeReason.WATCHING );
    };

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyFixtures();
        store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        assertEquals( 0, store.dispatch( 10, zed ) );
    }

    private static long insert( final Connection c, final String sql ) throws SQLException {
        try ( Statement s = c.createStatement(); ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    @Test
    void anEventCommittedAfterAHigherIdIsNotSkipped() throws SQLException {
        try ( Connection a = fx.ds.getConnection(); Connection b = fx.ds.getConnection() ) {
            a.setAutoCommit( false );
            final long first = insert( a, fx.eventSql( fx.project, fx.item, WorkEvent.EDITED, "bob", null, null ) );
            final long second = insert( b, fx.eventSql( fx.project, fx.item, WorkEvent.EDITED, "bob", null, null ) );
            assertEquals( true, first < second );
            assertEquals( 0, store.dispatch( 10, zed ) );
            assertEquals( 0, fx.count( "work_notification" ) );
            a.commit();
            assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 10, zed ), 2 ) );
            assertEquals( 2, fx.count( "work_notification" ) );
            assertEquals( List.of( first, second ), planned );
            assertEquals( 0, store.dispatch( 10, zed ) );
            assertEquals( 2, planned.size() );
        }
    }

    @Test
    void aTransactionThatStartedEarlierHoldsTheWindow() throws SQLException {
        try ( Connection a = fx.ds.getConnection(); Connection b = fx.ds.getConnection() ) {
            a.setAutoCommit( false );
            insert( a, "SELECT pg_current_xact_id()::text::bigint" );
            final long second = insert( b, fx.eventSql( fx.project, fx.item, WorkEvent.EDITED, "bob", null, null ) );
            final long first = insert( a, fx.eventSql( fx.project, fx.item, WorkEvent.EDITED, "bob", null, null ) );
            assertEquals( 0, store.dispatch( 10, zed ) );
            a.commit();
            assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 10, zed ), 2 ) );
            assertEquals( 2, fx.count( "work_notification" ) );
            assertEquals( List.of( second, first ), planned );
            assertEquals( 0, store.dispatch( 10, zed ) );
            assertEquals( 2, planned.size() );
        }
    }

    private long event( final Connection c ) throws SQLException {
        return insert( c, fx.eventSql( fx.project, fx.item, WorkEvent.EDITED, "bob", null, null ) );
    }

    @Test
    void oneTransactionOfFiveEventsSplitAcrossBatchesIsDeliveredOnceInOrder() throws SQLException {
        final List< Long > ids = new ArrayList<>();
        try ( Connection a = fx.ds.getConnection() ) {
            a.setAutoCommit( false );
            for ( int i = 0; i < 5; i++ ) {
                ids.add( event( a ) );
            }
            a.commit();
        }
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(DISTINCT tx) FROM work_event" ), "one tx" );
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 2, zed ), 2 ) );
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 2, zed ), 2 ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 2, zed ), 1 ) );
        assertEquals( 0, store.dispatch( 2, zed ) );
        assertEquals( ids, planned );
    }

    @Test
    void anEventWrittenInASavepointCarriesTheTopLevelTransactionAndIsDispatchedOnce() throws SQLException {
        final List< Long > kept = new ArrayList<>();
        try ( Connection a = fx.ds.getConnection(); Statement s = a.createStatement() ) {
            a.setAutoCommit( false );
            kept.add( event( a ) );
            s.execute( "SAVEPOINT one" );
            event( a );
            s.execute( "ROLLBACK TO SAVEPOINT one" );
            s.execute( "SAVEPOINT two" );
            kept.add( event( a ) );
            s.execute( "RELEASE SAVEPOINT two" );
            kept.add( event( a ) );
            a.commit();
        }
        assertEquals( 3, fx.count( "work_event" ) );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(DISTINCT tx) FROM work_event" ) );
        assertEquals( 3, DispatchAwait.events( () -> store.dispatch( 10, zed ), 3 ) );
        assertEquals( 0, store.dispatch( 10, zed ) );
        assertEquals( kept, planned );
    }

    @Test
    void aBatchSizeBelowOneIsRefused() {
        assertThrows( IllegalArgumentException.class, () -> store.dispatch( 0, zed ) );
        assertThrows( IllegalArgumentException.class, () -> store.dispatch( -3, zed ) );
    }

    @Test
    void anOlderOpenTransactionAtTheFirstDispatchDoesNotReplayHistory() throws SQLException {
        // a fresh log: the setUp planted an empty one, so reset it to make "first dispatch" real
        WorkAgentFixtures.exec( fx.ds, "DELETE FROM work_notify_cursor" );
        try ( Connection old = fx.ds.getConnection() ) {
            old.setAutoCommit( false );
            insert( old, "SELECT pg_current_xact_id()::text::bigint" );
            fx.event( WorkEvent.EDITED, "bob", null );
            fx.event( WorkEvent.COMMENTED, "carol", null );
            fx.event( WorkEvent.STATUS, "bob", "done" );
            assertEquals( 0, store.dispatch( 10, zed ), "the first dispatch only plants the cursor" );
            final long fresh = fx.event( WorkEvent.EDITED, "bob", null );
            old.commit();
            assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
            assertEquals( List.of( fresh ), planned );
        }
    }

    @Test
    void anEmptyLogPlantsAtTheWindowAndTheFirstLaterEventIsDispatched() throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "DELETE FROM work_notify_cursor" );
        assertEquals( 0, fx.count( "work_event" ) );
        assertEquals( 0, store.dispatch( 10, zed ) );
        final long first = fx.event( WorkEvent.EDITED, "bob", null );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( List.of( first ), planned );
    }
}

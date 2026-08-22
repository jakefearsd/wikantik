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
package com.wikantik.connectors.state;

import com.wikantik.connectors.SyncReport;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class JdbcSyncRunStoreTest {

    private DataSource ds;

    @BeforeEach void schema() {
        this.ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "connector_sync_run" );
    }

    @Test void startFinishListRoundTrip() {
        final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
        final long id = store.start( "c1", "manual" );
        List< SyncRunRow > runs = store.list( "c1", 10 );
        assertEquals( 1, runs.size() );
        assertEquals( "running", runs.get( 0 ).status() );
        assertNull( runs.get( 0 ).finished() );
        store.finish( id, new SyncReport( 3, 1, 2, 0, 1 ) );
        runs = store.list( "c1", 10 );
        assertEquals( "ok", runs.get( 0 ).status() );
        assertEquals( 3, runs.get( 0 ).created() );
        assertEquals( 1, runs.get( 0 ).failed() );
        assertNotNull( runs.get( 0 ).finished() );
    }

    @Test void failRecordsErrorText() {
        final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
        final long id = store.start( "c1", "scheduled" );
        store.fail( id, "boom: connection refused" );
        assertEquals( "failed", store.list( "c1", 1 ).get( 0 ).status() );
        assertEquals( "boom: connection refused", store.list( "c1", 1 ).get( 0 ).error() );
    }

    @Test void listIsNewestFirstAndLimited() {
        final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
        for ( int i = 0; i < 5; i++ ) store.finish( store.start( "c1", "manual" ), new SyncReport( i, 0, 0, 0, 0 ) );
        final List< SyncRunRow > two = store.list( "c1", 2 );
        assertEquals( 2, two.size() );
        assertTrue( two.get( 0 ).runId() > two.get( 1 ).runId() );
    }

    @Test void startPrunesBeyond100PerConnector() {
        final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
        for ( int i = 0; i < 105; i++ ) store.finish( store.start( "c1", "manual" ), new SyncReport( 0, 0, 0, 0, 0 ) );
        assertEquals( 100, store.list( "c1", 1000 ).size() );
    }

    @Test void purgeRunsRemovesOnlyThatConnectorsHistory() {
        final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
        store.finish( store.start( "c1", "manual" ), new SyncReport( 1, 0, 0, 0, 0 ) );
        store.finish( store.start( "c1", "scheduled" ), new SyncReport( 0, 1, 0, 0, 0 ) );
        store.finish( store.start( "c2", "manual" ), new SyncReport( 0, 0, 1, 0, 0 ) );

        store.purgeRuns( "c1" );

        assertTrue( store.list( "c1", 10 ).isEmpty(), "purged connector must have no run history" );
        assertEquals( 1, store.list( "c2", 10 ).size(), "other connectors' history must be untouched" );
    }
}

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

import com.wikantik.api.connectors.SyncCursor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSyncStateStoreTest {

    private DataSource ds;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:connstate;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
            // idempotency mirror of the migration convention: re-running CREATE ... IF NOT EXISTS is a no-op
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
        }
    }

    @Test void cursorRoundTrips() {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        assertTrue( store.loadCursor( "c1" ).isEmpty() );
        store.saveCursor( "c1", new SyncCursor( "cur-1" ) );
        assertEquals( "cur-1", store.loadCursor( "c1" ).orElseThrow().value() );
        store.saveCursor( "c1", new SyncCursor( "cur-2" ) );        // upsert
        assertEquals( "cur-2", store.loadCursor( "c1" ).orElseThrow().value() );
    }

    @Test void recordSyncedThenHashKnownUrisPageNameAndRemove() {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        store.recordSynced( "c1", "file:a.md", "hashA", "PageA", List.of( "group:docs", "user:jo" ) );
        assertEquals( Optional.of( "hashA" ), store.syncedHash( "c1", "file:a.md" ) );
        assertEquals( Optional.of( "PageA" ), store.pageNameFor( "c1", "file:a.md" ) );
        assertEquals( List.of( "file:a.md" ), store.knownUris( "c1" ) );
        store.recordSynced( "c1", "file:a.md", "hashA2", "PageA", List.of() );   // upsert hash
        assertEquals( Optional.of( "hashA2" ), store.syncedHash( "c1", "file:a.md" ) );
        store.removeSynced( "c1", "file:a.md" );
        assertTrue( store.syncedHash( "c1", "file:a.md" ).isEmpty() );
        assertTrue( store.knownUris( "c1" ).isEmpty() );
    }

    @Test void aclRefsRoundTripAsJsonArrayString() throws Exception {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        store.recordSynced( "c1", "file:a.md", "h", "PageA", List.of( "group:docs", "user:jo" ) );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery(
                  "SELECT acl_refs FROM connector_synced_item WHERE source_uri='file:a.md'" ) ) {
            rs.next();
            assertEquals( "[\"group:docs\",\"user:jo\"]", rs.getString( 1 ) );   // DoD #4: carried
        }
    }
}

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
package com.wikantik.connectors.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorStatusReaderTest {

    private DataSource ds;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:connstatus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
            // The named in-memory DB (DB_CLOSE_DELAY=-1) persists across both @Test methods in this
            // class within the same JVM, so reset rows before each test to keep @BeforeEach idempotent.
            s.execute( "DELETE FROM connector_synced_item" );
            s.execute( "DELETE FROM connector_sync_state" );
            s.execute( "INSERT INTO connector_sync_state (connector_id, cursor, last_run, status) VALUES ('fs1','cur','2026-07-11T10:00:00Z','ok')" );
            s.execute( "INSERT INTO connector_synced_item (connector_id, source_uri, content_hash, page_name) VALUES ('fs1','file:a.md','h','A'),('fs1','file:b.md','h2','B')" );
        }
    }

    @Test void readsLastRunStatusAndItemCount() {
        ConnectorStatus st = new ConnectorStatusReader( ds ).read( "fs1", "filesystem" );
        assertEquals( "fs1", st.connectorId() );
        assertEquals( "filesystem", st.connectorType() );
        assertEquals( "ok", st.lastStatus() );
        assertNotNull( st.lastRun() );
        assertEquals( 2, st.syncedItemCount() );
    }

    @Test void neverRunConnectorHasNullsAndZeroCount() {
        ConnectorStatus st = new ConnectorStatusReader( ds ).read( "never", "filesystem" );
        assertNull( st.lastRun() );
        assertNull( st.lastStatus() );
        assertEquals( 0, st.syncedItemCount() );
    }
}

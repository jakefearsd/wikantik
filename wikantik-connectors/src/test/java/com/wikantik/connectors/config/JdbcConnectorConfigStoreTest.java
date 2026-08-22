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
package com.wikantik.connectors.config;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class JdbcConnectorConfigStoreTest {

    private DataSource ds;

    @BeforeEach void schema() {
        this.ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "connector_configs" );
    }

    @Test void upsertGetListDeleteRoundTrip() {
        final JdbcConnectorConfigStore store = new JdbcConnectorConfigStore( ds );
        assertTrue( store.list().isEmpty() );
        final ConnectorConfigRow row = new ConnectorConfigRow( "gh-notes", "github", true, 24,
            "{\"repo\":\"jake/notes\"}", "Engineering", "notes,github", "Gh" );
        store.upsert( row );
        assertEquals( row, store.get( "gh-notes" ).orElseThrow() );
        store.upsert( new ConnectorConfigRow( "gh-notes", "github", false, 12,
            "{\"repo\":\"jake/notes\",\"branch\":\"main\"}", null, null, null ) );   // upsert overwrites
        assertFalse( store.get( "gh-notes" ).orElseThrow().enabled() );
        assertEquals( 1, store.list().size() );
        store.delete( "gh-notes" );
        assertTrue( store.get( "gh-notes" ).isEmpty() );
    }

    @Test void listOrdersById() {
        final JdbcConnectorConfigStore store = new JdbcConnectorConfigStore( ds );
        store.upsert( new ConnectorConfigRow( "b", "feed", true, 0, "{}", null, null, null ) );
        store.upsert( new ConnectorConfigRow( "a", "feed", true, 0, "{}", null, null, null ) );
        assertEquals( List.of( "a", "b" ), store.list().stream().map( ConnectorConfigRow::connectorId ).toList() );
    }
}

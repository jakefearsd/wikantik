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
package com.wikantik.pagegraph.spine;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class TrustedAuthorsDaoTest {

    private DataSource ds;
    private TrustedAuthorsDao dao;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "trusted_authors" );
        this.dao = new TrustedAuthorsDao( ds );
    }

    @Test
    void empty_table_yields_empty_set() {
        assertTrue( dao.all().isEmpty() );
        assertFalse( dao.contains( "alice" ) );
    }

    @Test
    void upsert_adds_to_cache() {
        dao.upsert( "alice", "primary maintainer" );
        assertTrue( dao.contains( "alice" ) );
        assertEquals( 1, dao.all().size() );
    }

    @Test
    void upsert_idempotent() {
        dao.upsert( "alice", "first" );
        dao.upsert( "alice", "second" );
        assertEquals( 1, dao.all().size() );
    }

    @Test
    void remove_drops_from_cache() {
        dao.upsert( "alice", null );
        dao.remove( "alice" );
        assertFalse( dao.contains( "alice" ) );
    }

    @Test
    void contains_is_blank_safe() {
        assertFalse( dao.contains( null ) );
        assertFalse( dao.contains( "" ) );
        assertFalse( dao.contains( "   " ) );
    }

    @Test
    void refresh_picks_up_external_writes() throws Exception {
        // Bypass the DAO and write directly so we know refresh() works.
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement( "INSERT INTO trusted_authors (login_name, notes) VALUES (?, ?)" ) ) {
            ps.setString( 1, "bob" );
            ps.setString( 2, null );
            ps.executeUpdate();
        }
        assertFalse( dao.contains( "bob" ), "cache is stale before refresh" );
        dao.refresh();
        assertTrue( dao.contains( "bob" ) );
    }
}

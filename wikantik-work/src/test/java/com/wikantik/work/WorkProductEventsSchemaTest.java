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

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins V083: the record of changes to a product's settings. */
@RequiresPostgres
class WorkProductEventsSchemaTest {

    private DataSource ds;
    private long product;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        product = scalar( "INSERT INTO work_product (key, name) VALUES ('wikantik', 'Wikantik') RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private static String v083() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V083__work_product_events.sql" ) );
    }

    @Test
    void anEventDefaultsToAUserAndNeedsAProduct() throws SQLException {
        exec( "INSERT INTO work_product_event (product_id, actor, field, from_value, to_value) VALUES (" + product
            + ", 'alice', 'deploy_gate', 'human', 'agent')" );
        assertEquals( 1, scalar( "SELECT count(*) FROM work_product_event WHERE actor_kind = 'user' AND agent_id IS NULL" ) );
        assertEquals( "23503", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_product_event (product_id, actor, field) VALUES (999, 'alice', 'name')" ) ).getSQLState() );
    }

    @Test
    void theHistoryIndexExists() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_product_event_product'" ) );
    }

    /** The test container's role is a superuser, so the append-only grant is pinned on the migration text. */
    @Test
    void theAppRoleCanOnlyAppendAndRead() throws Exception {
        final List< String > grants = Arrays.stream( v083().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of( "GRANT SELECT, INSERT ON work_product_event TO :app_user" ), grants );
    }

    @Test
    void v083IsANoOpWhenReapplied() throws Exception {
        final String sql = v083().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
    }
}

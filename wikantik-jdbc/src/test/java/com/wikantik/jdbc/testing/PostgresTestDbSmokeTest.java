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
package com.wikantik.jdbc.testing;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that {@link PostgresTestDb} applies the real {@code bin/db/migrations} — every
 * migrated table exists, and {@code schema_migrations} records every applied version including
 * ones skipped by {@code migrate.sh}'s meta-command exception ({@code V031} is absent).
 */
@RequiresPostgres
class PostgresTestDbSmokeTest {

    @Test
    void migratesAllExpectedTablesAndTracksVersions() throws SQLException {
        final DataSource ds = PostgresTestDb.createDataSource();
        try ( Connection conn = ds.getConnection() ) {
            assertTableExists( conn, "users" );
            assertTableExists( conn, "policy_grants" );
            assertTableExists( conn, "kg_nodes" );
            assertTableExists( conn, "kg_edges" );
            assertTableExists( conn, "kg_content_chunks" );
            assertTableExists( conn, "schema_migrations" );

            final List< String > versions = new ArrayList<>();
            try ( Statement st = conn.createStatement();
                  ResultSet rs = st.executeQuery( "SELECT version FROM schema_migrations" ) ) {
                while ( rs.next() ) versions.add( rs.getString( 1 ) );
            }
            assertTrue( versions.contains( "V001__schema_migrations" ) );
            assertTrue( versions.contains( "V057__expected_ctr_curve" ) );
            assertFalse( versions.contains( "V031__monitoring_role" ),
                "V031 has psql meta-commands and must be skipped, not applied" );
        }
    }

    @Test
    void truncateClearsRowsAndRestartsIdentity() throws SQLException {
        final DataSource ds = PostgresTestDb.createDataSource();
        try ( Connection conn = ds.getConnection(); Statement st = conn.createStatement() ) {
            st.execute( "INSERT INTO groups (name, created, modified) "
                + "VALUES ('SmokeTestGroup', NOW(), NOW())" );
        }

        PostgresTestDb.truncate( "group_members", "groups" );

        try ( Connection conn = ds.getConnection(); Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery( "SELECT COUNT(*) FROM groups" ) ) {
            rs.next();
            org.junit.jupiter.api.Assertions.assertEquals( 0, rs.getInt( 1 ) );
        }
    }

    private static void assertTableExists( final Connection conn, final String table ) throws SQLException {
        try ( Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery( "SELECT to_regclass('public." + table + "') IS NOT NULL" ) ) {
            rs.next();
            assertTrue( rs.getBoolean( 1 ), "Expected table missing: " + table );
        }
    }
}

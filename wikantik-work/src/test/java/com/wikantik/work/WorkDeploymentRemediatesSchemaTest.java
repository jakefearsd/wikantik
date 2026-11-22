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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins V090: {@code remediates_id} on deployments and the read-time indexes of the flow and DORA figures. */
@RequiresPostgres
class WorkDeploymentRemediatesSchemaTest {

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

    private long deployment() throws SQLException {
        return scalar( "INSERT INTO work_deployment (product_id, version, requested_by) VALUES (" + product
            + ", '2.5.26', 'alice') RETURNING id" );
    }

    private static String v090() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V090__work_flow_metrics.sql" ) );
    }

    private static String code( final String sql ) {
        return sql.replaceAll( "(?m)^--.*$", "" );
    }

    @Test
    void remediatesIdIsANullableReference() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM information_schema.columns WHERE table_name = 'work_deployment' "
            + "AND column_name = 'remediates_id' AND data_type = 'bigint' AND is_nullable = 'YES'" ) );
        final long d = deployment();
        assertEquals( 1, scalar( "SELECT count(*) FROM work_deployment WHERE id = " + d + " AND remediates_id IS NULL" ) );
    }

    @Test
    void anUnknownTargetIsRefused() throws SQLException {
        final long d = deployment();
        assertEquals( "23503", assertThrows( SQLException.class,
            () -> exec( "UPDATE work_deployment SET remediates_id = 999999 WHERE id = " + d ) ).getSQLState() );
    }

    @Test
    void aDeploymentCannotRemediateItself() throws SQLException {
        final long d = deployment();
        assertEquals( "23514", assertThrows( SQLException.class,
            () -> exec( "UPDATE work_deployment SET remediates_id = id WHERE id = " + d ) ).getSQLState() );
    }

    @Test
    void severalDeploymentsMayRemediateOne() throws SQLException {
        final long bad = deployment();
        exec( "UPDATE work_deployment SET remediates_id = " + bad + " WHERE id = " + deployment() );
        exec( "UPDATE work_deployment SET remediates_id = " + bad + " WHERE id = " + deployment() );
    }

    @Test
    void theThreeReadIndexesArePartial() throws SQLException {
        for ( final String index : List.of( "idx_work_event_moves", "idx_work_deployment_verified",
            "idx_work_change_merged", "idx_work_deployment_remediates" ) ) {
            assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + index
                + "' AND indexdef LIKE '% WHERE %'" ), index );
        }
    }

    /** I-1: the flow reads status moves of every route, so the index covers exactly the types that can carry one. */
    @Test
    void theMovesIndexCoversEveryTypeThatCanMoveAStatus() throws SQLException {
        assertEquals( 0, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_event_status'" ),
            "the status-only draft index is gone" );
        for ( final String type : StatusMoves.TYPES ) {
            assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_event_moves' "
                + "AND indexdef LIKE '%''" + type + "''%'" ), type );
        }
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_event_moves' "
            + "AND indexdef NOT LIKE '%commented%'" ) );
    }

    @Test
    void v090ReplacesTheDraftIndexWhenReapplied() throws Exception {
        final String before = schema();
        exec( "CREATE INDEX IF NOT EXISTS idx_work_event_status ON work_event (item_id, at) WHERE type = 'status'" );
        exec( v090().replace( ":app_user", "CURRENT_USER" ) );
        assertEquals( before, schema() );
    }

    @Test
    void v090IsANoOpWhenReapplied() throws Exception {
        final String before = schema();
        final String sql = v090().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        assertEquals( before, schema() );
        deployment();
    }

    /** Columns, constraints and indexes of the tables V090 touches, as one comparable text. */
    private String schema() throws SQLException {
        final StringBuilder out = new StringBuilder();
        try ( var c = ds.getConnection(); var st = c.createStatement();
              var rs = st.executeQuery( "SELECT 'col ' || table_name || '.' || column_name || ' ' || data_type FROM "
                  + "information_schema.columns WHERE table_name IN ('work_deployment','work_event','work_change') "
                  + "UNION ALL SELECT 'con ' || conname || ' ' || pg_get_constraintdef( oid ) FROM pg_constraint "
                  + "WHERE conrelid = 'work_deployment'::regclass "
                  + "UNION ALL SELECT 'idx ' || indexdef FROM pg_indexes WHERE tablename IN "
                  + "('work_deployment','work_event','work_change') ORDER BY 1" ) ) {
            while ( rs.next() ) {
                out.append( rs.getString( 1 ) ).append( '\n' );
            }
        }
        return out.toString();
    }

    @Test
    void v090AddsNoTableAndNoGrant() throws Exception {
        final String text = code( v090() ).toUpperCase();
        assertFalse( text.contains( "CREATE " + "TABLE" ) );
        assertFalse( text.contains( "GRANT" ) );
        assertTrue( text.contains( "REMEDIATES_ID" ) );
    }
}

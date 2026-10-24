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
package com.wikantik.knowledge.querylog;

import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the real {@code briefing_log} DDL (V044 + the V064 tier columns) accepts the service's
 * twelve-column insert on actual Postgres — the mock-based {@link JdbcBriefingLogServiceTest} cannot
 * catch a column-name typo or a missing migration.
 */
@RequiresPostgres
class JdbcBriefingLogServicePostgresTest {

    /** Same-thread executor so the fire-and-forget write is deterministic in tests. */
    private static final Executor INLINE = Runnable::run;

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "briefing_log" );
    }

    @Test
    void log_persistsTierCountsAndScopeMode_onRealPostgres() throws Exception {
        new JdbcBriefingLogService( ds, true, INLINE ).log( new BriefingLogEntry(
            "PageA", "clusterX", true, 6000, 4200, 5, 2, 1, "api_briefing", 3, 4, "strict" ) );

        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(
                  "SELECT pin_count, pointer_count, cited_count, elsewhere_count, scope_mode, surface "
                  + "FROM briefing_log" ) ) {
            assertTrue( rs.next(), "row written to real Postgres" );
            assertEquals( 2, rs.getInt( "pin_count" ) );
            assertEquals( 1, rs.getInt( "pointer_count" ) );
            assertEquals( 3, rs.getInt( "cited_count" ) );
            assertEquals( 4, rs.getInt( "elsewhere_count" ) );
            assertEquals( "strict", rs.getString( "scope_mode" ) );
            assertEquals( "api_briefing", rs.getString( "surface" ) );
            assertFalse( rs.next(), "exactly one row" );
        }
    }

    @Test
    void log_nineArgEntry_storesZeroCountsAndNullScopeMode_onRealPostgres() throws Exception {
        new JdbcBriefingLogService( ds, true, INLINE ).log( new BriefingLogEntry(
            "PageA", null, false, 6000, 0, 0, 1, 0, "mcp_get_briefing" ) );

        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT cited_count, elsewhere_count, scope_mode FROM briefing_log" ) ) {
            assertTrue( rs.next(), "row written to real Postgres" );
            assertEquals( 0, rs.getInt( "cited_count" ) );
            assertEquals( 0, rs.getInt( "elsewhere_count" ) );
            assertNull( rs.getString( "scope_mode" ) );
        }
    }
}

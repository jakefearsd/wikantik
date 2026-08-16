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

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.SourceSurface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the real {@code retrieval_query_log} DDL (BIGSERIAL id + NOW() default + the wire
 * tokens) accepts the service's insert on actual Postgres — the H2 unit test can't validate
 * the Postgres-specific column types.
 */
@Testcontainers( disabledWithoutDocker = true )
class JdbcQueryLogServicePostgresTest {

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestContainer.createDataSource();
    }

    @Test
    void log_insertsRow_onRealPostgres() throws Exception {
        final String unique = "pg-rql-" + System.nanoTime();
        new JdbcQueryLogService( ds, true, Runnable::run )
            .log( unique, ActorType.AGENT, SourceSurface.TOOLS_SEARCH_WIKI, 7 );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT actor_type, source_surface, result_count, created_at "
                  + "FROM retrieval_query_log WHERE query_text = ?" ) ) {
            ps.setString( 1, unique );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "row written to real Postgres" );
                assertEquals( "agent", rs.getString( "actor_type" ) );
                assertEquals( "tools_search_wiki", rs.getString( "source_surface" ) );
                assertEquals( 7, rs.getInt( "result_count" ) );
                assertTrue( rs.getTimestamp( "created_at" ) != null, "created_at defaulted by NOW()" );
            }
        }
    }

    @Test
    void log_sixArgForm_insertsCoverageAndSessionHash_onRealPostgres() throws Exception {
        final String unique = "pg-rql-cov-" + System.nanoTime();
        new JdbcQueryLogService( ds, true, Runnable::run )
            .log( unique, ActorType.AGENT, SourceSurface.MCP_ASSEMBLE_BUNDLE, 0, "weak", "sesh-abc123" );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT coverage, session_hash FROM retrieval_query_log WHERE query_text = ?" ) ) {
            ps.setString( 1, unique );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "row written to real Postgres" );
                assertEquals( "weak", rs.getString( "coverage" ) );
                assertEquals( "sesh-abc123", rs.getString( "session_hash" ) );
            }
        }
    }

    // --- recordClick: the UPDATE-with-scalar-subquery syntax is Postgres-specific enough that it
    //     needs a real container, not H2 (see JdbcQueryLogServiceTest for the H2-safe dispatch/
    //     guard-clause coverage). --------------------------------------------------------------

    @Test
    void recordClick_updatesClickedRank_onRealPostgres() throws Exception {
        // session_hash is VARCHAR(16) (a truncated HMAC per D8) — keep test fixtures within that;
        // the row is already made unique by the nanoTime-suffixed query text below.
        final String session = "sesh-click";
        final String query = "pg-rql-click-" + System.nanoTime();
        insertRow( query, session, "2026-08-16T10:00:00Z" );

        new JdbcQueryLogService( ds, true, Runnable::run ).recordClick( session, query, 4 );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT clicked_rank FROM retrieval_query_log WHERE query_text = ? AND session_hash = ?" ) ) {
            ps.setString( 1, query );
            ps.setString( 2, session );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next() );
                assertEquals( 4, rs.getInt( "clicked_rank" ) );
            }
        }
    }

    @Test
    void recordClick_updatesOnlyTheMostRecentMatchingRow_onRealPostgres() throws Exception {
        // Two rows for the same session + query text (a repeat search) at different times — the
        // click must land on the newer one, never silently pick whichever the planner returns first.
        final String session = "sesh-repeat";
        final String query = "pg-rql-repeat-" + System.nanoTime();
        insertRow( query, session, "2026-08-14T09:00:00Z" );   // older
        insertRow( query, session, "2026-08-16T09:00:00Z" );   // newer

        new JdbcQueryLogService( ds, true, Runnable::run ).recordClick( session, query, 2 );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT created_at, clicked_rank FROM retrieval_query_log "
                  + "WHERE query_text = ? AND session_hash = ? ORDER BY created_at" ) ) {
            ps.setString( 1, query );
            ps.setString( 2, session );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "older row present" );
                rs.getInt( "clicked_rank" );
                assertTrue( rs.wasNull(), "the older row must be untouched" );
                assertTrue( rs.next(), "newer row present" );
                assertEquals( 2, rs.getInt( "clicked_rank" ), "the click must land on the newer row" );
            }
        }
    }

    @Test
    void recordClick_noMatchingRow_isNoOp_onRealPostgres() throws Exception {
        // An unrelated row exists, but not for this session/query pair. The click must not fall
        // back to updating it — asserting the unrelated row is untouched is what makes this test
        // meaningful, rather than merely asserting the call didn't throw.
        final String unrelatedQuery = "pg-rql-unrelated-" + System.nanoTime();
        insertRow( unrelatedQuery, "other-session", "2026-08-16T09:00:00Z" );

        new JdbcQueryLogService( ds, true, Runnable::run )
            .recordClick( "no-such-sesh", "no-such-query", 1 );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT clicked_rank FROM retrieval_query_log WHERE query_text = ?" ) ) {
            ps.setString( 1, unrelatedQuery );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next() );
                rs.getInt( "clicked_rank" );
                assertTrue( rs.wasNull(), "an unmatched recordClick must not touch an unrelated row" );
            }
        }
    }

    /** Inserts a row with an explicit {@code created_at}, bypassing the service so recordClick's
     *  "most recent" ordering can be tested deterministically instead of racing wall-clock NOW(). */
    private void insertRow( final String query, final String sessionHash, final String createdAtIso ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO retrieval_query_log "
                  + "(query_text, actor_type, source_surface, result_count, session_hash, created_at) "
                  + "VALUES (?, 'human', 'api_search', 1, ?, ?::timestamptz)" ) ) {
            ps.setString( 1, query );
            ps.setString( 2, sessionHash );
            ps.setString( 3, createdAtIso );
            ps.executeUpdate();
        }
    }
}

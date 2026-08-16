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
package com.wikantik.insights;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL tests for {@link JdbcInsightsStore}'s upsert semantics. H2 does not implement
 * {@code ON CONFLICT}, so these run against an actual PostgreSQL container instead of H2 — see
 * {@link JdbcInsightsStoreTest} for the tests that don't depend on {@code ON CONFLICT} and can
 * stay on H2.
 *
 * <p>wikantik-insights deliberately does not depend on wikantik-main, so this class starts its
 * own container rather than reusing {@code com.wikantik.PostgresTestContainer}.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class JdbcInsightsStorePostgresTest {

    @Container
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
            DockerImageName.parse( "pgvector/pgvector:pg17" ).asCompatibleSubstituteFor( "postgres" ) )
            .withDatabaseName( "wikantik_insights_test" )
            .withUsername( "test" )
            .withPassword( "test" );

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        final PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl( CONTAINER.getJdbcUrl() );
        pg.setUser( CONTAINER.getUsername() );
        pg.setPassword( CONTAINER.getPassword() );
        this.ds = pg;

        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DROP TABLE IF EXISTS search_visibility_snapshot" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS search_visibility_snapshot (
                    snapshot_date DATE        NOT NULL,
                    window_days   SMALLINT    NOT NULL,
                    engine        TEXT        NOT NULL,
                    site_host     TEXT        NOT NULL,
                    page_path     TEXT        NOT NULL,
                    query_text    TEXT        NOT NULL,
                    impressions   INTEGER     NOT NULL,
                    clicks        INTEGER     NOT NULL,
                    position      NUMERIC(6,2),
                    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text)
                )""" );
        }
    }

    private VisibilityRow row( final int impressions, final int clicks, final Double position ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 14 ), 28, "bing",
                "wiki.wikantik.com", "/wiki/PhilosophyHub", "", impressions, clicks, position );
    }

    @Test
    void upsertWritesRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertEquals( 1, store.upsert( List.of( row( 100, 4, 5.2 ) ) ) );
    }

    @Test
    void reSendingAWindowConvergesInsteadOfDuplicating() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );

        store.upsert( List.of( row( 100, 4, 5.2 ) ) );
        store.upsert( List.of( row( 120, 7, 4.8 ) ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n, MAX(impressions) i, MAX(clicks) k, MAX(position) p "
                  + "FROM search_visibility_snapshot" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1, rs.getInt( "n" ),
                    "re-shipping a snapshot must converge — backfill and the nightly run "
                    + "are the same code path, so duplicates would double-count every history load" );
            assertEquals( 120, rs.getInt( "i" ) );
            assertEquals( 7, rs.getInt( "k" ) );
            assertEquals( 4.8, rs.getDouble( "p" ), 0.001 );
        }
    }

    @Test
    void pageRollupAndQueryRowsCoexistForTheSamePage() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );

        // query_text "" is the page-level rollup; a query row for the same snapshot must not
        // collide with it, or page totals would be overwritten by whichever arrived last.
        store.upsert( List.of(
                row( 100, 4, 5.2 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 14 ), 28, "bing", "wiki.wikantik.com",
                        "", "philosophy hub", 3, 1, 4.5 ) ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n FROM search_visibility_snapshot" ) ) {
            assertTrue( rs.next() );
            assertEquals( 2, rs.getInt( "n" ) );
        }
    }

    @Test
    void nullPositionIsStoredRatherThanCoercedToZero() throws Exception {
        // Bing omits position for some rows. Coercing null to 0 would place the page at the
        // best possible rank and make every CTR-vs-position rule fire on it.
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( row( 10, 0, null ) ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT position FROM search_visibility_snapshot" ) ) {
            assertTrue( rs.next() );
            rs.getBigDecimal( "position" );
            assertTrue( rs.wasNull(), "a missing position must stay NULL, never 0" );
        }
    }

    // --- read path (A5): these queries back /admin/insights/acquisition -------------------

    private VisibilityRow rollup( final String engine, final String date, final int impressions,
                                  final int clicks, final Double position ) {
        return new VisibilityRow( LocalDate.parse( date ), 28, engine, "wiki.wikantik.com",
                "/wiki/A", "", impressions, clicks, position );
    }

    @Test
    void engineTotalsReadsOnlyPageRollupRowsNotQueryRows() {
        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "bing", "2026-08-14", 108, 2, 4.7 ),
                // a query row for the same snapshot must NOT be summed into the page totals:
                // engines omit low-volume queries, so query rows undercount the true total.
                new VisibilityRow( LocalDate.parse( "2026-08-14" ), 28, "bing",
                        "wiki.wikantik.com", "", "philosophy hub", 999, 99, 3.0 ) ) );

        final List< EngineTotal > totals =
                store.engineTotals( "wiki.wikantik.com", LocalDate.parse( "2026-08-14" ) );

        assertEquals( 1, totals.size() );
        assertEquals( 108, totals.get( 0 ).impressions() );
        assertEquals( 2, totals.get( 0 ).clicks() );
    }

    @Test
    void engineWithNoPageRowsIsOmittedNotZeroFilled() {
        // Yandex emits no by_page rows at all. A zero-filled row would read as "ranked, no
        // clicks" when the truth is "this engine reports no page data" — a different claim.
        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-08-14", 2518, 8, 36.0 ),
                new VisibilityRow( LocalDate.parse( "2026-08-14" ), 28, "yandex",
                        "wiki.wikantik.com", "", "some query", 45, 0, 5.6 ) ) );

        final List< String > engines =
                store.engineTotals( "wiki.wikantik.com", LocalDate.parse( "2026-08-14" ) )
                     .stream().map( EngineTotal::engine ).toList();

        assertEquals( List.of( "google" ), engines );
    }

    @Test
    void nullPositionsAreExcludedFromTheAverageRatherThanCountedAsZero() {
        // Averaging a NULL position as 0 would report rank 1 — the best possible position —
        // for an engine that reported no position at all, and fire every CTR-vs-rank rule.
        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "bing", "2026-08-14", 10, 1, 4.0 ),
                new VisibilityRow( LocalDate.parse( "2026-08-14" ), 28, "bing",
                        "wiki.wikantik.com", "/wiki/B", "", 10, 0, null ) ) );

        final EngineTotal t = store.engineTotals( "wiki.wikantik.com",
                LocalDate.parse( "2026-08-14" ) ).get( 0 );

        assertNotNull( t.position() );
        assertEquals( 4.0, t.position(), 0.001,
                "the NULL-position row must be excluded from the average, not averaged as 0" );
    }

    @Test
    void latestSnapshotDatePicksTheNewestAndTrendRespectsItsWindow() {
        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-06-04", 100, 1, 40.0 ),
                rollup( "google", "2026-08-14", 200, 8, 36.0 ) ) );

        assertEquals( LocalDate.parse( "2026-08-14" ),
                store.latestSnapshotDate( "wiki.wikantik.com" ).orElseThrow() );

        assertEquals( 2, store.trend( "wiki.wikantik.com", LocalDate.parse( "2026-06-01" ) ).size() );
        assertEquals( 1, store.trend( "wiki.wikantik.com", LocalDate.parse( "2026-07-01" ) ).size(),
                "the trend window must exclude snapshots older than the cutoff" );
    }

    @Test
    void latestSnapshotDateIsEmptyForAnUnknownSite() {
        final JdbcInsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( rollup( "google", "2026-08-14", 1, 0, 1.0 ) ) );
        assertTrue( store.latestSnapshotDate( "nope.example.com" ).isEmpty() );
    }
}

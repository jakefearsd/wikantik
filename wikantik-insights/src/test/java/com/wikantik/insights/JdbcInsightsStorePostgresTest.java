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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL tests for {@link JdbcInsightsStore}'s upsert semantics. H2 does not implement
 * {@code ON CONFLICT}, so these run against an actual PostgreSQL container instead of H2 — see
 * {@link JdbcInsightsStoreTest} for the tests that don't depend on {@code ON CONFLICT} and can
 * stay on H2.
 *
 * <p>wikantik-insights deliberately does not depend on wikantik-main, so this class starts its
 * own container rather than reusing {@code com.wikantik.jdbc.testing.PostgresTestDb}.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class JdbcInsightsStorePostgresTest {

    /** Fixed instants so these assertions never depend on the calendar date the suite runs on.
     *
     *  <p>{@code content_change_log.applied_at} defaults to {@code NOW()}, and
     *  {@code unevaluatedChanges(cutoff)} selects rows at or before the cutoff. Comparing a
     *  NOW()-stamped row against a hardcoded cutoff makes a test that passes on the day it is
     *  written and fails every day after — which is exactly what happened here. Pin both sides.</p> */
    private static final String APPLIED_AT = "2026-07-01T00:00:00Z";
    private static final LocalDate CUTOFF = LocalDate.parse( "2026-07-15" );

    /** Force every recorded change to {@link #APPLIED_AT}, overriding the NOW() default. */
    private void pinAppliedAt() {
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "UPDATE content_change_log SET applied_at = '" + APPLIED_AT + "'" );
        } catch ( final Exception e ) {
            throw new IllegalStateException( "could not pin applied_at", e );
        }
    }

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

            // V052 / V053, applied verbatim so the ON CONFLICT / RETURNING SQL under test runs
            // against the real schema rather than a hand-simplified stand-in.
            st.execute( "DROP TABLE IF EXISTS content_change_log" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS content_change_log (
                    id                    BIGSERIAL   PRIMARY KEY,
                    page_path             TEXT        NOT NULL,
                    change_type           TEXT        NOT NULL,
                    opportunity_type      TEXT,
                    applied_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    applied_by            TEXT        NOT NULL,
                    note                  TEXT,

                    baseline_start        DATE        NOT NULL,
                    baseline_end          DATE        NOT NULL,
                    baseline_impressions  INTEGER     NOT NULL,
                    baseline_clicks       INTEGER     NOT NULL,
                    baseline_ctr          NUMERIC(8,5),
                    baseline_position     NUMERIC(6,2),

                    evaluated_at          TIMESTAMPTZ,
                    effect                TEXT,
                    effect_ctr_delta      NUMERIC(8,5),
                    effect_position_delta NUMERIC(6,2),
                    effect_detail         JSONB,

                    -- V055
                    predicted_priority    NUMERIC(10,4),
                    effect_click_delta    NUMERIC(10,2),
                    effect_method         TEXT
                )""" );

            st.execute( "DROP TABLE IF EXISTS content_opportunity_snooze" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS content_opportunity_snooze (
                    opportunity_type TEXT        NOT NULL,
                    target           TEXT        NOT NULL,
                    snoozed_until    DATE        NOT NULL,
                    reason           TEXT        NOT NULL,
                    snoozed_by       TEXT        NOT NULL,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (opportunity_type, target)
                )""" );

            // V054
            st.execute( "DROP TABLE IF EXISTS content_opportunity_seen" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS content_opportunity_seen (
                    opportunity_type TEXT NOT NULL,
                    target           TEXT NOT NULL,
                    first_seen       DATE NOT NULL,
                    last_seen        DATE NOT NULL,
                    PRIMARY KEY (opportunity_type, target)
                )""" );

            // V041 + V051, applied verbatim so demandRows() runs against the real schema.
            st.execute( "DROP TABLE IF EXISTS retrieval_query_log" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS retrieval_query_log (
                    id             BIGSERIAL   PRIMARY KEY,
                    query_text     TEXT        NOT NULL,
                    actor_type     TEXT        NOT NULL,
                    source_surface TEXT        NOT NULL,
                    result_count   INTEGER,
                    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    session_hash   VARCHAR(16),
                    clicked_rank   INTEGER,
                    coverage       TEXT
                )""" );

            // V056
            st.execute( "DROP TABLE IF EXISTS imported_opportunity" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS imported_opportunity (
                    as_of            DATE           NOT NULL,
                    engine           TEXT           NOT NULL,
                    site_host        TEXT           NOT NULL,
                    opportunity_type TEXT           NOT NULL,
                    target           TEXT           NOT NULL,
                    expected_uplift  NUMERIC(10,2)  NOT NULL,
                    confidence       NUMERIC(4,3),
                    evidence         JSONB,
                    PRIMARY KEY (as_of, engine, site_host, opportunity_type, target)
                )""" );

            // V057
            st.execute( "DROP TABLE IF EXISTS expected_ctr_curve" );
            st.execute( """
                CREATE TABLE IF NOT EXISTS expected_ctr_curve (
                    as_of    DATE     NOT NULL,
                    position SMALLINT NOT NULL,
                    ctr      NUMERIC(6,5) NOT NULL,
                    PRIMARY KEY (as_of, position)
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

    // --- content_change_log (V052) ----------------------------------------------------------

    private ContentChange change( final String opportunityType, final String note,
                                  final Double baselineCtr, final Double baselinePosition ) {
        return change( opportunityType, note, baselineCtr, baselinePosition, null );
    }

    private ContentChange change( final String opportunityType, final String note,
                                  final Double baselineCtr, final Double baselinePosition,
                                  final Double predictedPriority ) {
        return new ContentChange( "/wiki/A", "title", opportunityType, "testbot", note,
                LocalDate.parse( "2026-07-01" ), LocalDate.parse( "2026-07-28" ),
                500, 20, baselineCtr, baselinePosition, predictedPriority );
    }

    @Test
    void recordChangeReturnsAGeneratedId() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final Optional<Long> id = store.recordChange( change( "agent_gap", "curated KG relations", 0.04, 12.5 ) );
        assertTrue( id.isPresent() );
        assertTrue( id.get() > 0 );
    }

    @Test
    void recordChangeStoresNullableFieldsAsNull() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long id = store.recordChange( change( null, null, null, null ) ).orElseThrow();

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT opportunity_type, note, baseline_ctr, baseline_position "
                  + "FROM content_change_log WHERE id = " + id ) ) {
            assertTrue( rs.next() );
            rs.getString( "opportunity_type" );
            assertTrue( rs.wasNull(), "manual changes (no motivating rule) must store a NULL opportunity_type" );
            rs.getString( "note" );
            assertTrue( rs.wasNull() );
            rs.getBigDecimal( "baseline_ctr" );
            assertTrue( rs.wasNull(), "0-impression baselines must store a NULL ctr, never 0" );
            rs.getBigDecimal( "baseline_position" );
            assertTrue( rs.wasNull() );
        }
    }

    @Test
    void unevaluatedChangesExcludesRowsAlreadyEvaluated() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long evaluatedId = store.recordChange( change( "agent_gap", "a", 0.04, 12.5 ) ).orElseThrow();
        store.recordChange( change( "agent_gap", "b", 0.05, 11.0 ) );

        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            // applied_at defaults to NOW(). Pin it to a fixed past instant so the assertion does
            // not depend on the calendar date the suite happens to run on.
            st.execute( "UPDATE content_change_log SET applied_at = '" + APPLIED_AT + "'" );
            st.execute( "UPDATE content_change_log SET evaluated_at = NOW(), effect = 'no_effect' "
                      + "WHERE id = " + evaluatedId );
        }

        final List<PendingChange> pending = store.unevaluatedChanges( CUTOFF );

        assertEquals( 1, pending.size() );
        assertEquals( "b", pending.get( 0 ).note() );
    }

    @Test
    void unevaluatedChangesExcludesRowsAppliedAfterTheCutoff() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long futureId = store.recordChange( change( "agent_gap", "future", 0.04, 12.5 ) ).orElseThrow();

        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "UPDATE content_change_log SET applied_at = '2026-09-01T00:00:00Z' "
                      + "WHERE id = " + futureId );
        }

        final List<PendingChange> pending = store.unevaluatedChanges( CUTOFF );

        assertTrue( pending.isEmpty(),
                "a change applied after the cutoff has not had 28 days to accumulate an after-window" );
    }

    @Test
    void unevaluatedChangesReturnsFullBaselineAndMetadata() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.recordChange( change( "engine_divergence", "diagnostic only", 0.031, 22.4 ) );

        pinAppliedAt();

        final PendingChange pending = store.unevaluatedChanges( CUTOFF ).get( 0 );

        assertEquals( "/wiki/A", pending.pagePath() );
        assertEquals( "title", pending.changeType() );
        assertEquals( "engine_divergence", pending.opportunityType() );
        assertEquals( "testbot", pending.appliedBy() );
        assertEquals( 500, pending.baselineImpressions() );
        assertEquals( 20, pending.baselineClicks() );
        assertEquals( 0.031, pending.baselineCtr(), 0.0001 );
        assertEquals( 22.4, pending.baselinePosition(), 0.0001 );
        assertEquals( LocalDate.parse( "2026-07-01" ), pending.baselineStart() );
        assertEquals( LocalDate.parse( "2026-07-28" ), pending.baselineEnd() );
    }

    // --- content_opportunity_snooze (V053) ----------------------------------------------------

    @Test
    void snoozeWritesARow() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final boolean written = store.snooze( new OpportunitySnooze( "engine_divergence", "/wiki/A",
                LocalDate.parse( "2026-09-01" ), "authority gap, not a page problem", "jake" ) );
        assertTrue( written );
    }

    @Test
    void reSnoozingTheSamePairConvergesInsteadOfDuplicating() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.snooze( new OpportunitySnooze( "agent_gap", "philosophy of mind",
                LocalDate.parse( "2026-09-01" ), "first reason", "jake" ) );
        store.snooze( new OpportunitySnooze( "agent_gap", "philosophy of mind",
                LocalDate.parse( "2026-10-15" ), "revised reason", "jake" ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n, MAX(snoozed_until) u, MAX(reason) r FROM content_opportunity_snooze" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1, rs.getInt( "n" ) );
            assertEquals( LocalDate.parse( "2026-10-15" ), rs.getDate( "u" ).toLocalDate() );
            assertEquals( "revised reason", rs.getString( "r" ) );
        }
    }

    @Test
    void activeSnoozesExcludesExpiredEntries() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.snooze( new OpportunitySnooze( "agent_gap", "expired query",
                LocalDate.parse( "2026-08-01" ), "past", "jake" ) );
        store.snooze( new OpportunitySnooze( "agent_gap", "active query",
                LocalDate.parse( "2026-09-01" ), "future", "jake" ) );

        final List<OpportunitySnooze> active = store.activeSnoozes( LocalDate.parse( "2026-08-16" ) );

        assertEquals( 1, active.size() );
        assertEquals( "active query", active.get( 0 ).target() );
    }

    @Test
    void activeSnoozesIncludesTheExpiryDayItself() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.snooze( new OpportunitySnooze( "agent_gap", "expires today",
                LocalDate.parse( "2026-08-16" ), "reason", "jake" ) );

        final List<OpportunitySnooze> active = store.activeSnoozes( LocalDate.parse( "2026-08-16" ) );

        assertEquals( 1, active.size(), "snoozed_until is inclusive" );
    }

    // --- latestRowsPerEngine / siteImpressions28d --------------------------------------------

    @Test
    void latestRowsPerEnginePicksADifferentLatestDatePerEngine() {
        // Engines poll on independent cadences. If this collapsed to one shared date, whichever
        // engine's cadence lagged would vanish from the comparison entirely.
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-07-20", 50, 1, 20.0 ),
                rollup( "google", "2026-08-14", 200, 8, 15.0 ),
                rollup( "bing", "2026-07-25", 30, 0, 25.0 ),
                rollup( "bing", "2026-08-10", 90, 3, 18.0 ) ) );

        final List<VisibilityRow> rows = store.latestRowsPerEngine( "wiki.wikantik.com", 28 );

        final List<VisibilityRow> googleRows = rows.stream()
                .filter( r -> r.engine().equals( "google" ) ).toList();
        final List<VisibilityRow> bingRows = rows.stream()
                .filter( r -> r.engine().equals( "bing" ) ).toList();

        assertEquals( 1, googleRows.size() );
        assertEquals( LocalDate.parse( "2026-08-14" ), googleRows.get( 0 ).snapshotDate() );
        assertEquals( 1, bingRows.size() );
        assertEquals( LocalDate.parse( "2026-08-10" ), bingRows.get( 0 ).snapshotDate(),
                "bing's own latest date must be used, not google's" );
    }

    @Test
    void latestRowsPerEngineDropsAnEngineStalerThanWithinDays() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-08-14", 200, 8, 15.0 ),
                // yandex's latest snapshot is 40 days behind google's -- outside a 28-day window.
                rollup( "yandex", "2026-07-05", 10, 0, 40.0 ) ) );

        final List<VisibilityRow> rows = store.latestRowsPerEngine( "wiki.wikantik.com", 28 );

        assertEquals( List.of( "google" ), rows.stream().map( VisibilityRow::engine ).distinct().toList() );
    }

    @Test
    void latestRowsPerEngineIncludesBothRollupAndQueryRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-08-14", 200, 8, 15.0 ),
                new VisibilityRow( LocalDate.parse( "2026-08-14" ), 28, "google", "wiki.wikantik.com",
                        "", "some query", 40, 2, 12.0 ) ) );

        final List<VisibilityRow> rows = store.latestRowsPerEngine( "wiki.wikantik.com", 28 );

        assertEquals( 2, rows.size() );
        assertTrue( rows.stream().anyMatch( r -> r.queryText().isEmpty() ) );
        assertTrue( rows.stream().anyMatch( r -> r.queryText().equals( "some query" ) ) );
    }

    @Test
    void siteImpressions28dIgnoresQueryRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", "2026-08-14", 200, 8, 15.0 ),
                rollup( "bing", "2026-08-14", 100, 4, 20.0 ),
                new VisibilityRow( LocalDate.parse( "2026-08-14" ), 28, "google", "wiki.wikantik.com",
                        "", "some query", 999, 99, 3.0 ) ) );

        assertEquals( 300, store.siteImpressions28d( "wiki.wikantik.com" ),
                "the query row's 999 impressions must not be summed into the page total" );
    }

    @Test
    void siteImpressions28dIsZeroForAnUnknownSite() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( rollup( "google", "2026-08-14", 200, 8, 15.0 ) ) );
        assertEquals( 0, store.siteImpressions28d( "nope.example.com" ) );
    }

    // --- demandRows (retrieval_query_log) -----------------------------------------------------

    private void insertQueryLogRow( final String queryText, final String sessionHash,
                                    final Integer resultCount, final String coverage ) throws Exception {
        try ( Connection c = ds.getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO retrieval_query_log "
                  + "(query_text, actor_type, source_surface, result_count, session_hash, coverage) "
                  + "VALUES (?,?,?,?,?,?)" ) ) {
            ps.setString( 1, queryText );
            ps.setString( 2, "human" );
            ps.setString( 3, "api_bundle" );
            if ( resultCount == null ) {
                ps.setNull( 4, java.sql.Types.INTEGER );
            } else {
                ps.setInt( 4, resultCount );
            }
            ps.setString( 5, sessionHash );
            ps.setString( 6, coverage );
            ps.executeUpdate();
        }
    }

    @Test
    void demandRowsCountsDistinctSessionsNotRawOccurrences() throws Exception {
        // Two calls from the same session must count once toward distinctSessions but still
        // twice toward occurrences -- collapsing them would understate real repeat demand.
        insertQueryLogRow( "philosophy of mind", "sess-1", 3, "weak" );
        insertQueryLogRow( "philosophy of mind", "sess-1", 5, "weak" );
        insertQueryLogRow( "philosophy of mind", "sess-2", 0, "weak" );

        final InsightsStore store = new JdbcInsightsStore( ds );
        final DemandRow row = store.demandRows( 28 ).get( 0 );

        assertEquals( "philosophy of mind", row.queryText() );
        assertEquals( 3, row.occurrences() );
        assertEquals( 2, row.distinctSessions() );
        assertEquals( 3, row.resultCount(), "rounded average of 3, 5, 0" );
        assertEquals( "weak", row.coverage() );
    }

    @Test
    void demandRowsPicksTheMostFrequentCoverageValue() throws Exception {
        insertQueryLogRow( "graph databases", "sess-1", 10, "strong" );
        insertQueryLogRow( "graph databases", "sess-2", 10, "strong" );
        insertQueryLogRow( "graph databases", "sess-3", 1, "weak" );

        final InsightsStore store = new JdbcInsightsStore( ds );
        final DemandRow row = store.demandRows( 28 ).get( 0 );

        assertEquals( "strong", row.coverage() );
    }

    @Test
    void demandRowsExcludesRowsOutsideTheWindow() throws Exception {
        insertQueryLogRow( "recent query", "sess-1", 5, "strong" );
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "INSERT INTO retrieval_query_log (query_text, actor_type, source_surface, "
                      + "result_count, created_at) VALUES ('stale query', 'human', 'api_bundle', 5, "
                      + "NOW() - INTERVAL '90 days')" );
        }

        final InsightsStore store = new JdbcInsightsStore( ds );
        final List<String> queries = store.demandRows( 28 ).stream()
                .map( DemandRow::queryText ).toList();

        assertEquals( List.of( "recent query" ), queries );
    }

    // --- lastChangeByTarget --------------------------------------------------------------------

    @Test
    void lastChangeByTargetReturnsTheMostRecentDatePerPage() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.recordChange( change( "agent_gap", "first", 0.04, 12.5 ) );
        store.recordChange( change( "agent_gap", "second", 0.04, 12.5 ) );

        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "UPDATE content_change_log SET applied_at = '2026-07-01T00:00:00Z' "
                      + "WHERE note = 'first'" );
            st.execute( "UPDATE content_change_log SET applied_at = '2026-07-15T00:00:00Z' "
                      + "WHERE note = 'second'" );
        }

        final Map<String, LocalDate> byTarget = store.lastChangeByTarget();

        assertEquals( LocalDate.parse( "2026-07-15" ), byTarget.get( "/wiki/A" ) );
    }

    // --- recordEffect ----------------------------------------------------------------------

    @Test
    void recordEffectRoundTripsAllSixFields() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long id = store.recordChange( change( "agent_gap", "n", 0.04, 12.5, 0.62 ) ).orElseThrow();

        final boolean updated = store.recordEffect( id, "positive", 0.012, -3.5, 8.0,
                "page_rollup", "{\"note\":\"looks good\"}" );

        assertTrue( updated );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT evaluated_at, effect, effect_ctr_delta, effect_position_delta, "
                  + "effect_click_delta, effect_method, effect_detail FROM content_change_log "
                  + "WHERE id = " + id ) ) {
            assertTrue( rs.next() );
            assertTrue( rs.getTimestamp( "evaluated_at" ) != null, "evaluated_at must be set" );
            assertEquals( "positive", rs.getString( "effect" ) );
            assertEquals( 0.012, rs.getDouble( "effect_ctr_delta" ), 0.0001 );
            assertEquals( -3.5, rs.getDouble( "effect_position_delta" ), 0.0001 );
            assertEquals( 8.0, rs.getDouble( "effect_click_delta" ), 0.0001 );
            assertEquals( "page_rollup", rs.getString( "effect_method" ) );
            assertEquals( "{\"note\": \"looks good\"}", rs.getString( "effect_detail" ) );
        }
    }

    @Test
    void recordEffectHandlesNullDetailJson() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long id = store.recordChange( change( "agent_gap", "n", 0.04, 12.5 ) ).orElseThrow();

        final boolean updated = store.recordEffect( id, "no_effect", null, null, null, null, null );

        assertTrue( updated );
    }

    @Test
    void recordEffectReturnsFalseForAnUnknownId() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertFalse( store.recordEffect( 999999L, "positive", 0.01, -1.0, 5.0, "page_rollup", null ) );
    }

    // --- verdictCountsByType / calibrationSamples -----------------------------------------

    @Test
    void verdictCountsByTypeExcludesInsufficientDataAndUnevaluatedRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long evaluated = store.recordChange( change( "agent_gap", "a", 0.04, 12.5 ) ).orElseThrow();
        final long insufficient = store.recordChange( change( "agent_gap", "b", 0.04, 12.5 ) ).orElseThrow();
        store.recordChange( change( "agent_gap", "c", 0.04, 12.5 ) ); // left unevaluated

        store.recordEffect( evaluated, "positive", 0.01, -1.0, 5.0, "page_rollup", null );
        store.recordEffect( insufficient, "insufficient_data", null, null, null, null, null );

        final Map<String, Integer> counts = store.verdictCountsByType();

        assertEquals( Map.of( "agent_gap", 1 ), counts );
    }

    @Test
    void calibrationSamplesOnlyReturnsRowsWithBothAPredictionAndAnOutcome() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final long withPrediction =
                store.recordChange( change( "agent_gap", "a", 0.04, 12.5, 0.75 ) ).orElseThrow();
        final long withoutPrediction =
                store.recordChange( change( "agent_gap", "b", 0.04, 12.5, null ) ).orElseThrow();

        store.recordEffect( withPrediction, "positive", 0.01, -1.0, 6.0, "page_rollup", null );
        store.recordEffect( withoutPrediction, "positive", 0.01, -1.0, 6.0, "page_rollup", null );

        final List<CalibrationSample> samples = store.calibrationSamples();

        assertEquals( 1, samples.size() );
        assertEquals( "agent_gap", samples.get( 0 ).opportunityType() );
        assertEquals( 0.75, samples.get( 0 ).predictedPriority(), 0.0001 );
        assertEquals( 6.0, samples.get( 0 ).realizedClickDelta(), 0.0001 );
    }

    // --- upsertSeen (content_opportunity_seen, V054) ------------------------------------------

    @Test
    void upsertSeenPreservesFirstSeenAcrossASecondCallOnALaterDay() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final List<String[]> pairs =
                List.<String[]>of( new String[] { "agent_gap", "philosophy of mind" } );

        final Map<String, LocalDate> first =
                store.upsertSeen( pairs, LocalDate.parse( "2026-08-01" ) );
        assertEquals( LocalDate.parse( "2026-08-01" ), first.get( "agent_gap philosophy of mind" ) );

        final Map<String, LocalDate> second =
                store.upsertSeen( pairs, LocalDate.parse( "2026-08-16" ) );
        assertEquals( LocalDate.parse( "2026-08-01" ), second.get( "agent_gap philosophy of mind" ),
                "first_seen must survive a later upsert, not reset to today" );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT first_seen, last_seen FROM content_opportunity_seen "
                  + "WHERE opportunity_type = 'agent_gap' AND target = 'philosophy of mind'" ) ) {
            assertTrue( rs.next() );
            assertEquals( LocalDate.parse( "2026-08-01" ), rs.getDate( "first_seen" ).toLocalDate() );
            assertEquals( LocalDate.parse( "2026-08-16" ), rs.getDate( "last_seen" ).toLocalDate(),
                    "last_seen must advance to the later call's date" );
        }
    }

    @Test
    void upsertSeenWritesMultiplePairsInOneCall() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final List<String[]> pairs = List.of(
                new String[] { "agent_gap", "target-a" },
                new String[] { "engine_divergence", "target-b" } );

        final Map<String, LocalDate> result = store.upsertSeen( pairs, LocalDate.parse( "2026-08-16" ) );

        assertEquals( 2, result.size() );
        assertEquals( LocalDate.parse( "2026-08-16" ), result.get( "agent_gap target-a" ) );
        assertEquals( LocalDate.parse( "2026-08-16" ), result.get( "engine_divergence target-b" ) );
    }

    @Test
    void upsertSeenReturnsEmptyMapForAnEmptyInput() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertTrue( store.upsertSeen( List.of(), LocalDate.parse( "2026-08-16" ) ).isEmpty() );
    }

    // --- pageWindowNear / siteWindowNear (design §7.4.2/§7.4.3) -------------------------------
    //
    // THE CRITICAL SEMANTIC under test: search_visibility_snapshot rows are ALREADY trailing
    // 28-day aggregates stamped with their window's END date. "The 28 days after a change" is
    // therefore ONE snapshot nearest the target date, never a sum across snapshot dates. Every
    // test below gives the candidate snapshots clearly different values so a summing bug cannot
    // pass by accident.

    private static final LocalDate D = LocalDate.parse( "2026-07-01" );

    @Test
    void pageWindowNearPicksTheSingleNearestSnapshotNeverSummingAcrossDates() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", D.plusDays( 21 ).toString(), 500, 50, 10.0 ),
                rollup( "google", D.plusDays( 28 ).toString(), 111, 22, 5.0 ),
                rollup( "google", D.plusDays( 35 ).toString(), 900, 90, 20.0 ) ) );

        final PageWindow window = store.pageWindowNear( "wiki.wikantik.com", "/wiki/A",
                D.plusDays( 28 ), 7 ).orElseThrow();

        assertEquals( D.plusDays( 28 ), window.snapshotDate() );
        assertEquals( 111, window.impressions(),
                "must be the D+28 snapshot alone -- 500+111+900=1511 would mean a summing bug" );
        assertEquals( 22, window.clicks() );
        assertEquals( 5.0, window.position(), 0.0001 );
    }

    @Test
    void pageWindowNearBreaksATieOnEqualDistanceByPickingTheEarlierDate() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", D.plusDays( 26 ).toString(), 100, 10, 8.0 ),
                rollup( "google", D.plusDays( 30 ).toString(), 200, 20, 9.0 ) ) );

        final PageWindow window = store.pageWindowNear( "wiki.wikantik.com", "/wiki/A",
                D.plusDays( 28 ), 7 ).orElseThrow();

        assertEquals( D.plusDays( 26 ), window.snapshotDate(),
                "D+26 and D+30 are equidistant from D+28 -- the earlier date must win" );
        assertEquals( 100, window.impressions() );
    }

    @Test
    void pageWindowNearIsEmptyWhenNoSnapshotFallsInTolerance() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( rollup( "google", D.plusDays( 40 ).toString(), 100, 10, 8.0 ) ) );

        assertTrue( store.pageWindowNear( "wiki.wikantik.com", "/wiki/A", D.plusDays( 28 ), 7 ).isEmpty(),
                "D+40 is 12 days from the D+28 target, outside a 7-day tolerance" );
    }

    @Test
    void pageWindowNearIsEmptyWhenOnlyAQueryRowExistsInTolerance() {
        // A query row in range is not a rollup row -- the page has no page-level total there.
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( new VisibilityRow( D.plusDays( 28 ), 28, "google", "wiki.wikantik.com",
                "/wiki/A", "some query", 999, 99, 1.0 ) ) );

        assertTrue( store.pageWindowNear( "wiki.wikantik.com", "/wiki/A", D.plusDays( 28 ), 7 ).isEmpty() );
    }

    @Test
    void pageWindowNearComputesImpressionWeightedPositionAcrossEngines() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", D.toString(), 100, 5, 10.0 ),
                rollup( "bing", D.toString(), 300, 15, 30.0 ) ) );

        final PageWindow window = store.pageWindowNear( "wiki.wikantik.com", "/wiki/A", D, 3 ).orElseThrow();

        assertEquals( 400, window.impressions() );
        assertEquals( 20, window.clicks() );
        assertEquals( 25.0, window.position(), 0.0001,
                "(100*10 + 300*30) / (100+300) = 25.0" );
    }

    @Test
    void pageWindowNearSkipsNullPositionsInTheWeightedAverage() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", D.toString(), 100, 5, 10.0 ),
                // Yandex-shaped: reports impressions/clicks but never a position.
                rollup( "yandex", D.toString(), 900, 1, null ) ) );

        final PageWindow window = store.pageWindowNear( "wiki.wikantik.com", "/wiki/A", D, 3 ).orElseThrow();

        assertEquals( 1000, window.impressions(), "impressions/clicks still sum across all engines" );
        assertEquals( 10.0, window.position(), 0.0001,
                "the null-position engine must be excluded from the average, not averaged as 0" );
    }

    @Test
    void siteWindowNearSumsAcrossAllPagesRollupsOnlyIgnoringQueryRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                rollup( "google", D.toString(), 100, 5, 10.0 ),
                new VisibilityRow( D, 28, "google", "wiki.wikantik.com", "/wiki/B", "", 50, 2, 20.0 ),
                // a site-level query row (page_path = '') must not be summed into the site total
                new VisibilityRow( D, 28, "google", "wiki.wikantik.com", "", "some query", 999, 99, 1.0 ) ) );

        final PageWindow window = store.siteWindowNear( "wiki.wikantik.com", D, 3 ).orElseThrow();

        assertEquals( D, window.snapshotDate() );
        assertEquals( 150, window.impressions(), "the 999-impression query row must not be counted" );
        assertEquals( 7, window.clicks() );
    }

    @Test
    void siteWindowNearIsEmptyForAnUnknownSite() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of( rollup( "google", D.toString(), 100, 5, 10.0 ) ) );

        assertTrue( store.siteWindowNear( "nope.example.com", D, 3 ).isEmpty() );
    }

    @Test
    void pageWindowNearAndSiteWindowNearResolveTheirSnapshotDatesIndependently() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsert( List.of(
                // the target page's own rollup only exists at D+26 ...
                rollup( "google", D.plusDays( 26 ).toString(), 100, 10, 8.0 ),
                // ... but a DIFFERENT page's rollup, closer to the D+28 target, exists at D+29.
                new VisibilityRow( D.plusDays( 29 ), 28, "google", "wiki.wikantik.com",
                        "/wiki/OtherPage", "", 50, 5, 12.0 ) ) );

        final PageWindow pageWindow = store.pageWindowNear( "wiki.wikantik.com", "/wiki/A",
                D.plusDays( 28 ), 7 ).orElseThrow();
        final PageWindow siteWindow = store.siteWindowNear( "wiki.wikantik.com",
                D.plusDays( 28 ), 7 ).orElseThrow();

        assertEquals( D.plusDays( 26 ), pageWindow.snapshotDate(),
                "/wiki/A only has a rollup at D+26" );
        assertEquals( D.plusDays( 29 ), siteWindow.snapshotDate(),
                "the site-wide resolution must pick its own nearest date (D+29, from /wiki/OtherPage), "
                + "not reuse /wiki/A's D+26" );
    }

    // --- imported_opportunity (V056) -----------------------------------------------------------

    private ImportedOpportunityRow importedRow( final String asOf, final String type, final String target,
                                                final double expectedUplift, final Double confidence,
                                                final String evidenceJson ) {
        return new ImportedOpportunityRow( LocalDate.parse( asOf ), "google", "wiki.wikantik.com",
                type, target, expectedUplift, confidence, evidenceJson );
    }

    @Test
    void upsertImportedWritesRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final int written = store.upsertImported( List.of(
                importedRow( "2026-08-14", "ctr_gap", "low latency queue", 12.3, 0.6, "{\"impressions\":240}" ) ) );
        assertEquals( 1, written );
    }

    @Test
    void upsertImportedWithEmptyListWritesNothing() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertEquals( 0, store.upsertImported( List.of() ) );
    }

    @Test
    void reUpsertingImportedOpportunityConvergesInsteadOfDuplicating() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsertImported( List.of(
                importedRow( "2026-08-14", "ctr_gap", "low latency queue", 12.3, 0.6, "{\"impressions\":240}" ) ) );
        store.upsertImported( List.of(
                importedRow( "2026-08-14", "ctr_gap", "low latency queue", 20.0, 0.8, "{\"impressions\":500}" ) ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n, MAX(expected_uplift) u, MAX(confidence) cf FROM imported_opportunity" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1, rs.getInt( "n" ),
                    "re-shipping a detector run must converge -- backfill and the nightly run are the "
                    + "same code path, matching V050's own re-ingest contract" );
            assertEquals( 20.0, rs.getDouble( "u" ), 0.001 );
            assertEquals( 0.8, rs.getDouble( "cf" ), 0.001 );
        }
    }

    // --- latestImported (V056) -------------------------------------------------------------------

    @Test
    void latestImportedReturnsOnlyTheNewestAsOf() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate today = LocalDate.now();
        store.upsertImported( List.of(
                importedRow( today.minusDays( 5 ).toString(), "ctr_gap", "older query", 5.0, 0.5, null ),
                importedRow( today.toString(), "ctr_gap", "newer query", 10.0, 0.7, null ) ) );

        final List<Opportunity> result = store.latestImported( "wiki.wikantik.com", 7 );

        assertEquals( 1, result.size(), "only the single most recent as_of row set must be returned" );
        assertEquals( "newer query", result.get( 0 ).target() );
    }

    @Test
    void latestImportedReturnsEmptyWhenTheNewestAsOfIsOlderThanMaxAgeDays() {
        // THE STALENESS GUARD under test: a detector run jakemon shipped weeks ago must not keep
        // proposing the same work forever once the collector stops shipping. Easy to get
        // backwards -- this must return EMPTY, not the stale row.
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate staleAsOf = LocalDate.now().minusDays( 10 );
        store.upsertImported( List.of(
                importedRow( staleAsOf.toString(), "ctr_gap", "stale query", 5.0, 0.5, null ) ) );

        final List<Opportunity> result = store.latestImported( "wiki.wikantik.com", 7 );

        assertTrue( result.isEmpty(),
                "as_of is 10 days old and maxAgeDays is 7 -- this must return empty, not the stale row" );
    }

    @Test
    void latestImportedIncludesARowExactlyAtTheMaxAgeBoundary() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate boundaryAsOf = LocalDate.now().minusDays( 7 );
        store.upsertImported( List.of(
                importedRow( boundaryAsOf.toString(), "ctr_gap", "boundary query", 5.0, 0.5, null ) ) );

        final List<Opportunity> result = store.latestImported( "wiki.wikantik.com", 7 );

        assertEquals( 1, result.size(), "exactly maxAgeDays old must still count as fresh" );
    }

    @Test
    void latestImportedIsEmptyForASiteWithNoRows() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertTrue( store.latestImported( "nope.example.com", 7 ).isEmpty() );
    }

    @Test
    void latestImportedMapsFieldsIntoAnOpportunityWithEngineAndConfidenceInEvidence() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate today = LocalDate.now();
        store.upsertImported( List.of(
                importedRow( today.toString(), "striking_distance", "low latency queue", 12.3, 0.6,
                        "{\"impressions\":240,\"position\":14.2}" ) ) );

        final Opportunity o = store.latestImported( "wiki.wikantik.com", 7 ).get( 0 );

        assertEquals( "striking_distance", o.type() );
        assertEquals( "low latency queue", o.target() );
        assertEquals( 12.3, o.priority(), 0.001 );
        assertEquals( today, o.firstSeen() );
        assertFalse( o.calibrated() );
        assertEquals( "google", o.evidence().get( "engine" ),
                "OpportunityEngine.suppressDivergenceAffected reads exactly this evidence key" );
        assertEquals( 0.6, ( ( Number ) o.evidence().get( "confidence" ) ).doubleValue(), 0.001 );
        assertEquals( 240.0, ( ( Number ) o.evidence().get( "impressions" ) ).doubleValue(), 0.001 );
    }

    @Test
    void latestImportedHandlesNullConfidenceAndNullEvidence() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate today = LocalDate.now();
        store.upsertImported( List.of(
                importedRow( today.toString(), "content_gap", "graph databases", 3.0, null, null ) ) );

        final Opportunity o = store.latestImported( "wiki.wikantik.com", 7 ).get( 0 );

        assertNull( o.evidence().get( "confidence" ) );
        assertEquals( "google", o.evidence().get( "engine" ) );
    }

    // --- expected_ctr_curve (V057) --------------------------------------------------------------

    @Test
    void upsertCtrCurveWritesOneRowPerPosition() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final int written = store.upsertCtrCurve( LocalDate.parse( "2026-08-14" ),
                Map.of( 1, 0.28, 2, 0.15, 3, 0.11 ) );
        assertEquals( 3, written );
    }

    @Test
    void upsertCtrCurveWithEmptyPointsWritesNothing() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertEquals( 0, store.upsertCtrCurve( LocalDate.parse( "2026-08-14" ), Map.of() ) );
    }

    @Test
    void reUpsertingACtrCurveConvergesInsteadOfDuplicating() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );
        store.upsertCtrCurve( LocalDate.parse( "2026-08-14" ), Map.of( 1, 0.28 ) );
        store.upsertCtrCurve( LocalDate.parse( "2026-08-14" ), Map.of( 1, 0.30 ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n, MAX(ctr) c FROM expected_ctr_curve" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1, rs.getInt( "n" ),
                    "re-shipping a day's curve must converge -- the same idempotency contract as "
                    + "upsert() and upsertImported()" );
            assertEquals( 0.30, rs.getDouble( "c" ), 0.00001 );
        }
    }

    @Test
    void latestCtrCurveReturnsOnlyTheNewestAsOf() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate today = LocalDate.now();
        store.upsertCtrCurve( today.minusDays( 5 ), Map.of( 1, 0.20 ) );
        store.upsertCtrCurve( today, Map.of( 1, 0.28 ) );

        final CtrCurveSnapshot snapshot = store.latestCtrCurve( 7 ).orElseThrow();

        assertEquals( today, snapshot.asOf(), "only the single most recent as_of row set must be returned" );
        assertEquals( 0.28, snapshot.points().get( 1 ), 0.00001,
                "the older shipment's value must not leak through" );
    }

    @Test
    void latestCtrCurveReturnsEmptyWhenTheNewestAsOfIsOlderThanMaxAgeDays() {
        // THE STALENESS GUARD under test: a curve jakemon shipped weeks ago must not keep being
        // treated as current once the collector/shipper stops running. Easy to get backwards --
        // this must return EMPTY, not the stale curve.
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate staleAsOf = LocalDate.now().minusDays( 10 );
        store.upsertCtrCurve( staleAsOf, Map.of( 1, 0.28 ) );

        final Optional<CtrCurveSnapshot> result = store.latestCtrCurve( 7 );

        assertTrue( result.isEmpty(),
                "as_of is 10 days old and maxAgeDays is 7 -- this must return empty, not the stale curve" );
    }

    @Test
    void latestCtrCurveIncludesACurveExactlyAtTheMaxAgeBoundary() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate boundaryAsOf = LocalDate.now().minusDays( 7 );
        store.upsertCtrCurve( boundaryAsOf, Map.of( 1, 0.28 ) );

        final Optional<CtrCurveSnapshot> result = store.latestCtrCurve( 7 );

        assertTrue( result.isPresent(), "exactly maxAgeDays old must still count as fresh" );
    }

    @Test
    void latestCtrCurveIsEmptyWhenNoCurveHasEverBeenShipped() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertTrue( store.latestCtrCurve( 7 ).isEmpty() );
    }

    @Test
    void latestCtrCurveReturnsEveryPositionForTheLatestAsOf() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        final LocalDate today = LocalDate.now();
        store.upsertCtrCurve( today, Map.of(
                1, 0.28, 2, 0.15, 3, 0.11, 4, 0.08, 5, 0.06,
                6, 0.05, 7, 0.04, 8, 0.032, 9, 0.028, 10, 0.025 ) );

        final CtrCurveSnapshot snapshot = store.latestCtrCurve( 7 ).orElseThrow();

        assertEquals( 10, snapshot.points().size() );
        assertEquals( 0.032, snapshot.points().get( 8 ), 0.00001 );
    }
}

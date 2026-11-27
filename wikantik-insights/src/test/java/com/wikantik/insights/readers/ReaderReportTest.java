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
package com.wikantik.insights.readers;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

@RequiresPostgres
class ReaderReportTest {

    private static final String HOST = "wiki.example.com";
    private static final LocalDate TODAY = LocalDate.of( 2026, 11, 20 );
    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-20T12:00:00Z" ), ZoneOffset.UTC );

    private DataSource ds;
    private JdbcReaderRollupStore store;
    private ReaderReport report;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "reader_engagement_daily", "reader_acquisition_daily", "search_visibility_snapshot" );
        store = new JdbcReaderRollupStore( new Jdbc( ds ) );
        final PathResolver resolver = path -> path.equals( "/wiki/Alpha" ) ? Optional.of( "ID_A" )
                : path.equals( "/wiki/Beta" ) ? Optional.of( "ID_B" ) : Optional.empty();
        final PageDirectory directory = id -> id.equals( "ID_A" )
                ? Optional.of( new PageDirectory.Entry( "Alpha", "clusterx" ) )
                : Optional.of( new PageDirectory.Entry( "Beta", "clustery" ) );
        report = new ReaderReport( new Jdbc( ds ), resolver, directory, CLOCK );
    }

    private static EngagementDay eng( final LocalDate day, final String id, final int views, final int exits,
                                      final Double median, final int scroll75 ) {
        return new EngagementDay( day, id, views, views, exits, median, null, scroll75, 1, 1, 0, 0, 0, 0,
                2000.0, null, 0.05 );
    }

    private void store( final List< EngagementDay > e, final List< AcquisitionDay > a ) {
        store.replace( HOST, TODAY.minusDays( 60 ), TODAY, e, a );
    }

    private void snapshot( final LocalDate date, final String engine, final String path, final int clicks )
            throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "INSERT INTO search_visibility_snapshot "
                      + "(snapshot_date, window_days, engine, site_host, page_path, query_text, impressions, clicks) "
                      + "VALUES (?, 28, ?, ?, ?, '', 100, ?)" ) ) {
            ps.setDate( 1, java.sql.Date.valueOf( date ) );
            ps.setString( 2, engine );
            ps.setString( 3, HOST );
            ps.setString( 4, path );
            ps.setInt( 5, clicks );
            ps.executeUpdate();
        }
    }

    @Test
    void typicalActiveTimeIsTheExitWeightedMedianOfDailyMedians() {
        store( List.of( eng( LocalDate.of( 2026, 11, 18 ), "ID_A", 5, 2, 30.0, 1 ),
                        eng( LocalDate.of( 2026, 11, 19 ), "ID_A", 5, 1, 60.0, 0 ) ), List.of() );
        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );
        assertEquals( 30.0, a.typicalActiveS(), 1e-9 );
        assertEquals( 10, a.views() );
        assertEquals( "Alpha", a.slug() );
        assertEquals( 1.0 / 3.0, a.scroll75Rate(), 1e-9 );
        assertEquals( 4.0 / 10.0, a.actionsPerView(), 1e-9 );
        assertEquals( 2000.0, a.lcpP75Ms(), 1e-9 );
        assertNull( a.inpP75Ms() );
    }

    @Test
    void vitalsAreTheViewsWeightedMeanOfDailyP75Values() {
        // day 1: 10 views; day 2: 30 views; day 3: 100 views but no INP sample (excluded from INP only).
        store( List.of(
                new EngagementDay( LocalDate.of( 2026, 11, 17 ), "ID_A", 10, 10, 1, 10.0, null, 0, 0, 0, 0, 0, 0, 0,
                        1000.0, 100.0, 0.10 ),
                new EngagementDay( LocalDate.of( 2026, 11, 18 ), "ID_A", 30, 30, 1, 10.0, null, 0, 0, 0, 0, 0, 0, 0,
                        2000.0, 300.0, 0.20 ),
                new EngagementDay( LocalDate.of( 2026, 11, 19 ), "ID_A", 100, 100, 1, 10.0, null, 0, 0, 0, 0, 0, 0, 0,
                        null, null, null ) ), List.of() );
        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );
        assertEquals( ( 1000.0 * 10 + 2000.0 * 30 ) / 40.0, a.lcpP75Ms(), 1e-9 );
        assertEquals( ( 100.0 * 10 + 300.0 * 30 ) / 40.0, a.inpP75Ms(), 1e-9 );
        assertEquals( ( 0.10 * 10 + 0.20 * 30 ) / 40.0, a.clsP75(), 1e-9 );
    }

    @Test
    void returningShareIgnoresTheFirstSevenDaysOfAMonth() {
        // Nov 5: 10 landings, 10 returning (ignored). Nov 10 and Nov 12: 4 landings each, 1 returning on Nov 10.
        store( List.of( eng( LocalDate.of( 2026, 11, 5 ), "ID_A", 10, 1, 10.0, 0 ) ),
                List.of( new AcquisitionDay( LocalDate.of( 2026, 11, 5 ), "ID_A", "direct", "", 10, 5, 10 ),
                         new AcquisitionDay( LocalDate.of( 2026, 11, 10 ), "ID_A", "direct", "", 4, 2, 1 ),
                         new AcquisitionDay( LocalDate.of( 2026, 11, 12 ), "ID_A", "organic-search", "google", 4, 2, 0 ) ) );
        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );
        assertEquals( 1.0 / 8.0, a.returningThisMonthRate(), 1e-9 );
        assertEquals( 18, a.landings().values().stream().mapToInt( Integer::intValue ).sum() );
        assertEquals( 14, a.landings().get( "direct" ) );
        assertEquals( 9.0 / 18.0, a.continuationRate(), 1e-9 );
    }

    @Test
    void ordersByViewsAndHonoursLimitAndCluster() {
        store( List.of( eng( LocalDate.of( 2026, 11, 18 ), "ID_A", 3, 1, 10.0, 0 ),
                        eng( LocalDate.of( 2026, 11, 18 ), "ID_B", 9, 1, 10.0, 0 ),
                        eng( LocalDate.of( 2026, 11, 18 ), "", 50, 1, 10.0, 0 ) ), List.of() );
        assertEquals( List.of( "ID_B", "ID_A" ),
                report.pages( HOST, 28, 10 ).stream().map( PageReaderStats::canonicalId ).toList() );
        assertEquals( 1, report.pages( HOST, 28, 1 ).size() );
        assertEquals( List.of( "ID_A" ), report.pages( HOST, 28, 10, "clusterx" ).stream()
                .map( PageReaderStats::canonicalId ).toList() );
    }

    @Test
    void windowExcludesOlderDays() {
        store( List.of( eng( LocalDate.of( 2026, 10, 1 ), "ID_A", 3, 1, 10.0, 0 ) ), List.of() );
        assertEquals( 0, report.pages( HOST, 28, 10 ).size() );
    }

    @Test
    void coverageRatioIsOrganicLandingsOverSearchClicksPerEngineAndPage() throws Exception {
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Alpha", 20 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Beta", 20 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Unknown", 10 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "bing", "/wiki/Alpha", 4 );
        store( List.of(), List.of(
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_A", "organic-search", "google", 10, 5, 0 ),
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_A", "direct", "", 99, 5, 0 ),
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_B", "organic-search", "google", 5, 1, 0 ) ) );
        final ReaderReport.Coverage c = report.coverage( HOST, 28 );
        final ReaderReport.CoverageRow a = c.pages().stream()
                .filter( r -> r.engine().equals( "google" ) && r.canonicalId().equals( "ID_A" ) ).findFirst().orElseThrow();
        assertEquals( 20, a.searchClicks() );
        assertEquals( 10, a.organicLandings() );
        // Site: clicks across resolvable pages 40 (unresolvable page excluded from the join, kept in the site total).
        assertEquals( 15.0 / 50.0, c.site().get( "google" ), 1e-9 );
        assertEquals( 0.0, c.site().get( "bing" ), 1e-9 );
        assertEquals( Map.of( "google", 15.0 / 50.0, "bing", 0.0 ), c.site() );
    }

    private static EngagementDay full( final LocalDate day, final String id, final int views, final int visitors,
                                       final int exits, final Double median, final int scroll75 ) {
        return new EngagementDay( day, id, views, visitors, exits, median, null, scroll75,
                1, 2, 3, 4, 5, 6, null, null, null );
    }

    @Test
    void everyEngagementCounterIsSummedAcrossDays() {
        store( List.of( full( LocalDate.of( 2026, 11, 18 ), "ID_A", 7, 5, 3, 10.0, 2 ),
                        full( LocalDate.of( 2026, 11, 19 ), "ID_A", 3, 2, 1, 10.0, 1 ) ), List.of() );

        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );

        assertEquals( 10, a.views() );
        assertEquals( 7, a.visitors() );
        assertEquals( 3.0 / 4.0, a.scroll75Rate(), 1e-9 );
        // six action counters 1+2+3+4+5+6 = 21 per day, two days, over 10 views
        assertEquals( 42.0 / 10.0, a.actionsPerView(), 1e-9 );
    }

    @Test
    void aDayWithNoExitsContributesNoActiveTimeSample() {
        store( List.of( full( LocalDate.of( 2026, 11, 18 ), "ID_A", 7, 5, 0, 10.0, 0 ) ), List.of() );

        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );

        assertNull( a.typicalActiveS() );
        assertNull( a.scroll75Rate(), "no exits means no scroll rate, not 0/0" );
    }

    @Test
    void ratesAreNullRatherThanNaNWhenTheirDenominatorIsZero() {
        store( List.of( full( LocalDate.of( 2026, 11, 18 ), "ID_A", 0, 0, 0, null, 0 ) ), List.of() );

        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );

        assertNull( a.actionsPerView() );
        assertNull( a.continuationRate(), "no landings" );
        assertNull( a.returningThisMonthRate(), "no late-month landings" );
    }

    @Test
    void returningShareCountsFromTheEighthOfTheMonthNotTheSeventh() {
        store( List.of( eng( LocalDate.of( 2026, 11, 8 ), "ID_A", 10, 1, 10.0, 0 ) ),
                List.of( new AcquisitionDay( LocalDate.of( 2026, 11, 7 ), "ID_A", "direct", "", 10, 0, 10 ),
                         new AcquisitionDay( LocalDate.of( 2026, 11, 8 ), "ID_A", "direct", "", 10, 0, 5 ) ) );

        final PageReaderStats a = report.pages( HOST, 28, 10 ).get( 0 );

        assertEquals( 5.0 / 10.0, a.returningThisMonthRate(), 1e-9 );
    }

    @Test
    void windowStartsExactlyDaysMinusOneBeforeToday() {
        store( List.of( eng( TODAY, "ID_A", 1, 1, 10.0, 0 ),
                        eng( TODAY.minusDays( 1 ), "ID_B", 1, 1, 10.0, 0 ) ), List.of() );

        assertEquals( List.of( "ID_A" ), report.pages( HOST, 1, 10 ).stream().map( PageReaderStats::canonicalId ).toList(),
                "days=1 is today only" );
        assertEquals( List.of( "ID_A" ), report.pages( HOST, 0, 10 ).stream().map( PageReaderStats::canonicalId ).toList(),
                "days below 1 is treated as 1" );
        assertEquals( 2, report.pages( HOST, 2, 10 ).size() );
    }

    @Test
    void theOldestDayInTheWindowIsIncludedAndOneDayOlderIsNot() {
        store( List.of( eng( TODAY.minusDays( 27 ), "ID_A", 1, 1, 10.0, 0 ),
                        eng( TODAY.minusDays( 28 ), "ID_B", 1, 1, 10.0, 0 ) ), List.of() );

        assertEquals( List.of( "ID_A" ), report.pages( HOST, 28, 10 ).stream().map( PageReaderStats::canonicalId ).toList() );
    }

    @Test
    void anotherSitesRowsAreNeverReported() {
        store.replace( "other.example.com", TODAY.minusDays( 60 ), TODAY,
                List.of( eng( TODAY, "ID_A", 99, 1, 10.0, 0 ) ), List.of() );

        assertTrue( report.pages( HOST, 28, 10 ).isEmpty() );
        assertNull( report.refreshedAt( HOST ) );
    }

    @Test
    void refreshedAtIsTheDatabaseTimeOfTheLastReadBack() {
        store( List.of( eng( TODAY, "ID_A", 1, 1, 10.0, 0 ) ), List.of() );

        final Instant refreshed = report.refreshedAt( HOST );

        assertTrue( refreshed != null && Math.abs( refreshed.until( Instant.now(), java.time.temporal.ChronoUnit.SECONDS ) ) < 300,
                "refreshedAt should be about now but was " + refreshed );
    }

    @Test
    void weightedMedianIsTheSmallestValueReachingHalfTheWeight() {
        assertNull( ReaderReport.weightedMedian( List.of() ) );
        assertEquals( 7.0, ReaderReport.weightedMedian( List.<double[]>of( new double[] { 7, 3 } ) ), 1e-9 );
        // unsorted input; weights 1+2 = 3, half 1.5: sorted 30(w2) reaches it first
        assertEquals( 30.0, ReaderReport.weightedMedian(
                List.of( new double[] { 60, 1 }, new double[] { 30, 2 } ) ), 1e-9 );
        // exactly half: 10 (w1) of total 2 reaches 1 >= 1, so the lower value wins the tie
        assertEquals( 10.0, ReaderReport.weightedMedian(
                List.of( new double[] { 20, 1 }, new double[] { 10, 1 } ) ), 1e-9 );
        // heavy tail: 90 carries 8 of 10, the median is 90 not the first value
        assertEquals( 90.0, ReaderReport.weightedMedian(
                List.of( new double[] { 90, 8 }, new double[] { 10, 1 }, new double[] { 20, 1 } ) ), 1e-9 );
    }

    @Test
    void weightedMedianDoesNotReorderTheCallersList() {
        final List< double[] > input = new java.util.ArrayList<>(
                List.of( new double[] { 60, 1 }, new double[] { 30, 2 } ) );
        ReaderReport.weightedMedian( input );
        assertEquals( 60.0, input.get( 0 )[ 0 ], 1e-9 );
    }

    @Test
    void coverageIgnoresAnEngineWithNoClicksAndOlderSnapshots() throws Exception {
        snapshot( LocalDate.of( 2026, 11, 10 ), "google", "/wiki/Alpha", 500 );   // superseded by the later one
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Alpha", 8 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "yandex", "/wiki/Alpha", 0 );
        store( List.of(), List.of(
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_A", "organic-search", "google", 4, 0, 0 ) ) );

        final ReaderReport.Coverage c = report.coverage( HOST, 28 );

        assertEquals( Map.of( "google", 0.5 ), c.site(), "a zero-click engine has no ratio" );
        assertEquals( 1, c.pages().stream().filter( r -> r.engine().equals( "google" ) ).count() );
        assertEquals( 8, c.pages().stream().filter( r -> r.engine().equals( "google" ) )
                .findFirst().orElseThrow().searchClicks() );
    }

    @Test
    void coveragePairsPagesThatHaveOnlyClicksOrOnlyLandingsAndSortsTheRows() throws Exception {
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Alpha", 20 );   // clicks, no landings
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "/wiki/Beta", 30 );    // clicks, and landings below
        snapshot( LocalDate.of( 2026, 11, 19 ), "bing", "/wiki/Beta", 5 );
        store( List.of(), List.of(
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_B", "organic-search", "google", 6, 0, 0 ),
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_C", "organic-search", "google", 2, 0, 0 ),   // landings, no clicks
                new AcquisitionDay( LocalDate.of( 2026, 11, 15 ), "ID_A", "organic-search", "ddg", 9, 0, 0 ) ) );    // engine with no snapshot

        final List< ReaderReport.CoverageRow > rows = report.coverage( HOST, 28 ).pages();

        assertEquals( List.of(
                new ReaderReport.CoverageRow( "bing", "ID_B", 5, 0 ),
                new ReaderReport.CoverageRow( "google", "ID_B", 30, 6 ),
                new ReaderReport.CoverageRow( "google", "ID_A", 20, 0 ),
                new ReaderReport.CoverageRow( "google", "ID_C", 0, 2 ) ), rows );
    }

    @Test
    void coverageResolvesSnapshotPagesGivenAsFullUrlsAndSurvivesUnparseableOnes() throws Exception {
        final PathResolver resolver = path -> switch ( path ) {
            case "/wiki/Alpha" -> Optional.of( "ID_A" );
            case "/" -> Optional.of( "ID_ROOT" );
            default -> Optional.empty();
        };
        final ReaderReport urlReport = new ReaderReport( new Jdbc( ds ), resolver,
                id -> Optional.empty(), CLOCK );
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "https://wiki.example.com/wiki/Alpha", 7 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "https://wiki.example.com", 3 );
        snapshot( LocalDate.of( 2026, 11, 19 ), "google", "http://bad host/x", 2 );

        final ReaderReport.Coverage c = urlReport.coverage( HOST, 28 );

        assertEquals( List.of(
                new ReaderReport.CoverageRow( "google", "ID_A", 7, 0 ),
                new ReaderReport.CoverageRow( "google", "ID_ROOT", 3, 0 ) ), c.pages() );
        assertEquals( Map.of( "google", 0.0 ), c.site(), "the unparseable row still counts in the engine's clicks" );
    }

    @Test
    void coverageWindowStartsDaysMinusOneBeforeToday() throws Exception {
        snapshot( TODAY.minusDays( 1 ), "google", "/wiki/Alpha", 9 );
        store( List.of(), List.of() );

        assertTrue( report.coverage( HOST, 1 ).pages().isEmpty(), "yesterday is outside a one-day window" );
        assertEquals( 1, report.coverage( HOST, 2 ).pages().size() );
    }
}

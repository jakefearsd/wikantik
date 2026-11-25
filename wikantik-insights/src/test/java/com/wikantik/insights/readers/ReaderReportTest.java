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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertNotNull( c.site().get( "google" ) );
        assertEquals( 15.0 / 50.0, c.site().get( "google" ), 1e-9 );
        assertEquals( 0.0, c.site().get( "bing" ), 1e-9 );
        assertEquals( Map.of( "google", 15.0 / 50.0, "bing", 0.0 ), c.site() );
    }
}

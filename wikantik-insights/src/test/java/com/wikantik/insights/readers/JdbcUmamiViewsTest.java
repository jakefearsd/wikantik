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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The four views belong to another database, so they are stood in for by TEMP tables on one
 * pinned connection: session-scoped, resolved ahead of any real schema, gone on close.
 */
@RequiresPostgres
class JdbcUmamiViewsTest {

    private static final UUID SITE = UUID.fromString( "11111111-1111-1111-1111-111111111111" );
    private static final UUID OTHER = UUID.fromString( "22222222-2222-2222-2222-222222222222" );
    private static final LocalDate D1 = LocalDate.of( 2026, 11, 1 );
    private static final LocalDate D2 = LocalDate.of( 2026, 11, 2 );
    private static final LocalDate D3 = LocalDate.of( 2026, 11, 3 );

    private Connection pinned;
    private JdbcUmamiViews views;

    @BeforeEach
    void setUp() throws Exception {
        pinned = PostgresTestDb.createDataSource().getConnection();
        try ( Statement st = pinned.createStatement() ) {
            st.execute( "CREATE TEMP TABLE analytics_engagement (day date, website_id uuid, canonical_id text, "
                + "exits bigint, active_median_s numeric, active_p90_s numeric, exits_scroll75 bigint, "
                + "code_copies bigint, wikilink_clicks bigint, outbound_clicks bigint, diagram_interacts bigint, "
                + "toc_clicks bigint, sign_in_clicks bigint)" );
            st.execute( "CREATE TEMP TABLE analytics_page_vitals (day date, website_id uuid, url_path text, "
                + "samples bigint, lcp_p75 numeric, inp_p75 numeric, cls_p75 numeric)" );
            st.execute( "CREATE TEMP TABLE analytics_traffic (website_id uuid, path text, entry_anchor text, "
                + "day date, sessions bigint, views bigint)" );
            st.execute( "CREATE TEMP TABLE analytics_acquisition (day date, website_id uuid, url_path text, "
                + "channel text, source text, landings bigint, continued bigint, returning_landings bigint)" );
        }
        views = new JdbcUmamiViews( new Jdbc( singleConnection( pinned ) ), SITE );
    }

    @AfterEach
    void tearDown() throws Exception {
        pinned.close();
    }

    /** A DataSource that hands out the one pinned connection and ignores close(). */
    private static DataSource singleConnection( final Connection conn ) {
        final Connection nonClosing = ( Connection ) Proxy.newProxyInstance(
            JdbcUmamiViewsTest.class.getClassLoader(), new Class< ? >[] { Connection.class },
            ( proxy, method, args ) -> {
                if ( "close".equals( method.getName() ) ) {
                    return null;
                }
                try {
                    return method.invoke( conn, args );
                } catch ( final java.lang.reflect.InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
        return new DataSource() {
            @Override public Connection getConnection() { return nonClosing; }
            @Override public Connection getConnection( final String u, final String p ) { return nonClosing; }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter( final PrintWriter out ) { }
            @Override public void setLoginTimeout( final int seconds ) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public < T > T unwrap( final Class< T > iface ) throws SQLException {
                throw new SQLException( "not a wrapper" );
            }
            @Override public boolean isWrapperFor( final Class< ? > iface ) { return false; }
        };
    }

    private void exec( final String sql ) throws Exception {
        try ( Statement st = pinned.createStatement() ) {
            st.execute( sql );
        }
    }

    @Test
    void trafficIsFilteredBySiteAndBoundedByDay() throws Exception {
        exec( "INSERT INTO analytics_traffic VALUES ('" + SITE + "','/wiki/A','',DATE '2026-10-31',1,1),"
            + "('" + SITE + "','/wiki/A','',DATE '2026-11-01',2,3),"
            + "('" + SITE + "','/wiki/B','',DATE '2026-11-03',4,5),"
            + "('" + SITE + "','/wiki/A','',DATE '2026-11-04',9,9),"
            + "('" + OTHER + "','/wiki/A','',DATE '2026-11-02',7,7)" );

        final List< UmamiViews.TrafficRow > rows = views.traffic( D1, D3 );

        assertEquals( List.of( new UmamiViews.TrafficRow( D1, "/wiki/A", 2, 3 ),
                               new UmamiViews.TrafficRow( D3, "/wiki/B", 4, 5 ) ),
                      rows.stream().sorted( java.util.Comparator.comparing( UmamiViews.TrafficRow::day ) ).toList() );
    }

    @Test
    void engagementMapsEveryColumnAndKeepsNullPercentiles() throws Exception {
        exec( "INSERT INTO analytics_engagement VALUES (DATE '2026-11-02','" + SITE + "','01ABC',1,12.5,40,2,3,4,5,6,7,8),"
            + "(DATE '2026-11-02','" + SITE + "','',1,NULL,NULL,0,0,0,0,0,0,0),"
            + "(DATE '2026-11-02','" + OTHER + "','01ABC',99,1,1,0,0,0,0,0,0,0),"
            + "(DATE '2026-11-04','" + SITE + "','01ABC',99,1,1,0,0,0,0,0,0,0)" );

        final List< UmamiViews.EngagementRow > rows = views.engagement( D1, D3 );

        assertEquals( 2, rows.size() );
        final UmamiViews.EngagementRow page = rows.stream().filter( r -> "01ABC".equals( r.canonicalId() ) )
            .findFirst().orElseThrow();
        assertEquals( new UmamiViews.EngagementRow( D2, "01ABC", 1, 12.5, 40.0, 2, 3, 4, 5, 6, 7, 8 ), page );
        assertNull( rows.stream().filter( r -> "".equals( r.canonicalId() ) ).findFirst().orElseThrow().activeMedianS() );
    }

    @Test
    void vitalsAreFilteredAndNullableAndAcquisitionReadsReturningLandings() throws Exception {
        exec( "INSERT INTO analytics_page_vitals VALUES (DATE '2026-11-02','" + SITE + "','/wiki/A',10,2500,200,0.1),"
            + "(DATE '2026-11-02','" + SITE + "','/wiki/B',3,NULL,NULL,NULL),"
            + "(DATE '2026-11-02','" + OTHER + "','/wiki/A',3,1,1,1)" );
        exec( "INSERT INTO analytics_acquisition VALUES (DATE '2026-11-02','" + SITE + "','/wiki/A','organic','google',5,3,2),"
            + "(DATE '2026-11-02','" + OTHER + "','/wiki/A','organic','google',9,9,9),"
            + "(DATE '2026-11-09','" + SITE + "','/wiki/A','organic','google',9,9,9)" );

        final List< UmamiViews.VitalsRow > vitals = views.vitals( D1, D3 );
        final List< UmamiViews.AcquisitionRow > acq = views.acquisition( D1, D3 );

        assertEquals( 2, vitals.size() );
        assertNull( vitals.stream().filter( v -> "/wiki/B".equals( v.path() ) ).findFirst().orElseThrow().lcpP75() );
        assertEquals( List.of( new UmamiViews.AcquisitionRow( D2, "/wiki/A", "organic", "google", 5, 3, 2 ) ), acq );
    }
}

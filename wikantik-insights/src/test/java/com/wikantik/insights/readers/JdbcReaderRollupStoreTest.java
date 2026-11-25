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
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@RequiresPostgres
class JdbcReaderRollupStoreTest {

    private static final String HOST = "wiki.example.com";
    private static final LocalDate D1 = LocalDate.of( 2026, 11, 1 );
    private static final LocalDate D2 = LocalDate.of( 2026, 11, 2 );
    private static final LocalDate D3 = LocalDate.of( 2026, 11, 3 );

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "reader_engagement_daily", "reader_acquisition_daily" );
    }

    private static EngagementDay eng( final LocalDate day, final String id, final int views ) {
        return new EngagementDay( day, id, views, 2, 1, 12.5, 40.0, 1, 2, 3, 4, 5, 6, 7, null, 90.0, 0.1 );
    }

    private static AcquisitionDay acq( final LocalDate day, final String id, final int landings ) {
        return new AcquisitionDay( day, id, "organic", "google", landings, 2, 1 );
    }

    private List< String > dump( final String sql ) throws Exception {
        final List< String > out = new ArrayList<>();
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( sql ) ) {
            final int n = rs.getMetaData().getColumnCount();
            while ( rs.next() ) {
                final StringBuilder sb = new StringBuilder();
                for ( int i = 1; i <= n; i++ ) {
                    sb.append( rs.getObject( i ) ).append( '|' );
                }
                out.add( sb.toString() );
            }
        }
        return out;
    }

    private static final String ENG_DUMP = "SELECT day, site_host, canonical_id, views, visitors, exits, "
        + "active_median_s, active_p90_s, exits_scroll75, code_copies, wikilink_clicks, outbound_clicks, "
        + "diagram_interacts, toc_clicks, sign_in_clicks, lcp_p75_ms, inp_p75_ms, cls_p75 "
        + "FROM reader_engagement_daily ORDER BY day, canonical_id";
    private static final String ACQ_DUMP = "SELECT day, site_host, canonical_id, channel, source, landings, "
        + "continued, returning_landings FROM reader_acquisition_daily ORDER BY day, canonical_id";

    @Test
    void replacingTheSameWindowTwiceLeavesIdenticalRows() throws Exception {
        final JdbcReaderRollupStore store = new JdbcReaderRollupStore( new Jdbc( ds ) );
        final List< EngagementDay > e = List.of( eng( D1, "", 3 ), eng( D1, "01ABC", 5 ) );
        final List< AcquisitionDay > a = List.of( acq( D1, "01ABC", 4 ) );

        store.replace( HOST, D1, D2, e, a );
        final List< String > firstE = dump( ENG_DUMP );
        final List< String > firstA = dump( ACQ_DUMP );
        store.replace( HOST, D1, D2, e, a );

        assertEquals( 2, firstE.size() );
        assertEquals( firstE, dump( ENG_DUMP ) );
        assertEquals( firstA, dump( ACQ_DUMP ) );
    }

    @Test
    void rowsOutsideTheWindowAndOtherSitesAreUntouched() throws Exception {
        final JdbcReaderRollupStore store = new JdbcReaderRollupStore( new Jdbc( ds ) );
        store.replace( HOST, D1, D3, List.of( eng( D1, "A", 1 ), eng( D3, "A", 1 ) ),
                       List.of( acq( D1, "A", 1 ), acq( D3, "A", 1 ) ) );
        store.replace( "other.example.com", D2, D2, List.of( eng( D2, "A", 9 ) ), List.of() );

        store.replace( HOST, D2, D2, List.of( eng( D2, "B", 7 ) ), List.of() );

        assertEquals( 4, dump( ENG_DUMP ).size() );
        assertEquals( 2, dump( ACQ_DUMP ).size() );
        assertEquals( 1, dump( "SELECT 1 FROM reader_engagement_daily WHERE site_host='other.example.com'" ).size() );
        assertEquals( 1, dump( "SELECT 1 FROM reader_engagement_daily WHERE canonical_id='B' AND views=7" ).size() );
    }

    @Test
    void aFailureOnTheSecondInsertKeepsThePreviousRows() throws Exception {
        new JdbcReaderRollupStore( new Jdbc( ds ) )
            .replace( HOST, D1, D1, List.of( eng( D1, "OLD", 1 ) ), List.of( acq( D1, "OLD", 1 ) ) );
        final List< String > before = dump( ENG_DUMP );

        // statements: 1 delete engagement, 2 delete acquisition, 3 insert engagement, 4 insert acquisition
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 4, new IllegalStateException( "boom" ) );
        final JdbcReaderRollupStore store = new JdbcReaderRollupStore( new Jdbc( faulty ) );

        assertThrows( IllegalStateException.class, () ->
            store.replace( HOST, D1, D1, List.of( eng( D1, "NEW", 9 ) ), List.of( acq( D1, "NEW", 9 ) ) ) );

        assertEquals( before, dump( ENG_DUMP ) );
        assertEquals( 1, dump( "SELECT 1 FROM reader_acquisition_daily WHERE canonical_id='OLD'" ).size() );
        assertEquals( 1, faulty.rollbacks() );
    }
}

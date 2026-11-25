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
import com.wikantik.jdbc.SqlBinder;

import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.wikantik.jdbc.NullableColumns.setNullableDouble;

/** {@link ReaderRollupStore} over {@code reader_engagement_daily} / {@code reader_acquisition_daily}. */
public final class JdbcReaderRollupStore implements ReaderRollupStore {

    private static final String DELETE_ENGAGEMENT =
        "DELETE FROM reader_engagement_daily WHERE site_host = ? AND day BETWEEN ? AND ?";
    private static final String DELETE_ACQUISITION =
        "DELETE FROM reader_acquisition_daily WHERE site_host = ? AND day BETWEEN ? AND ?";

    private static final String INSERT_ENGAGEMENT = """
        INSERT INTO reader_engagement_daily
            (day, site_host, canonical_id, views, visitors, exits, active_median_s, active_p90_s,
             exits_scroll75, code_copies, wikilink_clicks, outbound_clicks, diagram_interacts,
             toc_clicks, sign_in_clicks, lcp_p75_ms, inp_p75_ms, cls_p75)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

    private static final String INSERT_ACQUISITION = """
        INSERT INTO reader_acquisition_daily
            (day, site_host, canonical_id, channel, source, landings, continued, returning_landings)
        VALUES (?,?,?,?,?,?,?,?)
        """;

    private final Jdbc jdbc;

    public JdbcReaderRollupStore( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public void replace( final String siteHost, final LocalDate from, final LocalDate to,
                         final List< EngagementDay > engagement,
                         final List< AcquisitionDay > acquisition ) {
        final SqlBinder window = ps -> {
            ps.setString( 1, siteHost );
            ps.setDate( 2, Date.valueOf( from ) );
            ps.setDate( 3, Date.valueOf( to ) );
        };
        final List< SqlBinder > engagementBinders = new ArrayList<>( engagement.size() );
        for ( final EngagementDay e : engagement ) {
            engagementBinders.add( ps -> {
                ps.setDate( 1, Date.valueOf( e.day() ) );
                ps.setString( 2, siteHost );
                ps.setString( 3, e.canonicalId() );
                ps.setInt( 4, e.views() );
                ps.setInt( 5, e.visitors() );
                ps.setInt( 6, e.exits() );
                setNullableDouble( ps, 7, e.activeMedianS() );
                setNullableDouble( ps, 8, e.activeP90S() );
                ps.setInt( 9, e.exitsScroll75() );
                ps.setInt( 10, e.codeCopies() );
                ps.setInt( 11, e.wikilinkClicks() );
                ps.setInt( 12, e.outboundClicks() );
                ps.setInt( 13, e.diagramInteracts() );
                ps.setInt( 14, e.tocClicks() );
                ps.setInt( 15, e.signInClicks() );
                setNullableDouble( ps, 16, e.lcpP75Ms() );
                setNullableDouble( ps, 17, e.inpP75Ms() );
                setNullableDouble( ps, 18, e.clsP75() );
            } );
        }
        final List< SqlBinder > acquisitionBinders = new ArrayList<>( acquisition.size() );
        for ( final AcquisitionDay a : acquisition ) {
            acquisitionBinders.add( ps -> {
                ps.setDate( 1, Date.valueOf( a.day() ) );
                ps.setString( 2, siteHost );
                ps.setString( 3, a.canonicalId() );
                ps.setString( 4, a.channel() );
                ps.setString( 5, a.source() );
                ps.setInt( 6, a.landings() );
                ps.setInt( 7, a.continued() );
                ps.setInt( 8, a.returningLandings() );
            } );
        }
        try {
            jdbc.inTransaction( conn -> {
                jdbc.update( conn, DELETE_ENGAGEMENT, window );
                jdbc.update( conn, DELETE_ACQUISITION, window );
                if ( !engagementBinders.isEmpty() ) {
                    jdbc.batch( conn, INSERT_ENGAGEMENT, engagementBinders );
                }
                if ( !acquisitionBinders.isEmpty() ) {
                    jdbc.batch( conn, INSERT_ACQUISITION, acquisitionBinders );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Could not replace reader rollups: " + e.getMessage(), e );
        }
    }
}

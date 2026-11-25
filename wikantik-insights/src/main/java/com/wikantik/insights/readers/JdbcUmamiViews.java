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
import java.util.List;
import java.util.UUID;

import static com.wikantik.jdbc.NullableColumns.getNullableDouble;

/** {@link UmamiViews} over the suite's {@code analytics_*} views, filtered on {@code website_id}. */
public final class JdbcUmamiViews implements UmamiViews {

    private static final String TRAFFIC_SQL = """
        SELECT day, path, sessions, views FROM analytics_traffic
        WHERE website_id = ? AND day BETWEEN ? AND ?
        """;

    private static final String ENGAGEMENT_SQL = """
        SELECT day, canonical_id, exits, active_median_s, active_p90_s, exits_scroll75,
               code_copies, wikilink_clicks, outbound_clicks, diagram_interacts, toc_clicks,
               sign_in_clicks
        FROM analytics_engagement
        WHERE website_id = ? AND day BETWEEN ? AND ?
        """;

    private static final String VITALS_SQL = """
        SELECT day, url_path, samples, lcp_p75, inp_p75, cls_p75 FROM analytics_page_vitals
        WHERE website_id = ? AND day BETWEEN ? AND ?
        """;

    private static final String ACQUISITION_SQL = """
        SELECT day, url_path, channel, source, landings, continued, returning_landings
        FROM analytics_acquisition
        WHERE website_id = ? AND day BETWEEN ? AND ?
        """;

    private final Jdbc jdbc;
    private final UUID websiteId;

    public JdbcUmamiViews( final Jdbc jdbc, final UUID websiteId ) {
        this.jdbc = jdbc;
        this.websiteId = websiteId;
    }

    private SqlBinder window( final LocalDate from, final LocalDate to ) {
        return ps -> {
            ps.setObject( 1, websiteId );
            ps.setDate( 2, Date.valueOf( from ) );
            ps.setDate( 3, Date.valueOf( to ) );
        };
    }

    @Override
    public List< TrafficRow > traffic( final LocalDate from, final LocalDate to ) {
        return run( TRAFFIC_SQL, from, to, rs -> new TrafficRow(
            rs.getDate( "day" ).toLocalDate(), rs.getString( "path" ),
            rs.getLong( "sessions" ), rs.getLong( "views" ) ) );
    }

    @Override
    public List< EngagementRow > engagement( final LocalDate from, final LocalDate to ) {
        return run( ENGAGEMENT_SQL, from, to, rs -> new EngagementRow(
            rs.getDate( "day" ).toLocalDate(), rs.getString( "canonical_id" ),
            rs.getLong( "exits" ), getNullableDouble( rs, "active_median_s" ),
            getNullableDouble( rs, "active_p90_s" ), rs.getLong( "exits_scroll75" ),
            rs.getLong( "code_copies" ), rs.getLong( "wikilink_clicks" ),
            rs.getLong( "outbound_clicks" ), rs.getLong( "diagram_interacts" ),
            rs.getLong( "toc_clicks" ), rs.getLong( "sign_in_clicks" ) ) );
    }

    @Override
    public List< VitalsRow > vitals( final LocalDate from, final LocalDate to ) {
        return run( VITALS_SQL, from, to, rs -> new VitalsRow(
            rs.getDate( "day" ).toLocalDate(), rs.getString( "url_path" ), rs.getLong( "samples" ),
            getNullableDouble( rs, "lcp_p75" ), getNullableDouble( rs, "inp_p75" ),
            getNullableDouble( rs, "cls_p75" ) ) );
    }

    @Override
    public List< AcquisitionRow > acquisition( final LocalDate from, final LocalDate to ) {
        return run( ACQUISITION_SQL, from, to, rs -> new AcquisitionRow(
            rs.getDate( "day" ).toLocalDate(), rs.getString( "url_path" ),
            rs.getString( "channel" ), rs.getString( "source" ), rs.getLong( "landings" ),
            rs.getLong( "continued" ), rs.getLong( "returning_landings" ) ) );
    }

    private < T > List< T > run( final String sql, final LocalDate from, final LocalDate to,
                                 final com.wikantik.jdbc.RowMapper< T > mapper ) {
        try {
            return jdbc.query( sql, window( from, to ), mapper );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Umami view query failed: " + e.getMessage(), e );
        }
    }
}

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed implementation of InsightsStore that upserts search visibility snapshots
 * using PostgreSQL ON CONFLICT syntax.
 */
public class JdbcInsightsStore implements InsightsStore {

    private static final Logger LOG = LogManager.getLogger( JdbcInsightsStore.class );

    private static final String INSERT_SQL = """
        INSERT INTO search_visibility_snapshot
            (snapshot_date, window_days, engine, site_host, page_path, query_text,
             impressions, clicks, position)
        VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT (snapshot_date, engine, site_host, page_path, query_text)
        DO UPDATE SET impressions = EXCLUDED.impressions,
                      clicks      = EXCLUDED.clicks,
                      position    = EXCLUDED.position,
                      window_days = EXCLUDED.window_days,
                      ingested_at = NOW()
        """;

    private static final String LATEST_DATE_SQL = """
        SELECT MAX(snapshot_date) AS latest
        FROM search_visibility_snapshot
        WHERE site_host = ? AND query_text = ''
        """;

    // position > 0 excludes both NULL (Yandex never emits it) and any stray non-positive
    // value from the average -- a 0 or negative position is never a real rank.
    private static final String ENGINE_TOTALS_SQL = """
        SELECT engine,
               SUM(clicks)      AS clicks,
               SUM(impressions) AS impressions,
               AVG(position) FILTER (WHERE position > 0) AS avg_position
        FROM search_visibility_snapshot
        WHERE site_host = ? AND query_text = ? AND snapshot_date = ?
        GROUP BY engine
        ORDER BY engine
        """;

    private static final String TREND_SQL = """
        SELECT snapshot_date, engine,
               SUM(clicks)      AS clicks,
               SUM(impressions) AS impressions
        FROM search_visibility_snapshot
        WHERE site_host = ? AND query_text = ? AND snapshot_date >= ?
        GROUP BY snapshot_date, engine
        ORDER BY snapshot_date ASC, engine ASC
        """;

    /** Marks the page-rollup row -- see {@link VisibilityRow} for why it is authoritative. */
    private static final String PAGE_ROLLUP_QUERY_TEXT = "";

    private final DataSource dataSource;

    /**
     * Creates a new JdbcInsightsStore backed by the given DataSource.
     *
     * @param dataSource the data source to use for connections
     */
    public JdbcInsightsStore( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    @Override
    public int upsert( final List<VisibilityRow> rows ) {
        if ( rows.isEmpty() ) {
            return 0;
        }

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {

            for ( final VisibilityRow row : rows ) {
                ps.setDate( 1, Date.valueOf( row.snapshotDate() ) );
                ps.setInt( 2, row.windowDays() );
                ps.setString( 3, row.engine() );
                ps.setString( 4, row.siteHost() );
                ps.setString( 5, row.pagePath() );
                ps.setString( 6, row.queryText() );
                ps.setInt( 7, row.impressions() );
                ps.setInt( 8, row.clicks() );

                if ( row.position() == null ) {
                    ps.setNull( 9, Types.NUMERIC );
                } else {
                    ps.setDouble( 9, row.position() );
                }

                ps.addBatch();
            }

            final int[] results = ps.executeBatch();
            int total = 0;
            for ( final int result : results ) {
                total += Math.max( result, 0 );
            }

            return total;

        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} visibility rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }

    @Override
    public Optional<LocalDate> latestSnapshotDate( final String siteHost ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( LATEST_DATE_SQL ) ) {
            ps.setString( 1, siteHost );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    return Optional.empty();
                }
                final Date latest = rs.getDate( "latest" );
                return latest == null ? Optional.empty() : Optional.of( latest.toLocalDate() );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest visibility snapshot date for site {}: {}",
                    siteHost, e.getMessage(), e );
            throw new IllegalStateException( "latest visibility snapshot date query failed", e );
        }
    }

    @Override
    public List<EngineTotal> engineTotals( final String siteHost, final LocalDate snapshotDate ) {
        final List<EngineTotal> out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( ENGINE_TOTALS_SQL ) ) {
            ps.setString( 1, siteHost );
            ps.setString( 2, PAGE_ROLLUP_QUERY_TEXT );
            ps.setDate( 3, Date.valueOf( snapshotDate ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final double avgPosition = rs.getDouble( "avg_position" );
                    final Double position = rs.wasNull() ? null : avgPosition;
                    out.add( new EngineTotal( rs.getString( "engine" ), rs.getLong( "clicks" ),
                            rs.getLong( "impressions" ), position ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read visibility engine totals for site {} date {}: {}",
                    siteHost, snapshotDate, e.getMessage(), e );
            throw new IllegalStateException( "visibility engine totals query failed", e );
        }
        return out;
    }

    @Override
    public List<TrendPoint> trend( final String siteHost, final LocalDate since ) {
        final List<TrendPoint> out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( TREND_SQL ) ) {
            ps.setString( 1, siteHost );
            ps.setString( 2, PAGE_ROLLUP_QUERY_TEXT );
            ps.setDate( 3, Date.valueOf( since ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( new TrendPoint( rs.getDate( "snapshot_date" ).toLocalDate(),
                            rs.getString( "engine" ), rs.getLong( "clicks" ), rs.getLong( "impressions" ) ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read visibility trend for site {} since {}: {}",
                    siteHost, since, e.getMessage(), e );
            throw new IllegalStateException( "visibility trend query failed", e );
        }
        return out;
    }
}

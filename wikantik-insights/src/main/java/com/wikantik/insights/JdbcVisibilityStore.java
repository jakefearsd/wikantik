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

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getNullableDouble;

/**
 * Search-visibility snapshots: the upsert ingest path, the latest-snapshot reads and the
 * nearest-window lookups the effect evaluator uses. Held by {@link JdbcInsightsStore}.
 */
final class JdbcVisibilityStore {

    private static final Logger LOG = LogManager.getLogger( JdbcVisibilityStore.class );

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

    // Engines poll independently, so "latest" is resolved per engine, not once for the site --
    // a shared date would silently empty the cross-engine comparison whenever one engine lags.
    // withinDays then drops any engine whose own latest snapshot trails the newest one seen.
    // "Latest" is anchored on the page-rollup row (query_text = ''), matching
    // latestSnapshotDate()'s convention -- see VisibilityRow's javadoc for why the rollup row is
    // authoritative -- but the final SELECT below returns every row (rollup and query alike) for
    // that resolved date.
    private static final String LATEST_ROWS_PER_ENGINE_SQL = """
        WITH engine_latest AS (
            SELECT engine, MAX(snapshot_date) AS latest_date
            FROM search_visibility_snapshot
            WHERE site_host = ? AND query_text = ''
            GROUP BY engine
        ),
        newest AS (
            SELECT MAX(latest_date) AS newest_date FROM engine_latest
        )
        SELECT s.snapshot_date, s.window_days, s.engine, s.site_host, s.page_path, s.query_text,
               s.impressions, s.clicks, s.position
        FROM search_visibility_snapshot s
        JOIN engine_latest el ON el.engine = s.engine AND el.latest_date = s.snapshot_date
        CROSS JOIN newest n
        WHERE s.site_host = ? AND el.latest_date >= n.newest_date - ?
        ORDER BY s.engine, s.page_path, s.query_text
        """;

    private static final String SITE_IMPRESSIONS_28D_SQL = """
        WITH engine_latest AS (
            SELECT engine, MAX(snapshot_date) AS latest_date
            FROM search_visibility_snapshot
            WHERE site_host = ? AND query_text = ''
            GROUP BY engine
        )
        SELECT COALESCE(SUM(s.impressions), 0) AS total
        FROM search_visibility_snapshot s
        JOIN engine_latest el ON el.engine = s.engine AND el.latest_date = s.snapshot_date
        WHERE s.site_host = ? AND s.query_text = ''
        """;

    private static final String PAGE_WINDOW_NEAR_SQL = """
        WITH candidate AS (
            SELECT snapshot_date
            FROM search_visibility_snapshot
            WHERE site_host = ? AND page_path = ? AND query_text = ''
              AND snapshot_date BETWEEN ? AND ?
            ORDER BY ABS( snapshot_date - ? ), snapshot_date ASC
            LIMIT 1
        )
        SELECT s.snapshot_date,
               SUM( s.impressions )                                        AS impressions,
               SUM( s.clicks )                                             AS clicks,
               SUM( s.impressions * s.position ) FILTER ( WHERE s.position IS NOT NULL ) AS weighted_position_sum,
               SUM( s.impressions )              FILTER ( WHERE s.position IS NOT NULL ) AS weighted_position_denom
        FROM search_visibility_snapshot s
        JOIN candidate c ON c.snapshot_date = s.snapshot_date
        WHERE s.site_host = ? AND s.page_path = ? AND s.query_text = ''
        GROUP BY s.snapshot_date
        """;

    // Same nearest-single-snapshot rule as PAGE_WINDOW_NEAR_SQL, totalled across every page's
    // rollup row instead of one page's -- the difference-in-differences control term (§7.4.3).
    // page_path <> '' is belt-and-braces alongside query_text = '': a page-rollup row always
    // carries a real page_path (query_text = '' IS the page-level rollup, per V050's own notes),
    // so this excludes site-level query rows (page_path = '') even if query_text = '' somehow
    // co-occurred with page_path = '' in stray data.
    private static final String SITE_WINDOW_NEAR_SQL = """
        WITH candidate AS (
            SELECT snapshot_date
            FROM search_visibility_snapshot
            WHERE site_host = ? AND query_text = '' AND page_path <> ''
              AND snapshot_date BETWEEN ? AND ?
            ORDER BY ABS( snapshot_date - ? ), snapshot_date ASC
            LIMIT 1
        )
        SELECT s.snapshot_date,
               SUM( s.impressions )                                        AS impressions,
               SUM( s.clicks )                                             AS clicks,
               SUM( s.impressions * s.position ) FILTER ( WHERE s.position IS NOT NULL ) AS weighted_position_sum,
               SUM( s.impressions )              FILTER ( WHERE s.position IS NOT NULL ) AS weighted_position_denom
        FROM search_visibility_snapshot s
        JOIN candidate c ON c.snapshot_date = s.snapshot_date
        WHERE s.site_host = ? AND s.query_text = '' AND s.page_path <> ''
        GROUP BY s.snapshot_date
        """;

    private final Jdbc jdbc;

    JdbcVisibilityStore( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    int upsert( final List<VisibilityRow> rows ) {
        if ( rows.isEmpty() ) {
            return 0;
        }

        final List<SqlBinder> binders = new ArrayList<>( rows.size() );
        for ( final VisibilityRow row : rows ) {
            binders.add( ps -> {
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
            } );
        }

        try {
            return JdbcColumns.batchAffected( jdbc, INSERT_SQL, binders );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} visibility rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }

    Optional<LocalDate> latestSnapshotDate( final String siteHost ) {
        try {
            final List<LocalDate> rows = jdbc.query( LATEST_DATE_SQL, ps -> ps.setString( 1, siteHost ),
                    rs -> {
                        final Date latest = rs.getDate( "latest" );
                        return latest == null ? null : latest.toLocalDate();
                    } );
            return rows.isEmpty() || rows.get( 0 ) == null ? Optional.empty() : Optional.of( rows.get( 0 ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest visibility snapshot date for site {}: {}",
                    siteHost, e.getMessage(), e );
            throw new IllegalStateException( "latest visibility snapshot date query failed", e );
        }
    }

    List<EngineTotal> engineTotals( final String siteHost, final LocalDate snapshotDate ) {
        try {
            return jdbc.query( ENGINE_TOTALS_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, PAGE_ROLLUP_QUERY_TEXT );
                        ps.setDate( 3, Date.valueOf( snapshotDate ) );
                    },
                    rs -> new EngineTotal( rs.getString( "engine" ), rs.getLong( "clicks" ),
                            rs.getLong( "impressions" ), getNullableDouble( rs, "avg_position" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read visibility engine totals for site {} date {}: {}",
                    siteHost, snapshotDate, e.getMessage(), e );
            throw new IllegalStateException( "visibility engine totals query failed", e );
        }
    }

    List<TrendPoint> trend( final String siteHost, final LocalDate since ) {
        try {
            return jdbc.query( TREND_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, PAGE_ROLLUP_QUERY_TEXT );
                        ps.setDate( 3, Date.valueOf( since ) );
                    },
                    rs -> new TrendPoint( rs.getDate( "snapshot_date" ).toLocalDate(),
                            rs.getString( "engine" ), rs.getLong( "clicks" ), rs.getLong( "impressions" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read visibility trend for site {} since {}: {}",
                    siteHost, since, e.getMessage(), e );
            throw new IllegalStateException( "visibility trend query failed", e );
        }
    }

    List<VisibilityRow> latestRowsPerEngine( final String siteHost, final int withinDays ) {
        try {
            return jdbc.query( LATEST_ROWS_PER_ENGINE_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, siteHost );
                        ps.setInt( 3, withinDays );
                    },
                    rs -> new VisibilityRow(
                            rs.getDate( "snapshot_date" ).toLocalDate(),
                            rs.getInt( "window_days" ),
                            rs.getString( "engine" ),
                            rs.getString( "site_host" ),
                            rs.getString( "page_path" ),
                            rs.getString( "query_text" ),
                            rs.getInt( "impressions" ),
                            rs.getInt( "clicks" ),
                            getNullableDouble( rs, "position" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest visibility rows per engine for site {} within {} days: {}",
                    siteHost, withinDays, e.getMessage(), e );
            throw new IllegalStateException( "latest rows per engine query failed", e );
        }
    }

    int siteImpressions28d( final String siteHost ) {
        try {
            final List<Integer> rows = jdbc.query( SITE_IMPRESSIONS_28D_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, siteHost );
                    },
                    rs -> rs.getInt( "total" ) );
            return rows.isEmpty() ? 0 : rows.get( 0 );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read site impressions for site {}: {}", siteHost, e.getMessage(), e );
            throw new IllegalStateException( "site impressions query failed", e );
        }
    }

    Optional<PageWindow> pageWindowNear( final String siteHost, final String pagePath,
                                                final LocalDate targetDate, final int toleranceDays ) {
        try {
            return jdbc.queryOne( PAGE_WINDOW_NEAR_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, pagePath );
                        ps.setDate( 3, Date.valueOf( targetDate.minusDays( toleranceDays ) ) );
                        ps.setDate( 4, Date.valueOf( targetDate.plusDays( toleranceDays ) ) );
                        ps.setDate( 5, Date.valueOf( targetDate ) );
                        ps.setString( 6, siteHost );
                        ps.setString( 7, pagePath );
                    },
                    JdbcVisibilityStore::readPageWindow );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to resolve page window near {} (+/-{}d) for site {} page {}: {}",
                    targetDate, toleranceDays, siteHost, pagePath, e.getMessage(), e );
            throw new IllegalStateException( "page window query failed", e );
        }
    }

    Optional<PageWindow> siteWindowNear( final String siteHost, final LocalDate targetDate,
                                                final int toleranceDays ) {
        try {
            return jdbc.queryOne( SITE_WINDOW_NEAR_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setDate( 2, Date.valueOf( targetDate.minusDays( toleranceDays ) ) );
                        ps.setDate( 3, Date.valueOf( targetDate.plusDays( toleranceDays ) ) );
                        ps.setDate( 4, Date.valueOf( targetDate ) );
                        ps.setString( 5, siteHost );
                    },
                    JdbcVisibilityStore::readPageWindow );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to resolve site window near {} (+/-{}d) for site {}: {}",
                    targetDate, toleranceDays, siteHost, e.getMessage(), e );
            throw new IllegalStateException( "site window query failed", e );
        }
    }

    /** Shared row mapper for {@link #pageWindowNear} and {@link #siteWindowNear}'s identical shape. */
    private static PageWindow readPageWindow( final ResultSet rs ) throws SQLException {
        final Double weightedSum = getNullableDouble( rs, "weighted_position_sum" );
        final Double weightedDenom = getNullableDouble( rs, "weighted_position_denom" );
        final Double position = ( weightedSum == null || weightedDenom == null || weightedDenom == 0 )
                ? null
                : weightedSum / weightedDenom;
        return new PageWindow( rs.getDate( "snapshot_date" ).toLocalDate(),
                rs.getInt( "impressions" ), rs.getInt( "clicks" ), position );
    }
}

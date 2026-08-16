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

    private static final String INSERT_CHANGE_SQL = """
        INSERT INTO content_change_log
            (page_path, change_type, opportunity_type, applied_by, note,
             baseline_start, baseline_end, baseline_impressions, baseline_clicks,
             baseline_ctr, baseline_position)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        RETURNING id
        """;

    // applied_at is TIMESTAMPTZ; cutoff is a caller-supplied LocalDate (the nightly evaluator
    // passes today - 28 days per design §7.4.2), so the comparison casts to date rather than
    // requiring the caller to reason about time-of-day.
    private static final String UNEVALUATED_CHANGES_SQL = """
        SELECT id, page_path, change_type, opportunity_type, applied_at, applied_by, note,
               baseline_start, baseline_end, baseline_impressions, baseline_clicks,
               baseline_ctr, baseline_position
        FROM content_change_log
        WHERE evaluated_at IS NULL AND applied_at::date <= ?
        ORDER BY applied_at ASC
        """;

    private static final String SNOOZE_UPSERT_SQL = """
        INSERT INTO content_opportunity_snooze
            (opportunity_type, target, snoozed_until, reason, snoozed_by)
        VALUES (?,?,?,?,?)
        ON CONFLICT (opportunity_type, target)
        DO UPDATE SET snoozed_until = EXCLUDED.snoozed_until,
                      reason        = EXCLUDED.reason,
                      snoozed_by    = EXCLUDED.snoozed_by,
                      created_at    = NOW()
        """;

    private static final String ACTIVE_SNOOZES_SQL = """
        SELECT opportunity_type, target, snoozed_until, reason, snoozed_by
        FROM content_opportunity_snooze
        WHERE snoozed_until >= ?
        """;

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

    @Override
    public Optional<Long> recordChange( final ContentChange change ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_CHANGE_SQL ) ) {
            ps.setString( 1, change.pagePath() );
            ps.setString( 2, change.changeType() );
            setNullableString( ps, 3, change.opportunityType() );
            ps.setString( 4, change.appliedBy() );
            setNullableString( ps, 5, change.note() );
            ps.setDate( 6, Date.valueOf( change.baselineStart() ) );
            ps.setDate( 7, Date.valueOf( change.baselineEnd() ) );
            ps.setInt( 8, change.baselineImpressions() );
            ps.setInt( 9, change.baselineClicks() );
            setNullableDouble( ps, 10, change.baselineCtr() );
            setNullableDouble( ps, 11, change.baselinePosition() );

            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( rs.getLong( "id" ) ) : Optional.empty();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to record content change for page {}: {}",
                    change.pagePath(), e.getMessage(), e );
            return Optional.empty();
        }
    }

    @Override
    public List<PendingChange> unevaluatedChanges( final LocalDate cutoff ) {
        final List<PendingChange> out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( UNEVALUATED_CHANGES_SQL ) ) {
            ps.setDate( 1, Date.valueOf( cutoff ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( new PendingChange(
                            rs.getLong( "id" ),
                            rs.getString( "page_path" ),
                            rs.getString( "change_type" ),
                            rs.getString( "opportunity_type" ),
                            rs.getTimestamp( "applied_at" ).toInstant(),
                            rs.getString( "applied_by" ),
                            rs.getString( "note" ),
                            rs.getDate( "baseline_start" ).toLocalDate(),
                            rs.getDate( "baseline_end" ).toLocalDate(),
                            rs.getInt( "baseline_impressions" ),
                            rs.getInt( "baseline_clicks" ),
                            getNullableDouble( rs, "baseline_ctr" ),
                            getNullableDouble( rs, "baseline_position" ) ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read unevaluated content changes at or before {}: {}",
                    cutoff, e.getMessage(), e );
            throw new IllegalStateException( "unevaluated content changes query failed", e );
        }
        return out;
    }

    @Override
    public boolean snooze( final OpportunitySnooze snooze ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( SNOOZE_UPSERT_SQL ) ) {
            ps.setString( 1, snooze.opportunityType() );
            ps.setString( 2, snooze.target() );
            ps.setDate( 3, Date.valueOf( snooze.snoozedUntil() ) );
            ps.setString( 4, snooze.reason() );
            ps.setString( 5, snooze.snoozedBy() );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to write snooze for type={} target={}: {}",
                    snooze.opportunityType(), snooze.target(), e.getMessage(), e );
            return false;
        }
    }

    @Override
    public List<OpportunitySnooze> activeSnoozes( final LocalDate today ) {
        final List<OpportunitySnooze> out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( ACTIVE_SNOOZES_SQL ) ) {
            ps.setDate( 1, Date.valueOf( today ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( new OpportunitySnooze(
                            rs.getString( "opportunity_type" ),
                            rs.getString( "target" ),
                            rs.getDate( "snoozed_until" ).toLocalDate(),
                            rs.getString( "reason" ),
                            rs.getString( "snoozed_by" ) ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read active snoozes as of {}: {}", today, e.getMessage(), e );
            throw new IllegalStateException( "active snoozes query failed", e );
        }
        return out;
    }

    private static void setNullableString( final PreparedStatement ps, final int index, final String value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.VARCHAR );
        } else {
            ps.setString( index, value );
        }
    }

    private static void setNullableDouble( final PreparedStatement ps, final int index, final Double value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.NUMERIC );
        } else {
            ps.setDouble( index, value );
        }
    }

    private static Double getNullableDouble( final ResultSet rs, final String column ) throws SQLException {
        final double value = rs.getDouble( column );
        return rs.wasNull() ? null : value;
    }
}

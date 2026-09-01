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

import com.google.gson.Gson;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
             baseline_ctr, baseline_position, predicted_priority)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        RETURNING id
        """;

    // applied_at is TIMESTAMPTZ; cutoff is a caller-supplied LocalDate (the nightly evaluator
    // passes today - 28 days per design §7.4.2), so the comparison casts to date rather than
    // requiring the caller to reason about time-of-day.
    //
    // AT TIME ZONE 'UTC' before the ::date cast, and NOT applied_at::date: applied_at is a
    // TIMESTAMPTZ, so a bare cast resolves against the *session* timezone and silently shifts
    // the date by a day on any server not running UTC (pgjdbc sets the session zone from the
    // JVM default). The change dates these queries return line up the before/after windows in
    // EffectEvaluator, so a one-day shift mis-attributes every measured effect.
    private static final String UNEVALUATED_CHANGES_SQL = """
        SELECT id, page_path, change_type, opportunity_type, applied_at, applied_by, note,
               baseline_start, baseline_end, baseline_impressions, baseline_clicks,
               baseline_ctr, baseline_position
        FROM content_change_log
        WHERE evaluated_at IS NULL AND (applied_at AT TIME ZONE 'UTC')::date <= ?
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

    // Grouping (distinct sessions, average result count, coverage mode) happens in Java --
    // content-intelligence data volumes are small (V054), and a categorical mode plus a
    // DISTINCT-count is simpler to get right in code than in a single SQL pass.
    private static final String DEMAND_ROWS_SQL = """
        SELECT query_text, session_hash, result_count, coverage
        FROM retrieval_query_log
        WHERE created_at >= ?
        """;

    private static final String LAST_CHANGE_BY_TARGET_SQL = """
        SELECT page_path, MAX((applied_at AT TIME ZONE 'UTC')::date) AS last_date
        FROM content_change_log
        GROUP BY page_path
        """;

    private static final String RECORD_EFFECT_SQL = """
        UPDATE content_change_log
        SET evaluated_at          = NOW(),
            effect                = ?,
            effect_ctr_delta      = ?,
            effect_position_delta = ?,
            effect_click_delta    = ?,
            effect_method         = ?,
            effect_detail         = CAST(? AS jsonb)
        WHERE id = ?
        """;

    // Mirrors idx_ccl_calibration's own filter (evaluated_at IS NOT NULL AND opportunity_type
    // IS NOT NULL) so this query rides that partial index; effect <> 'insufficient_data' further
    // restricts to rows with a real verdict, since "insufficient_data" is not a calibratable one.
    private static final String VERDICT_COUNTS_BY_TYPE_SQL = """
        SELECT opportunity_type, COUNT(*) AS n
        FROM content_change_log
        WHERE evaluated_at IS NOT NULL
          AND opportunity_type IS NOT NULL
          AND effect IS NOT NULL
          AND effect <> 'insufficient_data'
        GROUP BY opportunity_type
        """;

    private static final String CALIBRATION_SAMPLES_SQL = """
        SELECT opportunity_type, predicted_priority, effect_click_delta
        FROM content_change_log
        WHERE evaluated_at IS NOT NULL
          AND opportunity_type IS NOT NULL
          AND predicted_priority IS NOT NULL
          AND effect_click_delta IS NOT NULL
        """;

    // first_seen is bound to `today` for a fresh row and left untouched on conflict (the DO
    // UPDATE only ever touches last_seen), so RETURNING first_seen always reports the preserved
    // value. Keying the output map off the RETURNING columns themselves -- rather than assuming
    // JDBC batch result ordering matches input ordering -- is deliberate; see upsertSeen().
    private static final String UPSERT_SEEN_SQL = """
        INSERT INTO content_opportunity_seen (opportunity_type, target, first_seen, last_seen)
        VALUES (?,?,?,?)
        ON CONFLICT (opportunity_type, target)
        DO UPDATE SET last_seen = EXCLUDED.last_seen
        RETURNING opportunity_type, target, first_seen
        """;

    // THE CRITICAL SEMANTIC (design §7.4.2): every row here is ALREADY a trailing 28-day
    // aggregate stamped with its window's END date -- never a daily figure. "The 28 days after a
    // change" is therefore the SINGLE snapshot nearest the target date, not a SUM over snapshot
    // dates; summing would count the same underlying days once per overlapping window and inflate
    // the after-figure several-fold. The `candidate` CTE picks exactly one snapshot_date (nearest
    // targetDate, ties toward the earlier date via the two-key ORDER BY); the outer SELECT then
    // aggregates ONLY across engines for that one date. Do not change GROUP BY / add a date range
    // to the outer SELECT without re-reading §7.4.2 -- that would silently reintroduce the
    // double-count this method exists to avoid.
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

    private static final String UPSERT_IMPORTED_SQL = """
        INSERT INTO imported_opportunity
            (as_of, engine, site_host, opportunity_type, target, expected_uplift, confidence, evidence)
        VALUES (?,?,?,?,?,?,?, CAST(? AS jsonb))
        ON CONFLICT (as_of, engine, site_host, opportunity_type, target)
        DO UPDATE SET expected_uplift = EXCLUDED.expected_uplift,
                      confidence      = EXCLUDED.confidence,
                      evidence        = EXCLUDED.evidence
        """;

    // Single-CTE "most recent as_of" resolution, same shape as LATEST_DATE_SQL. The final WHERE's
    // l.max_as_of >= ? is the staleness guard: if the site's newest as_of is older than the caller's
    // cutoff (today - maxAgeDays), the JOIN still resolves max_as_of but the WHERE excludes every
    // row, so this returns empty rather than the stale row set -- see latestImported()'s javadoc.
    private static final String LATEST_IMPORTED_SQL = """
        WITH latest AS (
            SELECT MAX(as_of) AS max_as_of
            FROM imported_opportunity
            WHERE site_host = ?
        )
        SELECT io.as_of, io.engine, io.site_host, io.opportunity_type, io.target,
               io.expected_uplift, io.confidence, io.evidence
        FROM imported_opportunity io
        JOIN latest l ON l.max_as_of = io.as_of
        WHERE io.site_host = ? AND l.max_as_of >= ?
        """;

    private static final String UPSERT_CTR_CURVE_SQL = """
        INSERT INTO expected_ctr_curve (as_of, position, ctr)
        VALUES (?,?,?)
        ON CONFLICT (as_of, position) DO UPDATE SET ctr = EXCLUDED.ctr
        """;

    // Same "most recent as_of, guarded by a staleness cutoff" shape as LATEST_IMPORTED_SQL, minus
    // the site_host dimension -- jakemon ships exactly one curve, not one per site (V057).
    private static final String LATEST_CTR_CURVE_SQL = """
        WITH latest AS (
            SELECT MAX(as_of) AS max_as_of
            FROM expected_ctr_curve
        )
        SELECT ecc.as_of, ecc.position, ecc.ctr
        FROM expected_ctr_curve ecc
        JOIN latest l ON l.max_as_of = ecc.as_of
        WHERE l.max_as_of >= ?
        """;

    private static final Gson GSON = new Gson();

    private final Jdbc jdbc;

    /**
     * Creates a new JdbcInsightsStore backed by the given DataSource.
     *
     * @param dataSource the data source to use for connections
     */
    public JdbcInsightsStore( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    @Override
    public int upsert( final List<VisibilityRow> rows ) {
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
            final int[] results = jdbc.withConnection( conn -> jdbc.batch( conn, INSERT_SQL, binders ) );
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

    @Override
    public List<EngineTotal> engineTotals( final String siteHost, final LocalDate snapshotDate ) {
        try {
            return jdbc.query( ENGINE_TOTALS_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, PAGE_ROLLUP_QUERY_TEXT );
                        ps.setDate( 3, Date.valueOf( snapshotDate ) );
                    },
                    rs -> {
                        final double avgPosition = rs.getDouble( "avg_position" );
                        final Double position = rs.wasNull() ? null : avgPosition;
                        return new EngineTotal( rs.getString( "engine" ), rs.getLong( "clicks" ),
                                rs.getLong( "impressions" ), position );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read visibility engine totals for site {} date {}: {}",
                    siteHost, snapshotDate, e.getMessage(), e );
            throw new IllegalStateException( "visibility engine totals query failed", e );
        }
    }

    @Override
    public List<TrendPoint> trend( final String siteHost, final LocalDate since ) {
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

    @Override
    public Optional<Long> recordChange( final ContentChange change ) {
        try {
            return jdbc.queryOne( INSERT_CHANGE_SQL,
                    ps -> {
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
                        setNullableDouble( ps, 12, change.predictedPriority() );
                    },
                    rs -> rs.getLong( "id" ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to record content change for page {}: {}",
                    change.pagePath(), e.getMessage(), e );
            return Optional.empty();
        }
    }

    @Override
    public List<PendingChange> unevaluatedChanges( final LocalDate cutoff ) {
        try {
            return jdbc.query( UNEVALUATED_CHANGES_SQL, ps -> ps.setDate( 1, Date.valueOf( cutoff ) ),
                    rs -> new PendingChange(
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
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read unevaluated content changes at or before {}: {}",
                    cutoff, e.getMessage(), e );
            throw new IllegalStateException( "unevaluated content changes query failed", e );
        }
    }

    @Override
    public boolean snooze( final OpportunitySnooze snooze ) {
        try {
            return jdbc.update( SNOOZE_UPSERT_SQL, ps -> {
                ps.setString( 1, snooze.opportunityType() );
                ps.setString( 2, snooze.target() );
                ps.setDate( 3, Date.valueOf( snooze.snoozedUntil() ) );
                ps.setString( 4, snooze.reason() );
                ps.setString( 5, snooze.snoozedBy() );
            } ) > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to write snooze for type={} target={}: {}",
                    snooze.opportunityType(), snooze.target(), e.getMessage(), e );
            return false;
        }
    }

    @Override
    public List<OpportunitySnooze> activeSnoozes( final LocalDate today ) {
        try {
            return jdbc.query( ACTIVE_SNOOZES_SQL, ps -> ps.setDate( 1, Date.valueOf( today ) ),
                    rs -> new OpportunitySnooze(
                            rs.getString( "opportunity_type" ),
                            rs.getString( "target" ),
                            rs.getDate( "snoozed_until" ).toLocalDate(),
                            rs.getString( "reason" ),
                            rs.getString( "snoozed_by" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read active snoozes as of {}: {}", today, e.getMessage(), e );
            throw new IllegalStateException( "active snoozes query failed", e );
        }
    }

    @Override
    public List<VisibilityRow> latestRowsPerEngine( final String siteHost, final int withinDays ) {
        try {
            return jdbc.query( LATEST_ROWS_PER_ENGINE_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, siteHost );
                        ps.setInt( 3, withinDays );
                    },
                    rs -> {
                        final double position = rs.getDouble( "position" );
                        return new VisibilityRow(
                                rs.getDate( "snapshot_date" ).toLocalDate(),
                                rs.getInt( "window_days" ),
                                rs.getString( "engine" ),
                                rs.getString( "site_host" ),
                                rs.getString( "page_path" ),
                                rs.getString( "query_text" ),
                                rs.getInt( "impressions" ),
                                rs.getInt( "clicks" ),
                                rs.wasNull() ? null : position );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest visibility rows per engine for site {} within {} days: {}",
                    siteHost, withinDays, e.getMessage(), e );
            throw new IllegalStateException( "latest rows per engine query failed", e );
        }
    }

    @Override
    public int siteImpressions28d( final String siteHost ) {
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

    @Override
    public List<DemandRow> demandRows( final int sinceDays ) {
        final Instant cutoff = Instant.now().minus( sinceDays, ChronoUnit.DAYS );
        final Map<String, DemandAggregate> byQuery = new LinkedHashMap<>();

        try {
            jdbc.forEachRow( DEMAND_ROWS_SQL, ps -> ps.setTimestamp( 1, Timestamp.from( cutoff ) ), 0, rs -> {
                final String queryText = rs.getString( "query_text" );
                final DemandAggregate agg = byQuery.computeIfAbsent( queryText, k -> new DemandAggregate() );

                agg.occurrences++;

                final String sessionHash = rs.getString( "session_hash" );
                if ( sessionHash != null ) {
                    agg.distinctSessions.add( sessionHash );
                }

                final int resultCount = rs.getInt( "result_count" );
                if ( !rs.wasNull() ) {
                    agg.resultCountSum += resultCount;
                    agg.resultCountCount++;
                }

                final String coverage = rs.getString( "coverage" );
                if ( coverage != null ) {
                    agg.coverageCounts.merge( coverage, 1, Integer::sum );
                }
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read demand rows for the last {} days: {}",
                    sinceDays, e.getMessage(), e );
            throw new IllegalStateException( "demand rows query failed", e );
        }

        final List<DemandRow> out = new ArrayList<>();
        for ( final Map.Entry<String, DemandAggregate> entry : byQuery.entrySet() ) {
            out.add( entry.getValue().toDemandRow( entry.getKey() ) );
        }
        return out;
    }

    @Override
    public Map<String, LocalDate> lastChangeByTarget() {
        final Map<String, LocalDate> out = new HashMap<>();
        try {
            jdbc.forEachRow( LAST_CHANGE_BY_TARGET_SQL, SqlBinder.NONE, 0,
                    rs -> out.put( rs.getString( "page_path" ), rs.getDate( "last_date" ).toLocalDate() ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read last change by target: {}", e.getMessage(), e );
            throw new IllegalStateException( "last change by target query failed", e );
        }
        return out;
    }

    @Override
    public boolean recordEffect( final long changeId, final String verdict, final Double ctrDelta,
                                 final Double positionDelta, final Double clickDelta,
                                 final String method, final String detailJson ) {
        try {
            return jdbc.update( RECORD_EFFECT_SQL, ps -> {
                ps.setString( 1, verdict );
                setNullableDouble( ps, 2, ctrDelta );
                setNullableDouble( ps, 3, positionDelta );
                setNullableDouble( ps, 4, clickDelta );
                setNullableString( ps, 5, method );
                setNullableString( ps, 6, detailJson );
                ps.setLong( 7, changeId );
            } ) > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to record effect for change {}: {}", changeId, e.getMessage(), e );
            return false;
        }
    }

    @Override
    public Map<String, Integer> verdictCountsByType() {
        final Map<String, Integer> out = new HashMap<>();
        try {
            jdbc.forEachRow( VERDICT_COUNTS_BY_TYPE_SQL, SqlBinder.NONE, 0,
                    rs -> out.put( rs.getString( "opportunity_type" ), rs.getInt( "n" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read verdict counts by type: {}", e.getMessage(), e );
            throw new IllegalStateException( "verdict counts by type query failed", e );
        }
        return out;
    }

    @Override
    public List<CalibrationSample> calibrationSamples() {
        try {
            return jdbc.query( CALIBRATION_SAMPLES_SQL, SqlBinder.NONE,
                    rs -> new CalibrationSample(
                            rs.getString( "opportunity_type" ),
                            rs.getDouble( "predicted_priority" ),
                            rs.getDouble( "effect_click_delta" ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read calibration samples: {}", e.getMessage(), e );
            throw new IllegalStateException( "calibration samples query failed", e );
        }
    }

    @Override
    public Map<String, LocalDate> upsertSeen( final List<String[]> typeTargetPairs, final LocalDate today ) {
        final Map<String, LocalDate> out = new HashMap<>();
        if ( typeTargetPairs.isEmpty() ) {
            return out;
        }

        final Date todaySql = Date.valueOf( today );
        final List<SqlBinder> binders = new ArrayList<>( typeTargetPairs.size() );
        for ( final String[] pair : typeTargetPairs ) {
            binders.add( ps -> {
                ps.setString( 1, pair[0] );
                ps.setString( 2, pair[1] );
                ps.setDate( 3, todaySql );
                ps.setDate( 4, todaySql );
            } );
        }

        try {
            final List<SeenRow> rows = jdbc.batchReturningKeys( UPSERT_SEEN_SQL, binders,
                    rs -> new SeenRow( rs.getString( "opportunity_type" ), rs.getString( "target" ),
                            rs.getDate( "first_seen" ).toLocalDate() ) );
            for ( final SeenRow row : rows ) {
                out.put( row.opportunityType() + " " + row.target(), row.firstSeen() );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} content-opportunity-seen rows: {}",
                    typeTargetPairs.size(), e.getMessage(), e );
            return new HashMap<>();
        }
        return out;
    }

    @Override
    public Optional<PageWindow> pageWindowNear( final String siteHost, final String pagePath,
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
                    JdbcInsightsStore::readPageWindow );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to resolve page window near {} (+/-{}d) for site {} page {}: {}",
                    targetDate, toleranceDays, siteHost, pagePath, e.getMessage(), e );
            throw new IllegalStateException( "page window query failed", e );
        }
    }

    @Override
    public Optional<PageWindow> siteWindowNear( final String siteHost, final LocalDate targetDate,
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
                    JdbcInsightsStore::readPageWindow );
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

    @Override
    public int upsertImported( final List<ImportedOpportunityRow> rows ) {
        if ( rows.isEmpty() ) {
            return 0;
        }

        final List<SqlBinder> binders = new ArrayList<>( rows.size() );
        for ( final ImportedOpportunityRow row : rows ) {
            binders.add( ps -> {
                ps.setDate( 1, Date.valueOf( row.asOf() ) );
                ps.setString( 2, row.engine() );
                ps.setString( 3, row.siteHost() );
                ps.setString( 4, row.opportunityType() );
                ps.setString( 5, row.target() );
                ps.setDouble( 6, row.expectedUplift() );
                setNullableDouble( ps, 7, row.confidence() );
                setNullableString( ps, 8, row.evidenceJson() );
            } );
        }

        try {
            final int[] results = jdbc.withConnection( conn -> jdbc.batch( conn, UPSERT_IMPORTED_SQL, binders ) );
            int total = 0;
            for ( final int result : results ) {
                total += Math.max( result, 0 );
            }
            return total;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} imported opportunity rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }

    @Override
    public List<Opportunity> latestImported( final String siteHost, final int maxAgeDays ) {
        try {
            return jdbc.query( LATEST_IMPORTED_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, siteHost );
                        ps.setDate( 3, Date.valueOf( LocalDate.now().minusDays( maxAgeDays ) ) );
                    },
                    JdbcInsightsStore::toImportedOpportunity );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest imported opportunities for site {}: {}",
                    siteHost, e.getMessage(), e );
            throw new IllegalStateException( "latest imported opportunities query failed", e );
        }
    }

    /**
     * Maps one {@code imported_opportunity} row to {@link Opportunity}. The evidence map is the
     * stored JSONB parsed back to a {@code Map}, plus {@code engine} and {@code confidence}
     * entries -- those two are separate typed columns, not part of {@code evidenceJson}, but
     * {@link OpportunityEngine#suppressDivergenceAffected} reads an {@code "engine"} evidence key
     * to match imported {@code ctr_gap}/{@code striking_distance} rows against a divergence
     * finding, so it has to be re-added here on the read path.
     */
    private static Opportunity toImportedOpportunity( final ResultSet rs ) throws SQLException {
        final Map<String, Object> evidence = new LinkedHashMap<>();
        final String evidenceJson = rs.getString( "evidence" );
        if ( evidenceJson != null && !evidenceJson.isBlank() ) {
            final Map<?, ?> parsed = GSON.fromJson( evidenceJson, Map.class );
            if ( parsed != null ) {
                for ( final Map.Entry<?, ?> entry : parsed.entrySet() ) {
                    evidence.put( String.valueOf( entry.getKey() ), entry.getValue() );
                }
            }
        }
        evidence.put( "engine", rs.getString( "engine" ) );
        evidence.put( "confidence", getNullableDouble( rs, "confidence" ) );

        final String type = rs.getString( "opportunity_type" );
        return new Opportunity( type, rs.getString( "target" ), rs.getDouble( "expected_uplift" ),
                evidence, suggestedActionForImportedType( type ),
                rs.getDate( "as_of" ).toLocalDate(), false );
    }

    @Override
    public int upsertCtrCurve( final LocalDate asOf, final Map<Integer, Double> points ) {
        if ( points.isEmpty() ) {
            return 0;
        }

        final List<SqlBinder> binders = new ArrayList<>( points.size() );
        for ( final Map.Entry<Integer, Double> point : points.entrySet() ) {
            binders.add( ps -> {
                ps.setDate( 1, Date.valueOf( asOf ) );
                ps.setInt( 2, point.getKey() );
                ps.setDouble( 3, point.getValue() );
            } );
        }

        try {
            final int[] results = jdbc.withConnection( conn -> jdbc.batch( conn, UPSERT_CTR_CURVE_SQL, binders ) );
            int total = 0;
            for ( final int result : results ) {
                total += Math.max( result, 0 );
            }
            return total;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} expected-CTR curve points for as_of {}: {}",
                    points.size(), asOf, e.getMessage(), e );
            return 0;
        }
    }

    @Override
    public Optional<CtrCurveSnapshot> latestCtrCurve( final int maxAgeDays ) {
        final LocalDate[] asOf = { null };
        final Map<Integer, Double> points = new LinkedHashMap<>();
        try {
            jdbc.forEachRow( LATEST_CTR_CURVE_SQL,
                    ps -> ps.setDate( 1, Date.valueOf( LocalDate.now().minusDays( maxAgeDays ) ) ), 0,
                    rs -> {
                        asOf[0] = rs.getDate( "as_of" ).toLocalDate();
                        points.put( rs.getInt( "position" ), rs.getDouble( "ctr" ) );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read latest expected-CTR curve: {}", e.getMessage(), e );
            throw new IllegalStateException( "latest expected-CTR curve query failed", e );
        }
        return asOf[0] == null ? Optional.empty() : Optional.of( new CtrCurveSnapshot( asOf[0], points ) );
    }

    /** One short suggested action per jakemon detector type (design §7.3 "Imported from jakemon"). */
    private static String suggestedActionForImportedType( final String type ) {
        return switch ( type ) {
            case "striking_distance" ->
                    "Page-2 query with real demand -- a small rank push wins the most clicks.";
            case "ctr_gap" -> "Ranks well but converts badly -- rewrite the title/meta snippet.";
            case "content_gap" ->
                    "Demand exists and no strong page covers it -- write or strengthen a page for this query.";
            case "cannibalization" ->
                    "Multiple pages split this query's equity -- consolidate or differentiate them.";
            case "decay" -> "Average position worsened materially against the prior window -- investigate.";
            default -> "Review this imported opportunity.";
        };
    }

    /** Accumulator for {@link #demandRows}; one instance per distinct {@code query_text}. */
    private static final class DemandAggregate {
        private int occurrences;
        private final Set<String> distinctSessions = new HashSet<>();
        private long resultCountSum;
        private int resultCountCount;
        private final Map<String, Integer> coverageCounts = new LinkedHashMap<>();

        private DemandRow toDemandRow( final String queryText ) {
            final int avgResultCount = resultCountCount == 0
                    ? 0
                    : (int) Math.round( (double) resultCountSum / resultCountCount );
            return new DemandRow( queryText, topCoverage(), avgResultCount,
                    occurrences, distinctSessions.size() );
        }

        /** The most frequent non-null coverage value seen for this query, or {@code null} if none. */
        private String topCoverage() {
            String best = null;
            int bestCount = 0;
            for ( final Map.Entry<String, Integer> entry : coverageCounts.entrySet() ) {
                if ( entry.getValue() > bestCount
                        || ( entry.getValue() == bestCount && best != null
                             && entry.getKey().compareTo( best ) < 0 ) ) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            return best;
        }
    }

    /** One RETURNING row from {@link #UPSERT_SEEN_SQL}; see {@link #upsertSeen} for why this is keyed off it. */
    private record SeenRow( String opportunityType, String target, LocalDate firstSeen ) {
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

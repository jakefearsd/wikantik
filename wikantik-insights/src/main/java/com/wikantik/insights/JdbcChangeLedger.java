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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getNullableDouble;
import static com.wikantik.jdbc.NullableColumns.setNullableDouble;
import static com.wikantik.jdbc.NullableColumns.setNullableString;

/**
 * The content-change ledger: recorded changes, their measured effects and calibration inputs,
 * opportunity snoozes and the first-seen log. Held by {@link JdbcInsightsStore}.
 */
final class JdbcChangeLedger {

    private static final Logger LOG = LogManager.getLogger( JdbcChangeLedger.class );

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

    private final Jdbc jdbc;

    JdbcChangeLedger( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    Optional<Long> recordChange( final ContentChange change ) {
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

    List<PendingChange> unevaluatedChanges( final LocalDate cutoff ) {
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

    boolean snooze( final OpportunitySnooze snooze ) {
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

    List<OpportunitySnooze> activeSnoozes( final LocalDate today ) {
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

    Map<String, LocalDate> lastChangeByTarget() {
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

    boolean recordEffect( final long changeId, final String verdict, final Double ctrDelta,
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

    Map<String, Integer> verdictCountsByType() {
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

    List<CalibrationSample> calibrationSamples() {
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

    Map<String, LocalDate> upsertSeen( final List<String[]> typeTargetPairs, final LocalDate today ) {
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

    /** One RETURNING row from {@link #UPSERT_SEEN_SQL}; see {@link #upsertSeen} for why this is keyed off it. */
    private record SeenRow( String opportunityType, String target, LocalDate firstSeen ) {
    }
}

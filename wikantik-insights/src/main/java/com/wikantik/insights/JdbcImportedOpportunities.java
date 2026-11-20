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

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wikantik.jdbc.NullableColumns.getNullableDouble;
import static com.wikantik.jdbc.NullableColumns.setNullableDouble;
import static com.wikantik.jdbc.NullableColumns.setNullableString;

/**
 * Opportunities imported from jakemon: the upsert path and the freshness-guarded read. Held by
 * {@link JdbcInsightsStore}.
 */
final class JdbcImportedOpportunities {

    private static final Logger LOG = LogManager.getLogger( JdbcImportedOpportunities.class );

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

    private static final Gson GSON = new Gson();

    private final Jdbc jdbc;

    JdbcImportedOpportunities( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    int upsertImported( final List<ImportedOpportunityRow> rows ) {
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
            return JdbcColumns.batchAffected( jdbc, UPSERT_IMPORTED_SQL, binders );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} imported opportunity rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }

    List<Opportunity> latestImported( final String siteHost, final int maxAgeDays ) {
        try {
            return jdbc.query( LATEST_IMPORTED_SQL,
                    ps -> {
                        ps.setString( 1, siteHost );
                        ps.setString( 2, siteHost );
                        ps.setDate( 3, Date.valueOf( LocalDate.now().minusDays( maxAgeDays ) ) );
                    },
                    JdbcImportedOpportunities::toImportedOpportunity );
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
                evidence, ImportedOpportunityActions.suggestedAction( type ),
                rs.getDate( "as_of" ).toLocalDate(), false );
    }
}

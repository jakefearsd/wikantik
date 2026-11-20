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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The expected-CTR curve jakemon ships: upsert and the freshness-guarded read. Held by
 * {@link JdbcInsightsStore}.
 */
final class JdbcCtrCurves {

    private static final Logger LOG = LogManager.getLogger( JdbcCtrCurves.class );

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

    private final Jdbc jdbc;

    JdbcCtrCurves( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    int upsertCtrCurve( final LocalDate asOf, final Map<Integer, Double> points ) {
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
            return JdbcColumns.batchAffected( jdbc, UPSERT_CTR_CURVE_SQL, binders );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} expected-CTR curve points for as_of {}: {}",
                    points.size(), asOf, e.getMessage(), e );
            return 0;
        }
    }

    Optional<CtrCurveSnapshot> latestCtrCurve( final int maxAgeDays ) {
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
}

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

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads retrieval-query demand, aggregated per query text. Held by {@link JdbcInsightsStore}.
 */
final class JdbcDemandStore {

    private static final Logger LOG = LogManager.getLogger( JdbcDemandStore.class );

    // Grouping (distinct sessions, average result count, coverage mode) happens in Java --
    // content-intelligence data volumes are small (V054), and a categorical mode plus a
    // DISTINCT-count is simpler to get right in code than in a single SQL pass.
    private static final String DEMAND_ROWS_SQL = """
        SELECT query_text, session_hash, result_count, coverage
        FROM retrieval_query_log
        WHERE created_at >= ?
        """;

    private final Jdbc jdbc;

    JdbcDemandStore( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    List<DemandRow> demandRows( final int sinceDays ) {
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
}

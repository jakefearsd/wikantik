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
package com.wikantik.knowledge.querylog;

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC {@link QueryLogReader}. Aggregates {@code retrieval_query_log} by query text over a window.
 * The miss filter is written as {@code SUM(result_count) <= M * COUNT(result_count)} (the
 * integer-only algebraic form of {@code AVG(result_count) <= M}) so it is portable across H2 and
 * PostgreSQL. A read failure is logged and rethrown — the calling MCP tool surfaces it to the agent.
 */
public final class JdbcQueryLogReader implements QueryLogReader {

    private static final Logger LOG = LogManager.getLogger( JdbcQueryLogReader.class );

    private final DataSource dataSource;

    public JdbcQueryLogReader( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    @Override
    public List< AggregatedQuery > topQueries( final QueryLogQuery q ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT query_text, COUNT(*) AS occurrences, "
              + "SUM(result_count) AS sum_results, COUNT(result_count) AS counted_results, "
              + "SUM(CASE WHEN result_count = 0 THEN 1 ELSE 0 END) AS zero_results, "
              + "MAX(created_at) AS last_seen "
              + "FROM retrieval_query_log WHERE created_at >= ?" );
        final List< Object > params = new ArrayList<>();
        params.add( Timestamp.from( q.since() ) );
        if ( q.actor() != null ) {
            sql.append( " AND actor_type = ?" );
            params.add( q.actor().wire() );
        }
        if ( q.surface() != null ) {
            sql.append( " AND source_surface = ?" );
            params.add( q.surface().wire() );
        }
        sql.append( " GROUP BY query_text HAVING COUNT(*) >= ?" );
        params.add( q.minOccurrences() );
        if ( q.maxAvgResultCount() != null ) {
            sql.append( " AND SUM(result_count) <= ? * COUNT(result_count)" );
            params.add( q.maxAvgResultCount() );
        }
        sql.append( " ORDER BY occurrences DESC, last_seen DESC LIMIT ?" );
        params.add( q.limit() );

        final List< AggregatedQuery > out = new ArrayList<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final long counted = rs.getLong( "counted_results" );
                    final long sum = rs.getLong( "sum_results" );
                    final double avg = counted > 0 ? ( double ) sum / counted : 0.0;
                    final Timestamp ts = rs.getTimestamp( "last_seen" );
                    out.add( new AggregatedQuery(
                            rs.getString( "query_text" ),
                            rs.getLong( "occurrences" ),
                            avg,
                            rs.getLong( "zero_results" ),
                            ts != null ? ts.toInstant() : null ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Retrieval query-log read failed: {}", e.getMessage() );
            throw new IllegalStateException( "Failed to read retrieval query log", e );
        }
        return out;
    }
}

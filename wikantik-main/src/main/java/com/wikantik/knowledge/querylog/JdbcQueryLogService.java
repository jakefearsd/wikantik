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

import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.concurrent.Executor;

/**
 * JDBC {@link QueryLogService}. The write is dispatched to an injected {@link Executor}
 * (production: a bounded single-thread pool) so it never runs on the retrieval request
 * thread, and every failure is swallowed with a {@code LOG.warn} — a flaky log can never
 * slow or break search. No-op when disabled or the query text is blank.
 */
public final class JdbcQueryLogService implements QueryLogService {

    private static final Logger LOG = LogManager.getLogger( JdbcQueryLogService.class );

    private static final String INSERT_SQL =
        "INSERT INTO retrieval_query_log (query_text, actor_type, source_surface, result_count) "
        + "VALUES (?, ?, ?, ?)";

    private final DataSource dataSource;
    private final boolean enabled;
    private final Executor executor;

    public JdbcQueryLogService( final DataSource dataSource, final boolean enabled, final Executor executor ) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.executor = executor;
    }

    @Override
    public void log( final String query, final ActorType actor, final SourceSurface surface,
                     final Integer resultCount ) {
        if ( !enabled || query == null || query.isBlank() ) {
            return;
        }
        try {
            executor.execute( () -> insert( query, actor, surface, resultCount ) );
        } catch ( final RuntimeException e ) {
            // e.g. RejectedExecutionException when the queue is saturated — drop, never propagate.
            LOG.warn( "Query-log dispatch dropped for surface={}: {}", surface, e.getMessage() );
        }
    }

    private void insert( final String query, final ActorType actor, final SourceSurface surface,
                         final Integer resultCount ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {
            ps.setString( 1, query );
            ps.setString( 2, actor.wire() );
            ps.setString( 3, surface.wire() );
            if ( resultCount == null ) {
                ps.setNull( 4, Types.INTEGER );
            } else {
                ps.setInt( 4, resultCount );
            }
            ps.executeUpdate();
        } catch ( final Exception e ) {
            LOG.warn( "Query-log write failed for surface={}: {}", surface, e.getMessage() );
        }
    }
}

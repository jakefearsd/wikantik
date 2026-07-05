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

import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingLogService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.Executor;

/**
 * JDBC {@link BriefingLogService}. The write is dispatched to an injected {@link Executor}
 * (production: a bounded single-thread pool) so it never runs on the briefing request thread,
 * and every failure is swallowed with a {@code LOG.warn} — a flaky log can never slow or break
 * briefing assembly. No-op when disabled or the entry is {@code null}.
 */
public final class JdbcBriefingLogService implements BriefingLogService {

    private static final Logger LOG = LogManager.getLogger( JdbcBriefingLogService.class );

    private static final String INSERT_SQL =
        "INSERT INTO briefing_log (pins, clusters, prompt_present, budget_requested, budget_used, "
        + "section_count, pin_count, pointer_count, surface) VALUES (?,?,?,?,?,?,?,?,?)";

    /** Cap on the caller-supplied pins/clusters strings — the columns are unbounded TEXT and the
     *  input is anonymous, so a pathological request can't stuff megabytes into a log row. */
    private static final int MAX_TEXT_LEN = 2000;

    private final DataSource dataSource;
    private final boolean enabled;
    private final Executor executor;

    public JdbcBriefingLogService( final DataSource dataSource, final boolean enabled, final Executor executor ) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.executor = executor;
    }

    @Override
    public void log( final BriefingLogEntry entry ) {
        if ( !enabled || entry == null ) {
            return;
        }
        try {
            executor.execute( () -> insert( entry ) );
        } catch ( final RuntimeException e ) {
            // e.g. RejectedExecutionException when the queue is saturated — drop, never propagate.
            LOG.warn( "Briefing-log dispatch dropped for surface={}: {}", entry.surface(), e.getMessage() );
        }
    }

    private void insert( final BriefingLogEntry entry ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {
            ps.setString( 1, truncate( entry.pins() ) );
            ps.setString( 2, truncate( entry.clusters() ) );
            ps.setBoolean( 3, entry.promptPresent() );
            ps.setInt( 4, entry.budgetRequested() );
            ps.setInt( 5, entry.budgetUsed() );
            ps.setInt( 6, entry.sectionCount() );
            ps.setInt( 7, entry.pinCount() );
            ps.setInt( 8, entry.pointerCount() );
            ps.setString( 9, entry.surface() );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            LOG.warn( "Briefing-log write failed for surface={}: {}", entry.surface(), e.getMessage() );
        }
    }

    /** Null-safe truncation of a caller-supplied string to {@link #MAX_TEXT_LEN} chars. */
    private static String truncate( final String s ) {
        if ( s == null || s.length() <= MAX_TEXT_LEN ) {
            return s;
        }
        return s.substring( 0, MAX_TEXT_LEN );
    }
}

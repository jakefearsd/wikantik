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
package com.wikantik.knowledge.judge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed implementation of {@link KgJudgeTimeoutRepository}. */
public class JdbcKgJudgeTimeoutRepository implements KgJudgeTimeoutRepository {

    private static final Logger LOG = LogManager.getLogger( JdbcKgJudgeTimeoutRepository.class );

    private final DataSource dataSource;

    public JdbcKgJudgeTimeoutRepository( final DataSource dataSource ) {
        this.dataSource = Objects.requireNonNull( dataSource, "dataSource" );
    }

    @Override
    public Optional< TimeoutRow > find( final UUID proposalId ) {
        final String sql = """
            SELECT proposal_id, content_sha256, source_page, proposal_type, model_name,
                   content_bytes, timeout_count, last_error_excerpt, base_timeout_seconds,
                   first_seen, last_seen
              FROM kg_judge_timeouts
             WHERE proposal_id = ?
            """;
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, proposalId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( map( rs ) ) : Optional.empty();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "kg_judge_timeouts find failed for {}: {}", proposalId, e.getMessage() );
            return Optional.empty();
        }
    }

    @Override
    public void recordTimeout( final UUID proposalId,
                                final String contentSha256,
                                final String sourcePage,
                                final String proposalType,
                                final String modelName,
                                final int contentBytes,
                                final String errorExcerpt,
                                final int baseTimeoutSeconds ) {
        // ON CONFLICT increment count and refresh metadata; preserve first_seen.
        final String sql = """
            INSERT INTO kg_judge_timeouts
                (proposal_id, content_sha256, source_page, proposal_type, model_name,
                 content_bytes, timeout_count, last_error_excerpt, base_timeout_seconds,
                 first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, NOW(), NOW())
            ON CONFLICT (proposal_id) DO UPDATE SET
                content_sha256       = EXCLUDED.content_sha256,
                source_page          = EXCLUDED.source_page,
                proposal_type        = EXCLUDED.proposal_type,
                model_name           = EXCLUDED.model_name,
                content_bytes        = EXCLUDED.content_bytes,
                timeout_count        = kg_judge_timeouts.timeout_count + 1,
                last_error_excerpt   = EXCLUDED.last_error_excerpt,
                base_timeout_seconds = EXCLUDED.base_timeout_seconds,
                last_seen            = NOW()
            """;
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, proposalId );
            ps.setString( 2, contentSha256 );
            setNullableString( ps, 3, sourcePage );
            setNullableString( ps, 4, proposalType );
            setNullableString( ps, 5, modelName );
            ps.setInt( 6, contentBytes );
            setNullableString( ps, 7, truncate( errorExcerpt, 1024 ) );
            ps.setInt( 8, baseTimeoutSeconds );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "kg_judge_timeouts upsert failed for {}: {}", proposalId, e.getMessage() );
        }
    }

    @Override
    public void clear( final UUID proposalId ) {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "DELETE FROM kg_judge_timeouts WHERE proposal_id = ?" ) ) {
            ps.setObject( 1, proposalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "kg_judge_timeouts clear failed for {}: {}", proposalId, e.getMessage() );
        }
    }

    @Override
    public List< TimeoutRow > listTopChronic( final int limit ) {
        final int safeLimit = Math.max( 1, Math.min( limit, 1000 ) );
        final String sql = """
            SELECT proposal_id, content_sha256, source_page, proposal_type, model_name,
                   content_bytes, timeout_count, last_error_excerpt, base_timeout_seconds,
                   first_seen, last_seen
              FROM kg_judge_timeouts
             ORDER BY timeout_count DESC, last_seen DESC
             LIMIT ?
            """;
        final List< TimeoutRow > rows = new ArrayList<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setInt( 1, safeLimit );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) rows.add( map( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "kg_judge_timeouts listTopChronic failed: {}", e.getMessage() );
        }
        return rows;
    }

    private static TimeoutRow map( final ResultSet rs ) throws SQLException {
        return new TimeoutRow(
            (UUID) rs.getObject( "proposal_id" ),
            rs.getString( "content_sha256" ),
            rs.getString( "source_page" ),
            rs.getString( "proposal_type" ),
            rs.getString( "model_name" ),
            rs.getInt( "content_bytes" ),
            rs.getInt( "timeout_count" ),
            rs.getString( "last_error_excerpt" ),
            rs.getInt( "base_timeout_seconds" ),
            toInstant( rs.getTimestamp( "first_seen" ) ),
            toInstant( rs.getTimestamp( "last_seen" ) )
        );
    }

    private static Instant toInstant( final Timestamp ts ) {
        return ts == null ? null : ts.toInstant();
    }

    private static void setNullableString( final PreparedStatement ps, final int idx, final String v )
            throws SQLException {
        if ( v == null ) ps.setNull( idx, Types.VARCHAR );
        else             ps.setString( idx, v );
    }

    private static String truncate( final String s, final int max ) {
        if ( s == null ) return null;
        return s.length() <= max ? s : s.substring( 0, max );
    }
}

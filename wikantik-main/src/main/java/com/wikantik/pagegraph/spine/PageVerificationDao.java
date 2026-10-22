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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.jdbc.JdbcSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC gateway for {@code page_verification}. Phase 1 of the Agent-Grade
 * Content design owns this; later phases consume it through
 * {@link com.wikantik.api.pagegraph.PageDescriptor} and the {@code /for-agent}
 * projection.
 */
public class PageVerificationDao extends JdbcSupport {

    private static final Logger LOG = LogManager.getLogger( PageVerificationDao.class );

    public PageVerificationDao( final DataSource ds ) {
        super( ds );
    }

    @Override
    protected Logger log() { return LOG; }

    public void upsert( final String canonicalId, final Verification v ) {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( v == null ) {
            throw new IllegalArgumentException( "verification required" );
        }
        try {
            // One statement; the WHERE makes an identical re-upsert (every boot) a no-op that
            // writes no tuple and leaves updated_at alone.
            update( "INSERT INTO page_verification " +
                    "(canonical_id, verified_at, verified_by, confidence, audience) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT ( canonical_id ) DO UPDATE SET verified_at = EXCLUDED.verified_at, " +
                    "verified_by = EXCLUDED.verified_by, confidence = EXCLUDED.confidence, " +
                    "audience = EXCLUDED.audience, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE ( page_verification.verified_at, page_verification.verified_by, " +
                    "page_verification.confidence, page_verification.audience ) " +
                    "IS DISTINCT FROM ( EXCLUDED.verified_at, EXCLUDED.verified_by, " +
                    "EXCLUDED.confidence, EXCLUDED.audience )", ps -> {
                ps.setString( 1, canonicalId );
                bindTimestamp( ps, 2, v.verifiedAt() );
                ps.setString( 3, v.verifiedBy() );
                ps.setString( 4, v.confidence().wireName() );
                ps.setString( 5, v.audience().wireName() );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "PageVerificationDao.upsert({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "verification upsert failed", e );
        }
    }

    /**
     * Every verification row keyed by canonical id, in one query.
     *
     * @return the rows, or {@code null} if the read failed (callers fall back to per-id lookups)
     */
    public java.util.Map< String, Verification > findAll() {
        try {
            final java.util.Map< String, Verification > out = new java.util.HashMap<>();
            query( "SELECT canonical_id, verified_at, verified_by, confidence, audience FROM page_verification",
                    ps -> {}, rs -> {
                        out.put( rs.getString( "canonical_id" ), readRow( rs ) );
                        return Boolean.TRUE;
                    } );
            return out;
        } catch ( final SQLException e ) {
            LOG.warn( "findAll() failed: {}", e.getMessage() );
            return null;
        }
    }

    public Optional< Verification > findByCanonicalId( final String canonicalId ) {
        try {
            return queryOne( "SELECT verified_at, verified_by, confidence, audience " +
                    "FROM page_verification WHERE canonical_id = ?",
                    ps -> ps.setString( 1, canonicalId ), PageVerificationDao::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "findByCanonicalId({}) failed: {}", canonicalId, e.getMessage() );
            return Optional.empty();
        }
    }

    public List< String > listCanonicalIdsByConfidence( final Confidence confidence ) {
        try {
            return query( "SELECT canonical_id FROM page_verification WHERE confidence = ? " +
                    "ORDER BY verified_at NULLS FIRST",
                    ps -> ps.setString( 1, confidence.wireName() ), rs -> rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            LOG.warn( "listCanonicalIdsByConfidence({}) failed: {}", confidence, e.getMessage() );
            return List.of();
        }
    }

    public void deleteByCanonicalId( final String canonicalId ) {
        try {
            update( "DELETE FROM page_verification WHERE canonical_id = ?", ps -> ps.setString( 1, canonicalId ) );
        } catch ( final SQLException e ) {
            LOG.warn( "deleteByCanonicalId({}) failed: {}", canonicalId, e.getMessage() );
            throw new RuntimeException( "verification delete failed", e );
        }
    }

    private static Verification readRow( final ResultSet rs ) throws SQLException {
        final Timestamp verifiedAt = rs.getTimestamp( "verified_at" );
        final String verifiedBy    = rs.getString( "verified_by" );
        final Confidence confidence = Confidence.fromWire( rs.getString( "confidence" ) )
                .orElse( Confidence.PROVISIONAL );
        final Audience audience    = Audience.fromWire( rs.getString( "audience" ) )
                .orElse( Audience.HUMANS_AND_AGENTS );
        return new Verification(
                verifiedAt == null ? null : verifiedAt.toInstant(),
                verifiedBy,
                confidence,
                audience );
    }

    private static void bindTimestamp( final PreparedStatement ps, final int index,
                                        final java.time.Instant instant ) throws SQLException {
        if ( instant == null ) {
            ps.setNull( index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE );
        } else {
            ps.setTimestamp( index, Timestamp.from( instant ) );
        }
    }
}

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
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgRejection;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC repository for {@code kg_rejections} table operations.
 *
 * <p>Extracted from the former JdbcKnowledgeRepository facade in Phase 3 Checkpoint 3 of the
 * wikantik-main subsystem decomposition. SQL strings are verbatim copies; no behaviour
 * change is introduced.</p>
 *
 * @since 1.0
 */
public final class KgRejectionRepository {

    private static final Logger LOG = LogManager.getLogger( KgRejectionRepository.class );

    private final Jdbc jdbc;

    public KgRejectionRepository( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    private Instant toInstant( final Timestamp ts ) {
        return ts != null ? ts.toInstant() : null;
    }

    private KgRejection mapRejection( final ResultSet rs ) throws SQLException {
        return new KgRejection(
            rs.getObject( "id", java.util.UUID.class ),
            rs.getString( "proposed_source" ),
            rs.getString( "proposed_target" ),
            rs.getString( "proposed_relationship" ),
            rs.getString( "rejected_by" ),
            rs.getString( "reason" ),
            toInstant( rs.getTimestamp( "created" ) )
        );
    }

    // ---- Rejection operations ----

    public void insertRejection( final String proposedSource, final String proposedTarget,
                                 final String proposedRelationship,
                                 final String rejectedBy, final String reason ) {
        final String sql = "INSERT INTO kg_rejections ( proposed_source, proposed_target, proposed_relationship, rejected_by, reason ) "
                + "VALUES ( ?, ?, ?, ?, ? ) "
                + "ON CONFLICT ( proposed_source, proposed_target, proposed_relationship ) DO UPDATE SET "
                + "rejected_by = EXCLUDED.rejected_by, reason = EXCLUDED.reason";
        try {
            jdbc.update( sql, ps -> {
                ps.setString( 1, proposedSource );
                ps.setString( 2, proposedTarget );
                ps.setString( 3, proposedRelationship );
                ps.setString( 4, rejectedBy );
                ps.setString( 5, reason );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert rejection: {}", e.getMessage(), e );
            throw new RuntimeException( "Failed to insert rejection: " + e.getMessage(), e );
        }
    }

    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        final String sql = "SELECT COUNT(*) FROM kg_rejections "
                         + "WHERE proposed_source = ? AND proposed_target = ? AND proposed_relationship = ?";
        try {
            return jdbc.queryOne( sql, ps -> {
                ps.setString( 1, sourceName );
                ps.setString( 2, targetName );
                ps.setString( 3, relationshipType );
            }, rs -> rs.getInt( 1 ) ).map( count -> count > 0 ).orElse( false );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check rejection: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Batch form of {@link #isRejected}: given {@code [source, target, relationship]} triples, returns the
     * subset with a recorded rejection, in one query joining the rejections to the unnested triples.
     */
    public Set< List< String > > findRejected( final Collection< List< String > > triples ) {
        if ( triples == null || triples.isEmpty() ) return Set.of();
        final String sql = "SELECT r.proposed_source, r.proposed_target, r.proposed_relationship "
                + "FROM kg_rejections r JOIN unnest( ?::text[], ?::text[], ?::text[] ) AS t( s, t, r ) "
                + "ON r.proposed_source = t.s AND r.proposed_target = t.t AND r.proposed_relationship = t.r";
        final String[] sources = new String[ triples.size() ];
        final String[] targets = new String[ triples.size() ];
        final String[] rels = new String[ triples.size() ];
        int i = 0;
        for ( final List< String > t : triples ) {
            sources[ i ] = t.get( 0 );
            targets[ i ] = t.get( 1 );
            rels[ i++ ] = t.get( 2 );
        }
        try {
            return new LinkedHashSet<>( jdbc.query( sql, ps -> {
                ps.setArray( 1, ps.getConnection().createArrayOf( "text", sources ) );
                ps.setArray( 2, ps.getConnection().createArrayOf( "text", targets ) );
                ps.setArray( 3, ps.getConnection().createArrayOf( "text", rels ) );
            }, rs -> List.of( rs.getString( 1 ), rs.getString( 2 ), rs.getString( 3 ) ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check {} rejections: {}", triples.size(), e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                               final String relationshipType ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_rejections WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        if ( sourceName != null ) {
            sql.append( " AND proposed_source = ?" );
            params.add( sourceName );
        }
        if ( targetName != null ) {
            sql.append( " AND proposed_target = ?" );
            params.add( targetName );
        }
        if ( relationshipType != null ) {
            sql.append( " AND proposed_relationship = ?" );
            params.add( relationshipType );
        }

        sql.append( " ORDER BY created DESC" );

        try {
            return jdbc.query( sql.toString(), SqlBinder.positional( params ), this::mapRejection );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list rejections: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public int deleteRejection( final String source, final String target, final String relationship ) {
        final String sql = "DELETE FROM kg_rejections WHERE proposed_source = ? " +
            "AND proposed_target = ? AND proposed_relationship = ?";
        try {
            return jdbc.update( sql, ps -> {
                ps.setString( 1, source );
                ps.setString( 2, target );
                ps.setString( 3, relationship );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "deleteRejection({}, {}, {}) failed: {}", source, target, relationship,
                e.getMessage(), e );
            throw new RuntimeException( "deleteRejection failed: " + e.getMessage(), e );
        }
    }
}

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for {@code kg_rejections} table operations.
 *
 * <p>Extracted from {@link JdbcKnowledgeRepository} in Phase 3 Checkpoint 3 of the
 * wikantik-main subsystem decomposition. SQL strings are verbatim copies; no behaviour
 * change is introduced.</p>
 *
 * @since 1.0
 */
public final class KgRejectionRepository {

    private static final Logger LOG = LogManager.getLogger( KgRejectionRepository.class );

    private final DataSource dataSource;

    public KgRejectionRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
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
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, proposedSource );
            ps.setString( 2, proposedTarget );
            ps.setString( 3, proposedRelationship );
            ps.setString( 4, rejectedBy );
            ps.setString( 5, reason );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert rejection: {}", e.getMessage(), e );
            throw new RuntimeException( "Failed to insert rejection: " + e.getMessage(), e );
        }
    }

    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        final String sql = "SELECT COUNT(*) FROM kg_rejections "
                         + "WHERE proposed_source = ? AND proposed_target = ? AND proposed_relationship = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, sourceName );
            ps.setString( 2, targetName );
            ps.setString( 3, relationshipType );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() && rs.getInt( 1 ) > 0;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to check rejection: {}", e.getMessage(), e );
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

        final List< KgRejection > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapRejection( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list rejections: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public int deleteRejection( final String source, final String target, final String relationship ) {
        final String sql = "DELETE FROM kg_rejections WHERE proposed_source = ? " +
            "AND proposed_target = ? AND proposed_relationship = ?";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, source );
            ps.setString( 2, target );
            ps.setString( 3, relationship );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "deleteRejection({}, {}, {}) failed: {}", source, target, relationship,
                e.getMessage(), e );
            throw new RuntimeException( "deleteRejection failed: " + e.getMessage(), e );
        }
    }
}

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
package com.wikantik.knowledge.structure;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC gateway for {@code page_canonical_ids} and {@code page_slug_history}.
 * All methods are idempotent: {@link #upsert} inserts on miss and updates
 * on hit, emitting a history row whenever the slug actually changes.
 */
public class PageCanonicalIdsDao {

    private static final Logger LOG = LogManager.getLogger( PageCanonicalIdsDao.class );

    private final DataSource ds;

    public PageCanonicalIdsDao( final DataSource ds ) {
        this.ds = ds;
    }

    public void upsert( final String canonicalId,
                        final String currentSlug,
                        final String title,
                        final String type,
                        final String cluster ) {
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try {
                final Optional< Row > existing = findByCanonicalId( c, canonicalId );
                if ( existing.isEmpty() ) {
                    try ( PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO page_canonical_ids " +
                            "(canonical_id, current_slug, title, type, cluster) " +
                            "VALUES (?, ?, ?, ?, ?)" ) ) {
                        ps.setString( 1, canonicalId );
                        ps.setString( 2, currentSlug );
                        ps.setString( 3, title );
                        ps.setString( 4, type );
                        ps.setString( 5, cluster );
                        ps.executeUpdate();
                    }
                } else {
                    final Row prev = existing.get();
                    if ( !prev.currentSlug().equals( currentSlug )
                            && !slugHistoryRowExists( c, canonicalId, prev.currentSlug() ) ) {
                        try ( PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO page_slug_history (canonical_id, previous_slug) " +
                                "VALUES (?, ?)" ) ) {
                            ps.setString( 1, canonicalId );
                            ps.setString( 2, prev.currentSlug() );
                            ps.executeUpdate();
                        }
                    }
                    try ( PreparedStatement ps = c.prepareStatement(
                            "UPDATE page_canonical_ids SET " +
                            "current_slug = ?, title = ?, type = ?, cluster = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE canonical_id = ?" ) ) {
                        ps.setString( 1, currentSlug );
                        ps.setString( 2, title );
                        ps.setString( 3, type );
                        ps.setString( 4, cluster );
                        ps.setString( 5, canonicalId );
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch ( final SQLException e ) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PageCanonicalIdsDao.upsert({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "upsert failed", e );
        }
    }

    public Optional< Row > findByCanonicalId( final String canonicalId ) {
        try ( Connection c = ds.getConnection() ) {
            return findByCanonicalId( c, canonicalId );
        } catch ( final SQLException e ) {
            LOG.warn( "findByCanonicalId({}) failed: {}", canonicalId, e.getMessage() );
            return Optional.empty();
        }
    }

    private boolean slugHistoryRowExists( final Connection c, final String canonicalId,
                                           final String previousSlug ) throws SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM page_slug_history WHERE canonical_id = ? AND previous_slug = ?" ) ) {
            ps.setString( 1, canonicalId );
            ps.setString( 2, previousSlug );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        }
    }

    private Optional< Row > findByCanonicalId( final Connection c, final String id ) throws SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                "FROM page_canonical_ids WHERE canonical_id = ?" ) ) {
            ps.setString( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( readRow( rs ) ) : Optional.empty();
            }
        }
    }

    public Optional< Row > findBySlug( final String slug ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                      "FROM page_canonical_ids WHERE current_slug = ?" ) ) {
            ps.setString( 1, slug );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( readRow( rs ) ) : Optional.empty();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findBySlug({}) failed: {}", slug, e.getMessage() );
            return Optional.empty();
        }
    }

    public List< Row > findAll() {
        final List< Row > rows = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                      "FROM page_canonical_ids ORDER BY canonical_id" );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                rows.add( readRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findAll() failed: {}", e.getMessage() );
        }
        return rows;
    }

    public List< String > slugHistory( final String canonicalId ) {
        final List< String > history = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT previous_slug FROM page_slug_history " +
                      "WHERE canonical_id = ? ORDER BY renamed_at DESC" ) ) {
            ps.setString( 1, canonicalId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    history.add( rs.getString( 1 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "slugHistory({}) failed: {}", canonicalId, e.getMessage() );
        }
        return history;
    }

    public void delete( final String canonicalId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "DELETE FROM page_canonical_ids WHERE canonical_id = ?" ) ) {
            ps.setString( 1, canonicalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "delete({}) failed: {}", canonicalId, e.getMessage() );
            throw new RuntimeException( "delete failed", e );
        }
    }

    private static Row readRow( final ResultSet rs ) throws SQLException {
        return new Row(
                rs.getString( "canonical_id" ),
                rs.getString( "current_slug" ),
                rs.getString( "title" ),
                rs.getString( "type" ),
                rs.getString( "cluster" ),
                rs.getTimestamp( "created_at" ).toInstant(),
                rs.getTimestamp( "updated_at" ).toInstant() );
    }

    public record Row(
            String canonicalId,
            String currentSlug,
            String title,
            String type,
            String cluster,
            Instant createdAt,
            Instant updatedAt
    ) {}
}

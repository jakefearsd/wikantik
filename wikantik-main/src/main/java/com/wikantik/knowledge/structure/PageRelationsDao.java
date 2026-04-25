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

import com.wikantik.api.structure.Relation;
import com.wikantik.api.structure.RelationType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JDBC gateway for the {@code page_relations} table. Phase 2 of the
 * Structural Spine populates this from frontmatter on every rebuild;
 * Phase 4 will surface validation rejections at save time.
 *
 * <p>Per-source replacement is the only supported write path —
 * {@link #replaceFor(String, Collection)} runs DELETE-then-INSERT in a single
 * transaction so a partial save can never leave stale relations attached to
 * a page that no longer authors them.</p>
 */
public class PageRelationsDao {

    private static final Logger LOG = LogManager.getLogger( PageRelationsDao.class );

    private final DataSource ds;

    public PageRelationsDao( final DataSource ds ) {
        this.ds = ds;
    }

    /**
     * Replace every outgoing relation for {@code sourceId} with the given
     * collection. Targets that don't exist in {@code page_canonical_ids}
     * cause the FK insert to throw — callers should pre-filter with the
     * structural index's known-id set.
     */
    public void replaceFor( final String sourceId, final Collection< Relation > relations ) {
        if ( sourceId == null || sourceId.isBlank() ) {
            throw new IllegalArgumentException( "sourceId required" );
        }
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try {
                try ( PreparedStatement del = c.prepareStatement(
                        "DELETE FROM page_relations WHERE source_id = ?" ) ) {
                    del.setString( 1, sourceId );
                    del.executeUpdate();
                }
                if ( relations != null && !relations.isEmpty() ) {
                    try ( PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO page_relations (source_id, target_id, relation_type) " +
                            "VALUES (?, ?, ?)" ) ) {
                        for ( final Relation r : relations ) {
                            if ( !r.sourceId().equals( sourceId ) ) {
                                throw new IllegalArgumentException(
                                    "relation sourceId " + r.sourceId() + " does not match " + sourceId );
                            }
                            ins.setString( 1, r.sourceId() );
                            ins.setString( 2, r.targetId() );
                            ins.setString( 3, r.type().wireName() );
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                c.commit();
            } catch ( final SQLException | IllegalArgumentException e ) {
                c.rollback();
                throw e instanceof SQLException sqle
                        ? new RuntimeException( "replaceFor failed", sqle )
                        : (RuntimeException) e;
            } finally {
                c.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PageRelationsDao.replaceFor({}) failed: {}", sourceId, e.getMessage(), e );
            throw new RuntimeException( "replaceFor failed", e );
        }
    }

    public List< Relation > listOutgoing( final String sourceId, final Optional< RelationType > type ) {
        return select(
                "SELECT source_id, target_id, relation_type FROM page_relations WHERE source_id = ?"
                + ( type.isPresent() ? " AND relation_type = ?" : "" )
                + " ORDER BY relation_type, target_id",
                sourceId, type );
    }

    public List< Relation > listIncoming( final String targetId, final Optional< RelationType > type ) {
        return select(
                "SELECT source_id, target_id, relation_type FROM page_relations WHERE target_id = ?"
                + ( type.isPresent() ? " AND relation_type = ?" : "" )
                + " ORDER BY relation_type, source_id",
                targetId, type );
    }

    public void deleteBySource( final String sourceId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "DELETE FROM page_relations WHERE source_id = ?" ) ) {
            ps.setString( 1, sourceId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "deleteBySource({}) failed: {}", sourceId, e.getMessage() );
            throw new RuntimeException( "deleteBySource failed", e );
        }
    }

    private List< Relation > select( final String sql, final String key,
                                      final Optional< RelationType > type ) {
        final List< Relation > out = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, key );
            if ( type.isPresent() ) {
                ps.setString( 2, type.get().wireName() );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Optional< RelationType > parsed = RelationType.fromWire( rs.getString( "relation_type" ) );
                    if ( parsed.isEmpty() ) {
                        // Unknown stored value — surface so we can clean up but don't crash the read.
                        LOG.warn( "Unknown relation_type in DB for source={} target={}",
                                  rs.getString( "source_id" ), rs.getString( "target_id" ) );
                        continue;
                    }
                    out.add( new Relation(
                            rs.getString( "source_id" ),
                            rs.getString( "target_id" ),
                            parsed.get() ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "select on page_relations failed: {}", e.getMessage() );
        }
        return out;
    }
}

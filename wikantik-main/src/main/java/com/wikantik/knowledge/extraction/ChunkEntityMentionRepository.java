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
package com.wikantik.knowledge.extraction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Writer for the {@code chunk_entity_mentions} table (schema V011). Reads
 * remain on {@link com.wikantik.knowledge.embedding.NodeMentionSimilarity},
 * which already owns the node-centric query shape.
 */
public class ChunkEntityMentionRepository {

    private static final Logger LOG = LogManager.getLogger( ChunkEntityMentionRepository.class );

    private static final String UPSERT_SQL =
        "INSERT INTO chunk_entity_mentions ( chunk_id, node_id, confidence, extractor, extracted_at ) "
      + "VALUES ( ?, ?, ?, ?, NOW() ) "
      + "ON CONFLICT ( chunk_id, node_id ) DO UPDATE "
      + "SET confidence = EXCLUDED.confidence, "
      + "    extractor = EXCLUDED.extractor, "
      + "    extracted_at = EXCLUDED.extracted_at";

    private static final String DELETE_BY_CHUNK_SQL =
        "DELETE FROM chunk_entity_mentions WHERE chunk_id = ?";

    private final DataSource dataSource;

    public ChunkEntityMentionRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /** A resolved mention ready for insertion. */
    public record Row( UUID chunkId, UUID nodeId, double confidence, String extractor ) {}

    /**
     * Upserts a batch of mentions in one transaction. Empty list is a no-op.
     * Confidence is clamped to {@code [0.0, 1.0]} on write.
     *
     * @return number of rows acted on (insert + update counts)
     */
    public int upsertAll( final List< Row > rows ) {
        if( rows == null || rows.isEmpty() ) {
            return 0;
        }
        try( Connection conn = dataSource.getConnection() ) {
            final boolean prev = conn.getAutoCommit();
            conn.setAutoCommit( false );
            try( PreparedStatement ps = conn.prepareStatement( UPSERT_SQL ) ) {
                for( final Row r : rows ) {
                    ps.setObject( 1, r.chunkId() );
                    ps.setObject( 2, r.nodeId() );
                    ps.setDouble( 3, clamp( r.confidence() ) );
                    ps.setString( 4, r.extractor() );
                    ps.addBatch();
                }
                final int[] counts = ps.executeBatch();
                conn.commit();
                int total = 0;
                for( final int c : counts ) {
                    total += Math.max( c, 0 );
                }
                return total;
            } catch( final SQLException e ) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit( prev );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to upsert {} mention rows: {}", rows.size(), e.getMessage(), e );
            throw new RuntimeException( "chunk_entity_mentions upsert failed", e );
        }
    }

    /** Removes every mention for the given chunk. Used before re-extracting. */
    public int deleteByChunkId( final UUID chunkId ) {
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( DELETE_BY_CHUNK_SQL ) ) {
            ps.setObject( 1, chunkId );
            return ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete mentions for chunk {}: {}", chunkId, e.getMessage(), e );
            throw new RuntimeException( "chunk_entity_mentions delete failed", e );
        }
    }

    private static double clamp( final double v ) {
        if( Double.isNaN( v ) ) {
            return 0.0;
        }
        return Math.max( 0.0, Math.min( 1.0, v ) );
    }
}

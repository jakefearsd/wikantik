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

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper that inserts the (chunk, embedding, mention) rows needed for
 * {@link com.wikantik.knowledge.embedding.NodeMentionSimilarity}-backed tests.
 * The hub services read mentions via SQL, so the fixtures must land in
 * {@code kg_content_chunks}, {@code content_chunk_embeddings}, and
 * {@code chunk_entity_mentions}. All rows are linked by the node's existing
 * {@code kg_nodes.id}; callers pre-insert nodes via {@code JdbcKnowledgeRepository}.
 */
final class MentionFixtures {

    private MentionFixtures() {}

    /** Inserts one chunk + embedding + mention for the given node. */
    static void seedMention( final DataSource ds, final String modelCode,
                              final UUID nodeId, final String pageName, final float[] vec ) {
        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = ds.getConnection() ) {
            try ( final PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kg_content_chunks "
              + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, 0, ?, ?, ?, ?)" ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, pageName );
                final String text = "chunk-for-" + pageName;
                ps.setString( 3, text );
                ps.setInt( 4, text.length() );
                ps.setInt( 5, text.length() );
                ps.setString( 6, Integer.toHexString( pageName.hashCode() ^ chunkId.hashCode() ) );
                ps.executeUpdate();
            }
            try ( final PreparedStatement ps = c.prepareStatement(
                "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) "
              + "VALUES (?, ?, ?, ?)" ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, modelCode );
                ps.setInt( 3, vec.length );
                ps.setBytes( 4, encode( vec ) );
                ps.executeUpdate();
            }
            try ( final PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
              + "VALUES (?, ?, 1.0, 'test')" ) ) {
                ps.setObject( 1, chunkId );
                ps.setObject( 2, nodeId );
                ps.executeUpdate();
            }
        } catch ( final Exception e ) {
            throw new RuntimeException( "Failed to seed mention for " + pageName, e );
        }
    }

    /** Bulk helper: resolves nodeId by name via kg_nodes, then seeds a mention. */
    static void seedMentionByName( final DataSource ds, final String modelCode,
                                    final String nodeName, final float[] vec ) {
        final UUID nodeId = nodeIdByName( ds, nodeName );
        if ( nodeId == null ) {
            throw new IllegalStateException( "kg_nodes row not found for name=" + nodeName );
        }
        seedMention( ds, modelCode, nodeId, nodeName, vec );
    }

    /** Bulk helper: iterate name→vector map, resolve each name, seed a mention. */
    static void seedAllByName( final DataSource ds, final String modelCode,
                                final Map< String, float[] > vectors ) {
        for ( final Map.Entry< String, float[] > e : vectors.entrySet() ) {
            seedMentionByName( ds, modelCode, e.getKey(), e.getValue() );
        }
    }

    /** Clears all fixture tables so each test starts from a known-empty state. */
    static void clear( final DataSource ds ) {
        try ( final Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Failed to clear mention fixtures", e );
        }
    }

    private static UUID nodeIdByName( final DataSource ds, final String name ) {
        try ( final Connection c = ds.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "SELECT id FROM kg_nodes WHERE name = ?" ) ) {
            ps.setString( 1, name );
            try ( final ResultSet rs = ps.executeQuery() ) {
                if ( rs.next() ) return rs.getObject( 1, UUID.class );
                return null;
            }
        } catch ( final Exception e ) {
            throw new RuntimeException( "nodeIdByName failed for " + name, e );
        }
    }

    private static byte[] encode( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES )
            .order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }
}

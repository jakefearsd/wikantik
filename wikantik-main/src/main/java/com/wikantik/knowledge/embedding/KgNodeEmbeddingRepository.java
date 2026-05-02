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
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DAO over {@code kg_node_embeddings}. Vectors are stored as pgvector
 * literal strings ({@code "[v1,v2,...]"}) — pgvector's JDBC type isn't
 * registered in this codebase so we round-trip via {@code ::vector} casts.
 */
public final class KgNodeEmbeddingRepository {

    private static final Logger LOG = LogManager.getLogger(KgNodeEmbeddingRepository.class);

    private final DataSource dataSource;

    public KgNodeEmbeddingRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Cached> findById(final UUID nodeId, final String modelCode) {
        final String sql = "SELECT content_hash, embedding::text FROM kg_node_embeddings"
                         + " WHERE node_id = ? AND model_code = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, nodeId);
            ps.setString(2, modelCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Cached(rs.getString(1), parseVector(rs.getString(2))));
            }
        } catch (final SQLException e) {
            LOG.warn("findById({}, {}) failed: {}", nodeId, modelCode, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void upsert(final UUID nodeId, final String modelCode,
                        final String contentHash, final float[] embedding) {
        final String sql = """
            INSERT INTO kg_node_embeddings (node_id, model_code, content_hash, embedding, embedded_at)
            VALUES (?, ?, ?, ?::vector, NOW())
            ON CONFLICT (node_id, model_code) DO UPDATE
            SET content_hash = EXCLUDED.content_hash,
                embedding    = EXCLUDED.embedding,
                embedded_at  = NOW()
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, nodeId);
            ps.setString(2, modelCode);
            ps.setString(3, contentHash);
            ps.setString(4, formatVector(embedding));
            ps.executeUpdate();
        } catch (final SQLException e) {
            LOG.warn("upsert({}, {}) failed: {}", nodeId, modelCode, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<KgNode> findTopKByPageEmbedding(final float[] pageEmbedding, final int k,
                                                 final String modelCode) {
        // Filter to a single model so different embedders never share a
        // similarity space (cosine across, say, bge-m3 and qwen3 vectors is
        // meaningless even though both are 1024-dim).
        final String sql = """
            SELECT n.id, n.name, n.node_type, n.source_page, n.provenance,
                   n.created, n.modified
            FROM kg_node_embeddings ne
            JOIN kg_nodes n ON n.id = ne.node_id
            WHERE ne.model_code = ?
            ORDER BY ne.embedding <=> ?::vector
            LIMIT ?
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, modelCode);
            ps.setString(2, formatVector(pageEmbedding));
            ps.setInt(3, k);
            try (ResultSet rs = ps.executeQuery()) {
                final List<KgNode> out = new ArrayList<>(k);
                while (rs.next()) {
                    out.add(new KgNode(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        Provenance.fromValue(rs.getString(5)),
                        Map.of(),
                        rs.getTimestamp(6) == null ? Instant.now() : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? Instant.now() : rs.getTimestamp(7).toInstant(),
                        "human",
                        null
                    ));
                }
                return out;
            }
        } catch (final SQLException e) {
            LOG.warn("findTopKByPageEmbedding failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    static String formatVector(final float[] v) {
        final StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    static float[] parseVector(final String text) {
        final String trimmed = text.substring(1, text.length() - 1);
        final String[] parts = trimmed.split(",");
        final float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }

    public record Cached(String contentHash, float[] embedding) {}
}

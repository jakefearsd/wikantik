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
package com.wikantik.search.embedding;

import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Read-only counts over the dense-retrieval index ({@code kg_content_chunks} +
 * {@code content_chunk_embeddings}) for one embedding model. Backs {@code GET /admin/embeddings/status}.
 */
public final class EmbeddingIndexStatsRepository {

    private static final Logger LOG = LogManager.getLogger( EmbeddingIndexStatsRepository.class );

    /** Index counts: pages with at least one embedded chunk, all chunks, embedded chunks. */
    public record Stats( int pagesEmbedded, long chunksTotal, long chunksEmbedded ) {
        public static Stats empty() { return new Stats( 0, 0L, 0L ); }
    }

    private final Jdbc jdbc;

    public EmbeddingIndexStatsRepository( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    /** Counts for {@code modelCode}; an unreadable database degrades to zeros with a warning. */
    public Stats stats( final String modelCode ) {
        try {
            final long total = jdbc.queryOne( "SELECT COUNT(*) FROM kg_content_chunks",
                    ps -> { }, rs -> rs.getLong( 1 ) ).orElse( 0L );
            return jdbc.queryOne(
                    "SELECT COUNT(*), COUNT(DISTINCT c.page_name) FROM content_chunk_embeddings e "
                  + "JOIN kg_content_chunks c ON c.id = e.chunk_id WHERE e.model_code = ?",
                    ps -> ps.setString( 1, modelCode ),
                    rs -> new Stats( rs.getInt( 2 ), total, rs.getLong( 1 ) ) )
                .orElse( Stats.empty() );
        } catch ( final SQLException e ) {
            LOG.warn( "Embedding index stats query failed for model={}: {}", modelCode, e.getMessage(), e );
            return Stats.empty();
        }
    }
}

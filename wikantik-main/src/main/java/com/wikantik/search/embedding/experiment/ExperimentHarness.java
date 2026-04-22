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
package com.wikantik.search.embedding.experiment;

import com.wikantik.search.embedding.EmbeddingClientFactory;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.TextEmbeddingClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Shared utilities for the embedding-retrieval experiment harnesses
 * ({@code ExperimentEvaluator}, {@code Experiment*Sweep}, {@code ExperimentGrandFinale}).
 *
 * <p>Previously each harness copy-pasted {@code loadCorpus}, {@code buildClient},
 * {@code rankOf}, a {@code ChunkCorpus} record, and the {@code Files.createDirectories
 * + Files.writeString} output pattern. Consolidating here keeps the dev-only
 * evaluation pipeline coherent across sweeps.</p>
 */
final class ExperimentHarness {

    private ExperimentHarness() {}

    /**
     * Package-visible holder shared by every harness. Uses package-visible fields
     * (not a record) because call sites read fields directly (e.g. {@code
     * corpus.vectors.size()}) and would otherwise all need to be rewritten to
     * use accessor methods.
     */
    static final class ChunkCorpus {
        final List< UUID > chunkIds;
        final List< float[] > vectors;
        final List< Double > norms;
        final Map< UUID, String > pagesForChunk;

        ChunkCorpus( final List< UUID > chunkIds, final List< float[] > vectors,
                     final List< Double > norms, final Map< UUID, String > pagesForChunk ) {
            this.chunkIds = chunkIds;
            this.vectors = vectors;
            this.norms = norms;
            this.pagesForChunk = pagesForChunk;
        }
    }

    /**
     * Loads all experiment embeddings for a given model from the experiment DB,
     * joined with the chunk → page mapping. Throws if the corpus is empty or a
     * dimension mismatch is detected — both indicate that
     * {@link ExperimentIndexer} has not been run for this model.
     */
    static ChunkCorpus loadCorpus( final Connection conn, final String modelCode, final int expectedDim )
            throws SQLException {
        final String sql = """
            SELECT e.chunk_id, e.dim, e.vec, c.page_name
            FROM experiment_embeddings e
            JOIN kg_content_chunks c ON c.id = e.chunk_id
            WHERE e.model_code = ?
            """;
        final List< UUID >    chunkIds = new ArrayList<>();
        final List< float[] > vectors  = new ArrayList<>();
        final List< Double >  norms    = new ArrayList<>();
        final Map< UUID, String > pagesForChunk = new HashMap<>();
        try( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID id = (UUID) rs.getObject( 1 );
                    final int dim = rs.getInt( 2 );
                    if( dim != expectedDim ) {
                        throw new IllegalStateException( "dim mismatch for chunk " + id
                            + ": stored=" + dim + " expected=" + expectedDim );
                    }
                    final float[] v = VectorCodec.decode( rs.getBytes( 3 ), dim );
                    chunkIds.add( id );
                    vectors.add( v );
                    norms.add( CosineSimilarity.norm( v ) );
                    pagesForChunk.put( id, rs.getString( 4 ) );
                }
            }
        }
        if( chunkIds.isEmpty() ) {
            throw new IllegalStateException( "no embeddings found for model_code=" + modelCode
                + " — did you run ExperimentIndexer first?" );
        }
        return new ChunkCorpus( chunkIds, vectors, norms, pagesForChunk );
    }

    /**
     * Builds a {@link TextEmbeddingClient} for the given model code, loading
     * defaults from {@code /ini/wikantik.properties} on the classpath and
     * allowing {@code -D} system-property overrides for the embedding config keys.
     */
    static TextEmbeddingClient buildClient( final String modelCode ) throws IOException {
        final Properties p = new Properties();
        try( InputStream in = ExperimentHarness.class.getResourceAsStream( "/ini/wikantik.properties" ) ) {
            if( in != null ) p.load( in );
        }
        for( final String key : List.of(
            EmbeddingConfig.PROP_BACKEND, EmbeddingConfig.PROP_BASE_URL, EmbeddingConfig.PROP_API_KEY,
            EmbeddingConfig.PROP_OLLAMA_TAG, EmbeddingConfig.PROP_TIMEOUT_MS, EmbeddingConfig.PROP_BATCH_SIZE ) ) {
            final String v = System.getProperty( key );
            if( v != null && !v.isBlank() ) p.setProperty( key, v );
        }
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( EmbeddingConfig.PROP_MODEL, modelCode );
        return EmbeddingClientFactory.create( EmbeddingConfig.fromProperties( p ) ).orElseThrow();
    }

    /** Returns 1-based rank of {@code target} in {@code ranked}, or 0 if not found. */
    static int rankOf( final String target, final List< String > ranked ) {
        for( int i = 0; i < ranked.size(); i++ ) {
            if( ranked.get( i ).equals( target ) ) return i + 1;
        }
        return 0;
    }

    /**
     * Ensures the parent directory of {@code out} exists, then writes {@code text}.
     * Tolerates paths with a {@code null} parent (e.g. a plain filename in CWD).
     */
    static void writeReport( final Path out, final String text ) throws IOException {
        final Path parent = out.getParent();
        if ( parent != null ) {
            Files.createDirectories( parent );
        }
        Files.writeString( out, text );
    }
}

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
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.EmbeddingModel;
import com.wikantik.search.embedding.EmbeddingTextBuilder;
import com.wikantik.search.embedding.TextEmbeddingClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Populates {@code experiment_embeddings} for a given model. Reads all rows
 * from {@code kg_content_chunks}, embeds each chunk's {@code text} with the
 * configured model, writes back with {@code ON CONFLICT DO NOTHING} so reruns
 * are cheap. Runs are per-model; invoke three times for the three candidates.
 */
public final class ExperimentIndexer {

    private static final Logger LOG = LogManager.getLogger( ExperimentIndexer.class );

    private ExperimentIndexer() {}

    @SuppressWarnings("PMD.SystemPrintln")

    public static void main( final String[] args ) throws SQLException, IOException {
        if( args.length < 1 ) {
            System.err.println( """
                Usage: ExperimentIndexer <model-code>
                  model-code: nomic-embed-v1.5 | bge-m3 | qwen3-embedding-0.6b
                Required:    -Dwikantik.experiment.db.password=<pw>
                Optional:    -Dwikantik.experiment.db.url|user, any -Dwikantik.search.embedding.* overrides
                """ );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );

        final TextEmbeddingClient client = buildClient( modelCode );
        LOG.info( "Indexer starting: model={} dim={}", client.modelName(), client.dimension() );

        try( final Connection conn = ExperimentDb.open() ) {
            conn.setAutoCommit( false );

            final int toEmbed = countPendingChunks( conn, model.code() );
            final int total   = countAllChunks( conn );
            LOG.info( "kg_content_chunks: total={}  needs embedding for {}: {}", total, model.code(), toEmbed );
            if( total == 0 ) {
                throw new IllegalStateException(
                    "kg_content_chunks is empty — populate it first via "
                    + "POST /admin/content/rebuild-indexes on the running wiki, "
                    + "then re-run the indexer." );
            }

            final long t0 = System.nanoTime();
            final int embedded = indexAll( conn, client, model.code() );
            final long elapsedMs = ( System.nanoTime() - t0 ) / 1_000_000L;

            LOG.info( "Indexer done: model={} newly_embedded={} poisoned_skipped={} elapsed_ms={} rate={} chunks/s",
                model.code(), embedded, poisonedChunks, elapsedMs,
                embedded > 0 ? String.format( Locale.ROOT, "%.1f", embedded * 1000.0 / elapsedMs ) : "n/a" );
        }
    }

    private static TextEmbeddingClient buildClient( final String modelCode ) throws IOException {
        final Properties p = loadDefaults();
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( EmbeddingConfig.PROP_MODEL, modelCode );
        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( p );
        return EmbeddingClientFactory.create( cfg ).orElseThrow(
            () -> new IllegalStateException( "factory refused to build a client; check config" ) );
    }

    private static Properties loadDefaults() throws IOException {
        final Properties p = new Properties();
        try( final InputStream in = ExperimentIndexer.class.getResourceAsStream( "/ini/wikantik.properties" ) ) {
            if( in != null ) p.load( in );
        }
        for( final String key : List.of(
            EmbeddingConfig.PROP_BACKEND, EmbeddingConfig.PROP_BASE_URL, EmbeddingConfig.PROP_API_KEY,
            EmbeddingConfig.PROP_OLLAMA_TAG, EmbeddingConfig.PROP_TIMEOUT_MS, EmbeddingConfig.PROP_BATCH_SIZE ) ) {
            final String v = System.getProperty( key );
            if( v != null && !v.isBlank() ) p.setProperty( key, v );
        }
        return p;
    }

    private static int countAllChunks( Connection conn ) throws SQLException {
        try( final PreparedStatement ps = conn.prepareStatement( "SELECT COUNT(*) FROM kg_content_chunks" );
             final ResultSet rs = ps.executeQuery() ) {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    private static int countPendingChunks( final Connection conn, final String modelCode ) throws SQLException {
        final String sql = """
            SELECT COUNT(*) FROM kg_content_chunks c
            WHERE NOT EXISTS (
                SELECT 1 FROM experiment_embeddings e
                WHERE e.chunk_id = c.id AND e.model_code = ?
            )
            """;
        try( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, modelCode );
            try( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        }
    }

    private static int indexAll( final Connection conn, final TextEmbeddingClient client, final String modelCode )
            throws SQLException {
        final String selectSql = """
            SELECT c.id, c.text, c.heading_path
            FROM kg_content_chunks c
            WHERE NOT EXISTS (
                SELECT 1 FROM experiment_embeddings e
                WHERE e.chunk_id = c.id AND e.model_code = ?
            )
            ORDER BY c.page_name, c.chunk_index
            """;
        final String insertSql = """
            INSERT INTO experiment_embeddings (chunk_id, model_code, dim, vec)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (chunk_id, model_code) DO NOTHING
            """;

        int embedded = 0;
        int logEvery = 200;

        try( final PreparedStatement sel = conn.prepareStatement( selectSql ) ) {
            sel.setString( 1, modelCode );
            sel.setFetchSize( 500 );
            try( final ResultSet rs = sel.executeQuery();
                 final PreparedStatement ins = conn.prepareStatement( insertSql ) ) {

                final List< UUID >   batchIds   = new ArrayList<>();
                final List< String > batchTexts = new ArrayList<>();
                while( rs.next() ) {
                    batchIds.add( (UUID) rs.getObject( 1 ) );
                    batchTexts.add( EmbeddingTextBuilder.forDocument(
                        readHeadingPath( rs, 3 ), rs.getString( 2 ) ) );
                    if( batchIds.size() >= 32 ) {
                        embedded += flushBatch( client, ins, modelCode, batchIds, batchTexts );
                        batchIds.clear(); batchTexts.clear();
                        if( embedded >= logEvery ) {
                            LOG.info( "  … embedded {} chunks", embedded );
                            logEvery += 200;
                        }
                    }
                }
                if( !batchIds.isEmpty() ) {
                    embedded += flushBatch( client, ins, modelCode, batchIds, batchTexts );
                }
            }
        }
        conn.commit();
        return embedded;
    }

    private static int flushBatch( final TextEmbeddingClient client, final PreparedStatement ins,
                                   final String modelCode,
                                   final List< UUID > ids, final List< String > texts ) throws SQLException {
        final List< float[] > vectors = embedBatchSkippingPoisoned( client, ids, texts );
        int inserted = 0;
        for( int i = 0; i < ids.size(); i++ ) {
            final float[] v = vectors.get( i );
            if( v == null ) continue;                 // poisoned chunk — skip
            ins.setObject( 1, ids.get( i ) );
            ins.setString( 2, modelCode );
            ins.setInt( 3, v.length );
            ins.setBytes( 4, VectorCodec.encode( v ) );
            ins.addBatch();
            inserted++;
        }
        if( inserted > 0 ) ins.executeBatch();
        return inserted;
    }

    /**
     * Embeds a batch, but when a batch-level call fails (e.g. bge-m3 returns
     * HTTP 500 with NaN in the response — a deterministic server-side bug for
     * specific inputs), falls back to per-item calls. Items that still fail
     * individually are marked null and skipped by the caller.
     */
    private static List< float[] > embedBatchSkippingPoisoned( final TextEmbeddingClient client,
                                                               final List< UUID > ids,
                                                               final List< String > texts ) {
        try {
            return new ArrayList<>( client.embed( texts, EmbeddingKind.DOCUMENT ) );
        } catch( final RuntimeException batchFailed ) {
            LOG.warn( "batch of {} failed ({}) — retrying per-item",
                      texts.size(), batchFailed.getMessage() );
        }
        final List< float[] > out = new ArrayList<>( texts.size() );
        int poisoned = 0;
        for( int i = 0; i < texts.size(); i++ ) {
            try {
                out.add( client.embed( List.of( texts.get( i ) ), EmbeddingKind.DOCUMENT ).get( 0 ) );
            } catch( final RuntimeException perItem ) {
                poisonedChunks++;
                poisoned++;
                LOG.warn( "skipping chunk id={} len={} — model failed: {}",
                          ids.get( i ), texts.get( i ).length(), perItem.getMessage() );
                out.add( null );
            }
        }
        if( poisoned > 0 ) {
            LOG.info( "per-item fallback: {} succeeded, {} skipped", texts.size() - poisoned, poisoned );
        }
        return out;
    }

    private static int poisonedChunks = 0;

    /**
     * Reads a PostgreSQL {@code text[]} column as an immutable {@link List}.
     * Null columns map to an empty list so chunks without heading context
     * degrade to body-only embedding via {@link EmbeddingTextBuilder}.
     */
    private static List< String > readHeadingPath( final ResultSet rs, final int col ) throws SQLException {
        final Array arr = rs.getArray( col );
        if( arr == null ) return List.of();
        final String[] raw = (String[]) arr.getArray();
        return raw == null ? List.of() : List.of( raw );
    }
}

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
package com.wikantik.search.subsystem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.knowledge.chunking.ChunkProjector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Postgres-backed coverage for the {@code wireHybridRetrieval} branches that only run with a
 * real backing store: the no-embedding-client BM25 refresh sink (which never gets a chance to
 * run without real chunks + a real {@code LuceneBm25ChunkIndex} build), and the full
 * embedding-enabled pipeline's contextual-embedding resolver + Caffeine metrics registration.
 *
 * <p>No real inference host is contacted: the embedding-enabled test points
 * {@code wikantik.search.embedding.base-url} at a local {@link HttpServer} stub.</p>
 */
@RequiresPostgres
class SearchWiringHelperPostgresTest {

    @Test
    void wireHybridRetrieval_noEmbeddingClient_wiresLexicalRefreshSinkOntoRealBm25Index() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "kg_content_chunks" );

        final UUID chunkId;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_content_chunks "
                  + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
                  + "VALUES ('SearchWiringBm25Page', 0, 'wiring helper lexical content', 30, 5, "
                  + "'search-wiring-bm25-hash') RETURNING id" ) ) {
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                chunkId = (UUID) rs.getObject( 1 );
            }
        }

        final Properties props = new Properties();
        // Embedding disabled by default (PROP_ENABLED absent) -> clientOpt.isEmpty().
        props.setProperty( "wikantik.bundle.bm25.enabled", "true" );

        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            new ContentChunkRepository( ds ), /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        @SuppressWarnings( "unchecked" )
        final ArgumentCaptor< Consumer< List< UUID > > > sinkCaptor = ArgumentCaptor.forClass( Consumer.class );
        verify( chunkProjector ).addPostChunkSink( sinkCaptor.capture() );
        assertNotNull( sinkCaptor.getValue(),
            "a real BM25 index must wire a post-chunk sink to stay current without an embedding client" );

        // Drive it exactly as a real page save would, exercising the shared BM25_REFRESH
        // executor's lazy (once-per-JVM) daemon thread creation.
        sinkCaptor.getValue().accept( List.of( chunkId ) );

        final long deadline = System.currentTimeMillis() + 5_000;
        boolean sawRefreshThread = false;
        while ( System.currentTimeMillis() < deadline ) {
            sawRefreshThread = Thread.getAllStackTraces().keySet().stream()
                .anyMatch( t -> "wikantik-bm25-refresh".equals( t.getName() ) && t.isDaemon() );
            if ( sawRefreshThread ) break;
            Thread.sleep( 20 );
        }
        assertTrue( sawRefreshThread,
            "the BM25 refresh executor must have created its daemon worker thread by now" );
    }

    @Test
    void wireHybridRetrieval_withRealEmbeddingBackend_resolvesPageContextAndRegistersCacheMetrics() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "content_chunk_embeddings", "kg_content_chunks" );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_content_chunks "
                  + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
                  + "VALUES ('SearchWiringCtxPage', 0, 'content about search wiring context resolution', "
                  + "48, 8, 'search-wiring-ctx-hash')" ) ) {
            ps.executeUpdate();
        }

        final HttpServer stub = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        stub.setExecutor( null );
        stub.createContext( "/api/embed", exchange -> {
            final String body = new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 );
            final JsonObject reqJson = JsonParser.parseString( body ).getAsJsonObject();
            final int inputCount = reqJson.getAsJsonArray( "input" ).size();
            final JsonArray embeddings = new JsonArray();
            for ( int i = 0; i < inputCount; i++ ) {
                final JsonArray vec = new JsonArray();
                for ( int j = 0; j < 1024; j++ ) vec.add( 0.001f * ( i + j ) );
                embeddings.add( vec );
            }
            final JsonObject resp = new JsonObject();
            resp.add( "embeddings", embeddings );
            final byte[] out = resp.toString().getBytes( StandardCharsets.UTF_8 );
            exchange.getResponseHeaders().set( "Content-Type", "application/json" );
            exchange.sendResponseHeaders( 200, out.length );
            try ( OutputStream os = exchange.getResponseBody() ) { os.write( out ); }
        } );
        stub.start();

        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MeterRegistryHolder.set( meterRegistry );
        try {
            final Properties props = new Properties();
            props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
            props.setProperty( EmbeddingConfig.PROP_BASE_URL, "http://127.0.0.1:" + stub.getAddress().getPort() );
            props.setProperty( EmbeddingConfig.PROP_MODEL, "bge-m3" );
            props.setProperty( EmbeddingConfig.PROP_TIMEOUT_MS, "5000" );
            props.setProperty( "wikantik.search.dense.backend", "inmemory" );
            props.setProperty( "wikantik.bundle.bm25.enabled", "true" );

            final PageManager pageManager = mock( PageManager.class );
            when( pageManager.getPureText( "SearchWiringCtxPage", -1 ) ).thenReturn(
                "---\ntitle: Search Wiring Context Page\ncluster: search-wiring\n"
                + "summary: exercises the contextual embedding resolver\n---\nBody content." );
            final FrontmatterMetadataCache fmCache = new FrontmatterMetadataCache( pageManager );
            final ContentChunkRepository chunkRepo = new ContentChunkRepository( ds );
            final ChunkProjector chunkProjector = mock( ChunkProjector.class );
            final WikiEngine engine = mock( WikiEngine.class );

            SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
                chunkRepo, fmCache, /*rebuildService=*/ null, engine );

            final ArgumentCaptor< ChunkVectorIndex > vecCaptor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
            verify( engine ).setChunkVectorIndex( vecCaptor.capture() );
            assertInstanceOf( InMemoryChunkVectorIndex.class, vecCaptor.getValue() );

            final ArgumentCaptor< BootstrapEmbeddingIndexer > bootCaptor =
                ArgumentCaptor.forClass( BootstrapEmbeddingIndexer.class );
            verify( engine ).setManager( org.mockito.ArgumentMatchers.eq( BootstrapEmbeddingIndexer.class ),
                bootCaptor.capture() );
            final BootstrapEmbeddingIndexer bootstrap = bootCaptor.getValue();

            final long deadline = System.currentTimeMillis() + 15_000;
            BootstrapEmbeddingIndexer.State state = bootstrap.progress().state();
            while ( ( state == BootstrapEmbeddingIndexer.State.IDLE
                    || state == BootstrapEmbeddingIndexer.State.RUNNING )
                    && System.currentTimeMillis() < deadline ) {
                Thread.sleep( 50 );
                state = bootstrap.progress().state();
            }
            if ( state != BootstrapEmbeddingIndexer.State.COMPLETED ) {
                fail( "bootstrap embedding run did not complete in time; state=" + state
                    + " error=" + bootstrap.progress().errorMessage() );
            }

            // The contextual-embedding resolver (ctxResolver) must have consulted the
            // frontmatter cache for the page whose chunk was just embedded.
            verify( pageManager ).getPureText( "SearchWiringCtxPage", -1 );

            // Caffeine cache metrics for both chunkRepo and fmCache must be registered
            // once a MeterRegistry is available.
            assertNotNull( meterRegistry.find( "wikantik_cache.size" ).tag( "cache", "chunk_text" ).gauge(),
                "chunk_text cache metrics must be registered" );
            assertNotNull( meterRegistry.find( "wikantik_cache.size" ).tag( "cache", "frontmatter_metadata" ).gauge(),
                "frontmatter_metadata cache metrics must be registered" );
        } finally {
            MeterRegistryHolder.set( null );
            stub.stop( 0 );
        }
    }
}

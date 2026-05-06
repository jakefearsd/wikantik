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

import com.sun.net.httpserver.HttpServer;
import com.wikantik.PostgresTestContainer;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Shared scaffolding for the PG-gated page-extraction integration tests.
 *
 * <p><strong>Plan deviation:</strong> the spec
 * (docs/superpowers/plans/2026-05-01-kg-extraction-redesign.md tasks 3.2, 3.3)
 * places these in {@code wikantik-it-tests/wikantik-it-test-rest} as
 * Cargo-driven {@code *IT.java} tests with WireMock. The tests don't actually
 * exercise the Tomcat surface — they seed PG, run the indexer in-process, and
 * assert against the DB — so they live here as PG-gated {@code *Test.java}
 * files (matching the Phase 2 pattern in {@link ProposalUpserterTest}). The
 * fake Ollama is a small embedded JDK {@link HttpServer} instead of WireMock
 * to avoid pulling a new test dependency.</p>
 */
abstract class PageExtractionPgTestBase {

    /** Unique-to-the-IT entity name; never collides with real corpus Kafka proposals. */
    protected static final String IT_ENTITY = "ITKafkaUnique20260501";

    protected static final String STUBBED_OLLAMA_BODY =
        "{\"message\":{\"content\":\"{\\\"entities\\\":[{\\\"name\\\":\\\""
      + IT_ENTITY + "\\\","
      + "\\\"type\\\":\\\"Technology\\\",\\\"evidence_span\\\":\\\""
      + IT_ENTITY + "\\\","
      + "\\\"confidence\\\":0.9}],\\\"relations\\\":[]}\"}}";

    protected DataSource ds;
    protected HttpServer fakeOllama;
    protected String fakeOllamaBaseUrl;

    protected void seedFiveKafkaPages() throws Exception {
        ds = openLivePg();
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.executeUpdate( String.format(
                "DELETE FROM kg_proposals WHERE source_page LIKE 'IT_Page%%' OR proposed_data ->> 'name' = '%s'",
                IT_ENTITY ) );
            st.execute( "DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_Page%'" );
            // Each chunk text contains IT_ENTITY so EvidenceGroundingVerifier
            // accepts the extractor's evidence_span.
            final String text = IT_ENTITY + " is a streaming platform.";
            for ( int i = 1; i <= 5; i++ ) {
                try ( var ps = c.prepareStatement(
                    "INSERT INTO kg_content_chunks (page_name, chunk_index, text, char_count, "
                  + "token_count_estimate, content_hash) VALUES (?, 0, ?, ?, ?, ?)" ) ) {
                    ps.setString( 1, "IT_Page" + i );
                    ps.setString( 2, text );
                    ps.setInt( 3, text.length() );
                    ps.setInt( 4, text.length() / 4 );
                    ps.setString( 5, "ithash-" + i );
                    ps.executeUpdate();
                }
            }
        }
        startFakeOllama();
    }

    protected void cleanup() throws Exception {
        if ( fakeOllama != null ) {
            fakeOllama.stop( 0 );
            fakeOllama = null;
        }
        if ( ds != null ) {
            try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
                st.executeUpdate( String.format(
                    "DELETE FROM kg_proposals WHERE source_page LIKE 'IT_Page%%' OR proposed_data ->> 'name' = '%s'",
                    IT_ENTITY ) );
                st.execute( "DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_Page%'" );
            }
        }
    }

    protected BootstrapEntityExtractionIndexer newIndexer() {
        final OllamaPageExtractor extractor = new OllamaPageExtractor(
            HttpClient.newHttpClient(), fakeOllamaBaseUrl, "ollama-test:latest", 60_000L,
            new PageExtractionResponseParser( new EvidenceGroundingVerifier(), 12, 8 ) );
        final KgNodeRepository kgNodes         = new KgNodeRepository( ds );
        final KgProposalRepository kgProposals = new KgProposalRepository( ds );
        // Filter to just the IT pages so the indexer doesn't walk the whole
        // live corpus (928 pages on the dev machine) — the indexer takes
        // listDistinctPageNames at face value, so wrapping the repo is the
        // narrowest seam to scope the run without touching production code.
        final ContentChunkRepository chunkRepo = new ContentChunkRepository( ds ) {
            @Override
            public List< String > listDistinctPageNames() {
                return super.listDistinctPageNames().stream()
                    .filter( n -> n.startsWith( "IT_Page" ) )
                    .sorted()
                    .toList();
            }
        };
        final ChunkEntityMentionRepository mentionRepo = new ChunkEntityMentionRepository( ds );
        final KgNodeEmbeddingRepository embRepo = new KgNodeEmbeddingRepository( ds );
        return new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(),
            new ProposalUpserter( kgProposals ),
            /*embeddingService*/ null, embRepo,
            chunkRepo, mentionRepo, kgNodes, new MentionAttributor(),
            PageEmbeddingProvider.EMPTY, /*excludedPages*/ null,
            /*concurrency*/ 1, /*dictionaryTopK*/ 0,
            /*maxEntitiesPerPage*/ 12, /*maxRelationsPerPage*/ 8 );
    }

    protected void runUntilDone( final BootstrapEntityExtractionIndexer indexer ) throws InterruptedException {
        indexer.start( /*forceOverwrite*/ false );
        final long deadline = System.currentTimeMillis() + 60_000L;
        while ( indexer.isRunning() && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 200L );
        }
        if ( indexer.isRunning() ) {
            indexer.cancel();
            throw new AssertionError( "indexer did not finish in 60s" );
        }
    }

    protected long countPendingByName( final String name ) throws Exception {
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement(
                  "SELECT COUNT(*) FROM kg_proposals "
                + "WHERE status = 'pending' AND proposed_data ->> 'name' = ?" ) ) {
            ps.setString( 1, name );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getLong( 1 );
            }
        }
    }

    protected int supportCountByName( final String name ) throws Exception {
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement(
                  "SELECT support_count FROM kg_proposals "
                + "WHERE status = 'pending' AND proposed_data ->> 'name' = ?" ) ) {
            ps.setString( 1, name );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return -1;
                return rs.getInt( 1 );
            }
        }
    }

    protected Instant lastSeenAtByName( final String name ) throws Exception {
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement(
                  "SELECT last_seen_at FROM kg_proposals "
                + "WHERE status = 'pending' AND proposed_data ->> 'name' = ?" ) ) {
            ps.setString( 1, name );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return null;
                final Timestamp ts = rs.getTimestamp( 1 );
                return ts == null ? null : ts.toInstant();
            }
        }
    }

    private void startFakeOllama() throws Exception {
        fakeOllama = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        fakeOllama.createContext( "/api/chat", exchange -> {
            final byte[] resp = STUBBED_OLLAMA_BODY.getBytes( StandardCharsets.UTF_8 );
            exchange.getResponseHeaders().set( "Content-Type", "application/json" );
            exchange.sendResponseHeaders( 200, resp.length );
            try ( OutputStream os = exchange.getResponseBody() ) {
                os.write( resp );
            }
        } );
        fakeOllama.start();
        fakeOllamaBaseUrl = "http://127.0.0.1:" + fakeOllama.getAddress().getPort();
    }

    private static DataSource openLivePg() {
        return PostgresTestContainer.createDataSource();
    }
}

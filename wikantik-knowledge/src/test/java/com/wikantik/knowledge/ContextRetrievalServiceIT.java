/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.testfakes.FakeChunkVectorIndex;
import com.wikantik.knowledge.testfakes.FakeEngine;
import com.wikantik.knowledge.testfakes.FakeHybridSearch;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.knowledge.testfakes.FakeSearchManager;
import com.wikantik.knowledge.testfakes.FakeSearchResult;
import com.wikantik.search.hybrid.ScoredChunk;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test against a PostgresTestContainer with seeded chunks.
 * Uses real DB-backed ContentChunkRepository + NodeMentionSimilarity;
 * stubs BM25/hybrid/page-manager with test fakes.
 */
@Testcontainers( disabledWithoutDocker = true )
class ContextRetrievalServiceIT {

    private static DataSource dataSource;

    @BeforeAll
    static void init() { dataSource = PostgresTestContainer.createDataSource(); }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void retrieve_endToEnd_seededCorpus() throws Exception {
        final UUID alphaC1 = UUID.randomUUID();
        final UUID betaC1  = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "  char_count, token_count_estimate, content_hash) VALUES "
              + "('" + alphaC1 + "', 'Alpha', 0, ARRAY['Alpha','Intro'], 'alpha body one', 14, 4, 'h1'), "
              + "('" + betaC1  + "', 'Beta',  0, ARRAY['Beta'], 'beta body',             9, 3, 'h2')" );
        }

        final var chunkRepo = new ContentChunkRepository( dataSource );
        final var similarity = new NodeMentionSimilarity( dataSource, "qwen3-embedding-0.6b" );

        final var chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        chunkIndex.setDim( 1024 );
        chunkIndex.setTopK( List.of(
            new ScoredChunk( alphaC1, "Alpha", 0.9 ),
            new ScoredChunk( betaC1,  "Beta",  0.7 ) ) );

        final var searchManager = new FakeSearchManager();
        searchManager.setResults( List.of(
            FakeSearchResult.of( "Alpha", 5 ),
            FakeSearchResult.of( "Beta", 3 ) ) );

        final var pageManager = new FakePageManager();
        pageManager.addPage( "Alpha", "---\nsummary: a\n---\nbody", "seed", new Date() );
        pageManager.addPage( "Beta", "---\nsummary: b\n---\nbody", "seed", new Date() );

        final var hybrid = FakeHybridSearch.enabledReturning( List.of( "Alpha", "Beta" ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            FakeEngine.create(),
            searchManager, hybrid, null, chunkIndex, chunkRepo, similarity,
            pageManager, null, "https://wiki.example" );

        final var result = svc.retrieve( new ContextQuery( "alpha body", 5, 3, null ) );

        assertEquals( 2, result.pages().size() );
        assertEquals( "Alpha", result.pages().get( 0 ).name() );
        assertFalse( result.pages().get( 0 ).contributingChunks().isEmpty(),
            "real ContentChunkRepository should have resolved chunk content" );
        assertEquals( List.of( "Alpha", "Intro" ),
            result.pages().get( 0 ).contributingChunks().get( 0 ).headingPath() );
    }
}

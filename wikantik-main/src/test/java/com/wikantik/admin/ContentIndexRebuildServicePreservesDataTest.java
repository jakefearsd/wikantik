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
package com.wikantik.admin;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.chunking.ChunkDiff;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Real-database tests: "Rebuild indexes" must not destroy the embeddings and Knowledge Graph
 * mentions of chunks whose text did not change (the old wholesale {@code deleteAll()} cascaded
 * into {@code content_chunk_embeddings} and {@code chunk_entity_mentions}).
 */
@RequiresPostgres
class ContentIndexRebuildServicePreservesDataTest {

    private static final String MODEL = "test-model";
    private static final int DIM = 1024;

    private static DataSource ds;

    private final Map< String, String > bodies = new LinkedHashMap<>();
    private final AtomicInteger embeddedTexts = new AtomicInteger();
    private ContentChunkRepository repo;
    private ContentIndexRebuildService svc;

    @BeforeAll
    static void initDs() {
        ds = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestDb.truncate( "kg_content_chunks", "kg_nodes" );
        bodies.clear();
        embeddedTexts.set( 0 );
        repo = new ContentChunkRepository( ds );

        final PageManager pm = mock( PageManager.class );
        lenient().when( pm.getAllPages() ).thenAnswer( ( InvocationOnMock inv ) -> {
            final List< Page > pages = new ArrayList<>();
            for ( final String n : bodies.keySet() ) {
                final Page p = mock( Page.class );
                lenient().when( p.getName() ).thenReturn( n );
                pages.add( p );
            }
            return pages;
        } );
        lenient().when( pm.getPureText( any( Page.class ) ) ).thenAnswer(
            ( InvocationOnMock inv ) -> bodies.get( inv.getArgument( 0, Page.class ).getName() ) );
        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenReturn( false );

        final LuceneReindexQueue lucene = new LuceneReindexQueue() {
            @Override public void reindexPage( final Page page ) {}
            @Override public int queueDepth() { return 0; }
            @Override public int documentCount() { return 0; }
            @Override public Instant lastUpdateInstant() { return null; }
            @Override public void clearIndex() {}
        };

        final TextEmbeddingClient client = new TextEmbeddingClient() {
            @Override public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
                final List< float[] > out = new ArrayList<>();
                for ( int i = 0; i < texts.size(); i++ ) {
                    embeddedTexts.incrementAndGet();
                    out.add( new float[ DIM ] );
                }
                return out;
            }
            @Override public int dimension() { return DIM; }
            @Override public String modelName() { return MODEL; }
        };

        svc = new ContentIndexRebuildService( pm, sp, lucene, repo,
            new ContentChunker( new ContentChunker.Config( 900, 8, 0, 0 ) ), () -> true, 10L );
        svc.setEmbeddingHook( new EmbeddingIndexService( ds, client ), MODEL );
    }

    private static String section( final String heading, final String word ) {
        return "# " + heading + "\n\nBody of " + heading + ". " + ( word + " " ).repeat( 20 ) + "\n\n";
    }

    private void rebuild() {
        svc.triggerRebuild();
        await().atMost( Duration.ofSeconds( 60 ) ).until( () ->
            svc.snapshot().rebuild().state().equals( "IDLE" ) );
    }

    private int count( final String sql ) throws Exception {
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( sql ) ) {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    private Map< Integer, UUID > chunkIds( final String page ) {
        final Map< Integer, UUID > m = new LinkedHashMap<>();
        for ( final ChunkDiff.Stored s : repo.findByPage( page ) ) {
            m.put( s.chunkIndex(), s.id() );
        }
        return m;
    }

    @Test
    void rebuildKeepsEmbeddingsAndMentionsOfUnchangedChunksAndOnlyReembedsChangedOnes() throws Exception {
        bodies.put( "Stable", section( "Stable", "alpha" ) );
        bodies.put( "Edited", section( "First", "beta" ) + section( "Second", "gamma" ) );
        rebuild();
        final Map< Integer, UUID > stableBefore = chunkIds( "Stable" );
        final Map< Integer, UUID > editedBefore = chunkIds( "Edited" );
        assertEquals( 1, stableBefore.size() );
        assertEquals( 2, editedBefore.size(), "Edited must chunk into two sections" );
        assertEquals( 3, count( "SELECT COUNT(*) FROM content_chunk_embeddings" ) );

        final UUID stableChunk = stableBefore.get( 0 );
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "INSERT INTO kg_nodes (name, node_type, provenance, properties, tier, modified) "
                + "VALUES ('Alpha', 'concept', 'human-authored', '{}'::jsonb, 'human', NOW())" );
            st.execute( "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
                + "SELECT '" + stableChunk + "', id, 0.9, 'test' FROM kg_nodes WHERE name = 'Alpha'" );
        }

        bodies.put( "Edited", section( "First", "beta" ) + section( "Second", "CHANGED" ) );
        embeddedTexts.set( 0 );
        rebuild();

        assertEquals( stableBefore, chunkIds( "Stable" ), "unchanged page keeps its chunk ids" );
        assertEquals( 1, count( "SELECT COUNT(*) FROM content_chunk_embeddings WHERE chunk_id = '" + stableChunk + "'" ),
            "unchanged chunk keeps its embedding" );
        assertEquals( 1, count( "SELECT COUNT(*) FROM chunk_entity_mentions WHERE chunk_id = '" + stableChunk + "'" ),
            "unchanged chunk keeps its KG mention" );

        assertEquals( editedBefore, chunkIds( "Edited" ), "an in-place edit updates rows, it does not replace them" );
        assertEquals( 3, count( "SELECT COUNT(*) FROM content_chunk_embeddings" ) );
        assertEquals( 1, embeddedTexts.get(), "only the single changed chunk is re-embedded" );
    }

    @Test
    void rebuildRemovesChunksOfPagesThatNoLongerExist() throws Exception {
        bodies.put( "Keep", section( "Keep", "alpha" ) );
        bodies.put( "Ghost", section( "Ghost", "beta" ) );
        rebuild();
        assertEquals( 1, chunkIds( "Ghost" ).size() );

        bodies.remove( "Ghost" );
        rebuild();

        assertTrue( chunkIds( "Ghost" ).isEmpty(), "chunks of a deleted page are swept" );
        assertEquals( 1, chunkIds( "Keep" ).size() );
        assertEquals( 1, count( "SELECT COUNT(*) FROM content_chunk_embeddings" ),
            "the deleted page's embeddings go with its chunks" );
    }

    @Test
    void rebuildThatSeesNoPagesKeepsEveryChunk() throws Exception {
        bodies.put( "P", section( "P", "alpha" ) );
        rebuild();
        final UUID before = chunkIds( "P" ).get( 0 );

        // A provider hiccup that lists no pages must not sweep the whole corpus away.
        bodies.clear();
        rebuild();

        assertEquals( before, chunkIds( "P" ).get( 0 ) );
        assertEquals( 1, count( "SELECT COUNT(*) FROM content_chunk_embeddings" ) );
    }

    @Test
    void rebuildFillsMissingEmbeddingsForUnchangedChunks() throws Exception {
        bodies.put( "P", section( "P", "alpha" ) );
        rebuild();
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DELETE FROM content_chunk_embeddings" );
        }
        embeddedTexts.set( 0 );
        final UUID before = chunkIds( "P" ).get( 0 );
        rebuild();
        assertEquals( before, chunkIds( "P" ).get( 0 ) );
        assertEquals( 1, embeddedTexts.get() );
        assertEquals( 1, count( "SELECT COUNT(*) FROM content_chunk_embeddings" ) );
        assertNotEquals( 0, svc.snapshot().rebuild().embeddingsIndexed() );
    }
}

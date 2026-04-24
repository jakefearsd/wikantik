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

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.search.SearchManager;
import com.wikantik.knowledge.testfakes.FakeDeps;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.knowledge.testfakes.FakeSearchManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultContextRetrievalServiceTest {

    @Test
    void getPage_returnsNullWhenMissing() {
        final DefaultContextRetrievalService svc = FakeDeps.minimal().build();
        assertNull( svc.getPage( "Nonexistent" ) );
    }

    @Test
    void getPage_returnsShapedRecord() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "---\nsummary: the hub\ncluster: search\n"
                + "tags: [retrieval, search]\n---\n\nBody", "alice", Date.from( Instant.parse( "2026-04-23T00:00:00Z" ) ) );
        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .pageManager( pm ).baseUrl( "https://wiki.example" ).build();

        final RetrievedPage p = svc.getPage( "Hub" );

        assertNotNull( p );
        assertEquals( "Hub", p.name() );
        assertEquals( "https://wiki.example/Hub", p.url() );
        assertEquals( "the hub", p.summary() );
        assertEquals( "search", p.cluster() );
        assertEquals( java.util.List.of( "retrieval", "search" ), p.tags() );
        assertEquals( 0.0, p.score() );
        assertTrue( p.contributingChunks().isEmpty() );
        assertTrue( p.relatedPages().isEmpty() );
        assertEquals( "alice", p.author() );
        assertEquals( java.util.Date.from( java.time.Instant.parse( "2026-04-23T00:00:00Z" ) ),
            p.lastModified() );
    }

    @Test
    void listMetadataValues_countsDistinctClusters() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ncluster: search\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "B", "---\ncluster: search\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "C", "---\ncluster: kg\n---\n\n", "bob", new java.util.Date() );
        pm.addPage( "D", "---\n---\n\n", "bob", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var values = svc.listMetadataValues( "cluster" );

        assertEquals( 2, values.size() );
        assertEquals( "search", values.get( 0 ).value() );
        assertEquals( 2, values.get( 0 ).count() );
        assertEquals( "kg", values.get( 1 ).value() );
        assertEquals( 1, values.get( 1 ).count() );
    }

    @Test
    void listMetadataValues_expandsListFields() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ntags: [retrieval, search]\n---\n\n", "b", new java.util.Date() );
        pm.addPage( "B", "---\ntags: [search, kg]\n---\n\n", "b", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var values = svc.listMetadataValues( "tags" );

        assertEquals( 3, values.size() );
        assertEquals( "search", values.get( 0 ).value() );
        assertEquals( 2, values.get( 0 ).count() );
    }

    @Test
    void listPages_filtersByCluster() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "S1", "---\ncluster: search\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "K1", "---\ncluster: kg\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "S2", "---\ncluster: search\n---\n\n", "a", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            "search", null, null, null, null, null, 50, 0 ) );

        assertEquals( 2, result.totalMatched() );
        assertEquals( 2, result.pages().size() );
        assertTrue( result.pages().stream().allMatch( p -> "search".equals( p.cluster() ) ) );
    }

    @Test
    void listPages_filtersByTag() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "A", "---\ntags: [search, retrieval]\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "B", "---\ntags: [kg]\n---\n\n", "a", new java.util.Date() );

        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            null, java.util.List.of( "search" ), null, null, null, null, 50, 0 ) );

        assertEquals( 1, result.pages().size() );
        assertEquals( "A", result.pages().get( 0 ).name() );
    }

    @Test
    void listPages_respectsLimitAndOffset() {
        final FakePageManager pm = new FakePageManager();
        for ( int i = 0; i < 10; i++ ) {
            pm.addPage( "P" + i, "---\n---\n\n", "a", new java.util.Date() );
        }
        final DefaultContextRetrievalService svc = FakeDeps.minimal().pageManager( pm ).build();
        final var result = svc.listPages( new com.wikantik.api.knowledge.PageListFilter(
            null, null, null, null, null, null, 3, 4 ) );

        assertEquals( 10, result.totalMatched() );
        assertEquals( 3, result.pages().size() );
        assertEquals( "P4", result.pages().get( 0 ).name() );
        assertEquals( "P6", result.pages().get( 2 ).name() );
    }

    @Test
    void retrieve_returnsPagesInBm25Order_whenHybridDisabled() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\nsummary: alpha summary\n---\n\nbody", "a", new java.util.Date() );
        pm.addPage( "Beta",  "---\nsummary: beta summary\n---\n\nbody", "a", new java.util.Date() );
        pm.addPage( "Gamma", "---\nsummary: gamma summary\n---\n\nbody", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Beta", 5 ),
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 3 ) ) );

        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .search( sm ).pageManager( pm ).build();

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "bm25 hit terms", 5, 3, null ) );

        assertEquals( "bm25 hit terms", result.query() );
        assertEquals( 2, result.totalMatched() );
        assertEquals( 2, result.pages().size() );
        assertEquals( "Beta", result.pages().get( 0 ).name() );
        assertEquals( "Alpha", result.pages().get( 1 ).name() );
        assertEquals( "beta summary", result.pages().get( 0 ).summary() );
        assertTrue( result.pages().get( 0 ).contributingChunks().isEmpty(),
            "chunks populated in later task" );
        assertTrue( result.pages().get( 0 ).relatedPages().isEmpty(),
            "relatedPages populated in later task" );
    }

    @Test
    void retrieve_respectsMaxPages() {
        final FakePageManager pm = new FakePageManager();
        for ( int i = 0; i < 8; i++ ) {
            pm.addPage( "P" + i, "---\n---\n\n", "a", new java.util.Date() );
        }
        final FakeSearchManager sm = new FakeSearchManager();
        final var srs = new java.util.ArrayList< com.wikantik.api.search.SearchResult >();
        for ( int i = 0; i < 8; i++ ) {
            srs.add( com.wikantik.knowledge.testfakes.FakeSearchResult.of( "P" + i, 8 - i ) );
        }
        sm.setResults( srs );

        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .search( sm ).pageManager( pm ).build();

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery( "q", 3, 3, null ) );

        assertEquals( 8, result.totalMatched() );
        assertEquals( 3, result.pages().size() );
    }

    @Test
    void retrieve_populatesRelatedPages() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "Beta",  "---\n---\n\n", "a", new java.util.Date() );
        pm.addPage( "Gamma", "---\n---\n\n", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 5 ) ) );

        final var mentionIndex = com.wikantik.knowledge.testfakes.FakeMentionIndex.relating(
            "Alpha",
            java.util.List.of(
                new com.wikantik.knowledge.MentionIndex.RelatedByMention(
                    "Beta",
                    java.util.List.of( "BM25", "Qwen3" ),
                    2 ),
                new com.wikantik.knowledge.MentionIndex.RelatedByMention(
                    "Gamma",
                    java.util.List.of( "BM25" ),
                    1 ) ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            com.wikantik.knowledge.testfakes.FakeEngine.create(),
            sm, null, null, null, null, null, mentionIndex, pm, null, "" );

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "q", 5, 3, null ) );

        assertEquals( 1, result.pages().size() );
        final var relatedPages = result.pages().get( 0 ).relatedPages();
        assertEquals( 2, relatedPages.size() );
        assertEquals( "Beta", relatedPages.get( 0 ).name() );
        assertTrue( relatedPages.get( 0 ).reason().contains( "BM25" ),
            "reason should name the shared entities, not an opaque similarity score" );
        assertTrue( relatedPages.get( 0 ).reason().contains( "Qwen3" ) );
    }

    @Test
    void retrieve_populatesContributingChunksFromDenseIndex() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\n---\nbody", "a", new java.util.Date() );
        pm.addPage( "Beta", "---\n---\nbody", "a", new java.util.Date() );

        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( java.util.List.of(
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Alpha", 5 ),
            com.wikantik.knowledge.testfakes.FakeSearchResult.of( "Beta", 3 ) ) );

        final java.util.UUID alphaC1 = java.util.UUID.randomUUID();
        final java.util.UUID alphaC2 = java.util.UUID.randomUUID();
        final java.util.UUID betaC1  = java.util.UUID.randomUUID();

        final var chunkIndex = new com.wikantik.knowledge.testfakes.FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        chunkIndex.setDim( 8 );
        chunkIndex.setTopK( java.util.List.of(
            new com.wikantik.search.hybrid.ScoredChunk( alphaC1, "Alpha", 0.9 ),
            new com.wikantik.search.hybrid.ScoredChunk( alphaC2, "Alpha", 0.8 ),
            new com.wikantik.search.hybrid.ScoredChunk( betaC1,  "Beta",  0.7 ) ) );

        final var chunkRepo = new com.wikantik.knowledge.testfakes.FakeChunkRepository();
        chunkRepo.addChunk( alphaC1, "Alpha", 0, java.util.List.of( "Alpha", "Intro" ), "first chunk of Alpha" );
        chunkRepo.addChunk( alphaC2, "Alpha", 1, java.util.List.of( "Alpha", "Details" ), "second chunk of Alpha" );
        chunkRepo.addChunk( betaC1,  "Beta",  0, java.util.List.of( "Beta"  ), "beta chunk one" );

        final var hybrid = com.wikantik.knowledge.testfakes.FakeHybridSearch.enabledReturning(
            java.util.List.of( "Alpha", "Beta" ) );

        final DefaultContextRetrievalService svc = new DefaultContextRetrievalService(
            com.wikantik.knowledge.testfakes.FakeEngine.create(),
            sm, hybrid, null, chunkIndex, chunkRepo, null, null, pm, null, "https://wiki.example" );

        final var result = svc.retrieve( new com.wikantik.api.knowledge.ContextQuery(
            "alpha query", 5, 2, null ) );

        final var alphaPage = result.pages().stream()
            .filter( p -> "Alpha".equals( p.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, alphaPage.contributingChunks().size() );
        assertEquals( java.util.List.of( "Alpha", "Intro" ),
            alphaPage.contributingChunks().get( 0 ).headingPath() );
        assertEquals( "first chunk of Alpha",
            alphaPage.contributingChunks().get( 0 ).text() );
        assertEquals( 0.9, alphaPage.contributingChunks().get( 0 ).chunkScore(), 0.0001 );
    }

    @Test
    void fromEngine_returnsNullWhenPageManagerMissing() {
        final Engine engine = mock( Engine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( null );
        assertNull( DefaultContextRetrievalService.fromEngine( engine ) );
    }

    @Test
    void fromEngine_returnsNullWhenSearchManagerMissing() {
        final Engine engine = mock( Engine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( mock( PageManager.class ) );
        when( engine.getManager( SearchManager.class ) ).thenReturn( null );
        assertNull( DefaultContextRetrievalService.fromEngine( engine ) );
    }

    @Test
    void fromEngine_buildsServiceWhenCoreManagersPresent() {
        final Engine engine = mock( Engine.class );
        final PageManager pm = mock( PageManager.class );
        final SearchManager sm = mock( SearchManager.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        when( engine.getManager( SearchManager.class ) ).thenReturn( sm );
        when( engine.getBaseURL() ).thenReturn( "https://wiki.example" );
        // optional managers left null — fromEngine tolerates that
        assertNotNull( DefaultContextRetrievalService.fromEngine( engine ) );
    }
}

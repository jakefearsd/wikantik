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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.Audience;
import com.wikantik.api.structure.Confidence;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.Verification;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultForAgentProjectionServiceTest {

    private StructuralIndexService idx;
    private PageManager pm;
    private CachingManager cache;
    private DefaultForAgentProjectionService svc;

    @BeforeEach
    void setUp() {
        idx = mock( StructuralIndexService.class );
        pm = mock( PageManager.class );
        cache = mock( CachingManager.class );
        when( cache.enabled( anyString() ) ).thenReturn( false );
        svc = new DefaultForAgentProjectionService( idx, pm, cache, new ForAgentMetrics() );
    }

    @Test
    void unknown_canonical_id_returns_empty() {
        when( idx.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        assertTrue( svc.project( "missing" ).isEmpty() );
    }

    @Test
    void blank_or_null_id_returns_empty() {
        assertTrue( svc.project( null ).isEmpty() );
        assertTrue( svc.project( "" ).isEmpty() );
        assertTrue( svc.project( "   " ).isEmpty() );
        verifyNoInteractions( pm );
    }

    @Test
    void happy_path_assembles_full_projection() {
        final PageDescriptor d = new PageDescriptor(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", PageType.ARTICLE,
                "wikantik-development", List.of( "retrieval" ),
                "Operator reference for hybrid retrieval.",
                Instant.parse( "2026-04-22T11:10:00Z" ) );
        when( idx.getByCanonicalId( "01ABC" ) ).thenReturn( Optional.of( d ) );
        when( idx.verificationOf( "01ABC" ) ).thenReturn( Optional.of(
                new Verification( Instant.parse( "2026-04-20T00:00:00Z" ), "jakefear",
                        Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS ) ) );
        when( idx.outgoingRelations( eq( "01ABC" ), any() ) ).thenReturn( List.of(
                new RelationEdge( "01ABC", "HybridRetrieval", "01XYZ", "InfoRetrieval",
                        "Information Retrieval", RelationType.EXAMPLE_OF, 1 ) ) );
        when( idx.incomingRelations( eq( "01ABC" ), any() ) ).thenReturn( List.of() );

        when( pm.getPureText( "HybridRetrieval", -1 ) ).thenReturn(
                "---\n" +
                "title: Hybrid Retrieval\n" +
                "summary: Operator reference for hybrid retrieval.\n" +
                "tags: [retrieval]\n" +
                "key_facts:\n" +
                "  - Retrieval fuses BM25 and dense embeddings via RRF.\n" +
                "---\n\n" +
                "## Wiring\n\n" +
                "Some prose.\n\n" +
                "## Failure modes\n" );
        final Page v1 = versionMock( 1, Instant.parse( "2026-04-22T11:10:00Z" ), "jakefear", "init" );
        when( pm.getVersionHistory( "HybridRetrieval" ) ).thenReturn( List.of( v1 ) );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();
        assertEquals( "01ABC", p.canonicalId() );
        assertEquals( "HybridRetrieval", p.slug() );
        assertEquals( "Hybrid Retrieval", p.title() );
        assertEquals( "article", p.type() );
        assertEquals( Confidence.AUTHORITATIVE, p.confidence() );
        assertEquals( "Operator reference for hybrid retrieval.", p.summary() );
        assertEquals( 1, p.keyFacts().size() );
        assertEquals( 2, p.headingsOutline().size() );
        assertEquals( 1, p.outgoingRelations().size() );
        assertEquals( 1, p.recentChanges().size() );
        assertFalse( p.mcpToolHints().isEmpty() );
        assertNull( p.runbook(), "runbook must be null until Phase 3" );
        assertFalse( p.degraded(), "happy path must not be degraded" );
        assertTrue( p.missingFields().isEmpty() );
        assertEquals( "/api/pages/HybridRetrieval",       p.fullBodyUrl() );
        assertEquals( "/wiki/HybridRetrieval?format=md",  p.rawMarkdownUrl() );
    }

    @Test
    void extractor_failure_marks_field_missing_but_keeps_others() {
        final PageDescriptor d = new PageDescriptor(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", PageType.ARTICLE,
                "wikantik-development", List.of(), null, Instant.now() );
        when( idx.getByCanonicalId( "01ABC" ) ).thenReturn( Optional.of( d ) );
        when( idx.verificationOf( "01ABC" ) ).thenReturn( Optional.empty() );
        when( idx.outgoingRelations( anyString(), any() ) ).thenReturn( List.of() );
        when( idx.incomingRelations( anyString(), any() ) ).thenReturn( List.of() );
        when( pm.getPureText( "HybridRetrieval", -1 ) ).thenThrow( new RuntimeException( "boom" ) );
        when( pm.getVersionHistory( "HybridRetrieval" ) ).thenReturn( List.of() );

        final ForAgentProjection p = svc.project( "01ABC" ).orElseThrow();

        assertTrue( p.degraded(), "must mark response degraded when an extractor throws" );
        assertTrue( p.missingFields().contains( "body" ),
                "missingFields must record the body extractor failure" );
        // Other fields (id, slug, title, confidence default) must still populate.
        assertEquals( "01ABC", p.canonicalId() );
        assertEquals( Confidence.PROVISIONAL, p.confidence(),
                "missing verification falls back to unverified()" );
    }

    @Test
    void uses_cache_when_enabled() throws Exception {
        when( cache.enabled( CachingManager.CACHE_FOR_AGENT ) ).thenReturn( true );
        final ForAgentProjection precomputed = miniProjection( "01ABC" );
        when( cache.get( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() ) ).thenReturn( precomputed );
        final PageDescriptor d = new PageDescriptor(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", PageType.ARTICLE,
                null, List.of(), null, Instant.parse( "2026-04-22T11:10:00Z" ) );
        when( idx.getByCanonicalId( "01ABC" ) ).thenReturn( Optional.of( d ) );

        final ForAgentProjection out = svc.project( "01ABC" ).orElseThrow();
        assertSame( precomputed, out, "cache hit must short-circuit extractor work" );
        verify( pm, never() ).getPureText( anyString(), anyInt() );
        verify( pm, never() ).getVersionHistory( anyString() );
    }

    private static Page versionMock( final int v, final Instant when, final String author, final String note ) {
        final Page p = mock( Page.class );
        when( p.getVersion() ).thenReturn( v );
        when( p.getLastModified() ).thenReturn( Date.from( when ) );
        when( p.getAuthor() ).thenReturn( author );
        when( p.getAttribute( Page.CHANGENOTE ) ).thenReturn( note );
        return p;
    }

    private static ForAgentProjection miniProjection( final String id ) {
        return new ForAgentProjection(
                id, "Slug", "Title", "article", null,
                Audience.HUMANS_AND_AGENTS, Confidence.PROVISIONAL,
                null, null, null,
                "summary",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null,
                "/api/pages/Slug", "/wiki/Slug?format=md",
                false, List.of() );
    }
}

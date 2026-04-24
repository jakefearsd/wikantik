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

import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.knowledge.testfakes.FakeDeps;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.knowledge.testfakes.FakeSearchManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

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
}

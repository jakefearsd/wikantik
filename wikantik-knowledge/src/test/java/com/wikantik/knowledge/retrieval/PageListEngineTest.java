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
package com.wikantik.knowledge.retrieval;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.search.FrontmatterMetadataCache;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageListEngineTest {

    /** Builds a minimal RetrievedPage the way DefaultContextRetrievalService's listPages callback does. */
    private static RetrievedPage toRetrievedPage( final Page page, final Map< String, Object > meta ) {
        return new RetrievedPage(
            page.getName(), page.getName(), 0.0,
            String.valueOf( meta.getOrDefault( "summary", "" ) ),
            meta.get( "cluster" ) == null ? null : meta.get( "cluster" ).toString(),
            PageListEngine.stringList( meta.get( "tags" ) ),
            List.of(), List.of(), page.getAuthor(), page.getLastModified(), false );
    }

    @Test
    void listPages_filtersByClusterAndReturnsMatchedPages() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\ncluster: index-funds\n---\nbody", "alice", new Date() );
        pm.addPage( "Beta", "---\ncluster: other\n---\nbody", "bob", new Date() );

        final PageListEngine engine = new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage );
        final PageList result = engine.listPages(
            new PageListFilter( "index-funds", null, null, null, null, null, 50, 0 ) );

        assertEquals( 1, result.totalMatched() );
        assertEquals( "Alpha", result.pages().get( 0 ).name() );
    }

    @Test
    void listPages_nullFilter_defaultsToUnfilteredAndPaginates() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "One", "body", "alice", new Date() );
        pm.addPage( "Two", "body", "alice", new Date() );

        final PageListEngine engine = new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage );
        final PageList result = engine.listPages( null );

        assertEquals( 2, result.totalMatched() );
        assertEquals( 2, result.pages().size() );
    }

    @Test
    void matchesFilter_requiresAllRequestedTagsPresent() {
        final PageListEngine engine = new PageListEngine( new FakePageManager(), null, PageListEngineTest::toRetrievedPage );
        final Page page = mock( Page.class );
        when( page.getAuthor() ).thenReturn( "alice" );
        final Map< String, Object > meta = Map.of( "tags", List.of( "retrieval", "hybrid" ) );

        final PageListFilter needsBoth = new PageListFilter(
            null, List.of( "retrieval", "hybrid" ), null, null, null, null, 50, 0 );
        final PageListFilter needsMissing = new PageListFilter(
            null, List.of( "retrieval", "missing" ), null, null, null, null, 50, 0 );

        assertTrue( engine.matchesFilter( page, meta, needsBoth ) );
        assertFalse( engine.matchesFilter( page, meta, needsMissing ) );
    }

    @Test
    void metadataFor_withoutCache_parsesFrontmatterFromPureText() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Alpha", "---\ncluster: index-funds\nsummary: hi\n---\nbody", "alice", new Date() );
        final PageListEngine engine = new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage );

        final Map< String, Object > meta = engine.metadataFor( pm.getPage( "Alpha" ) );

        assertEquals( "index-funds", meta.get( "cluster" ) );
        assertEquals( "hi", meta.get( "summary" ) );
    }

    @Test
    void metadataFor_withCache_delegatesRatherThanReparsing() {
        final FakePageManager pm = new FakePageManager();
        final Date lastMod = new Date();
        pm.addPage( "Alpha", "irrelevant if cache is used", "alice", lastMod );
        final FrontmatterMetadataCache cache = mock( FrontmatterMetadataCache.class );
        final Map< String, Object > cached = Map.of( "cluster", "from-cache" );
        when( cache.get( "Alpha", lastMod ) ).thenReturn( cached );

        final PageListEngine engine = new PageListEngine( pm, cache, PageListEngineTest::toRetrievedPage );
        final Map< String, Object > meta = engine.metadataFor( pm.getPage( "Alpha" ) );

        assertEquals( cached, meta );
        verify( cache ).get( "Alpha", lastMod );
    }

    @Test
    void stringList_toleratesNonListValuesByReturningEmpty() {
        assertEquals( List.of(), PageListEngine.stringList( "not-a-list" ) );
        assertEquals( List.of( "a", "b" ), PageListEngine.stringList( List.of( "a", "b" ) ) );
    }

    @Test
    void stringOrNull_passesThroughOrNullifies() {
        assertNull( PageListEngine.stringOrNull( null ) );
        assertEquals( "42", PageListEngine.stringOrNull( 42 ) );
    }

    @Test
    void listPages_providerExceptionDuringGetAllPagesReturnsEmptyList() throws Exception {
        final PageManager pm = mock( PageManager.class );
        when( pm.getAllPages() ).thenThrow( new ProviderException( "fs unavailable" ) );

        final PageListEngine engine = new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage );
        final PageList result = engine.listPages( PageListFilter.unfiltered() );

        assertEquals( 0, result.totalMatched() );
        assertTrue( result.pages().isEmpty() );
    }

    @Test
    void matchesFilter_modifiedAfterExcludesOlderPages() {
        final PageListEngine engine = new PageListEngine( new FakePageManager(), null, PageListEngineTest::toRetrievedPage );
        final Page page = mock( Page.class );
        when( page.getAuthor() ).thenReturn( "alice" );
        when( page.getLastModified() ).thenReturn( Date.from( Instant.parse( "2020-01-01T00:00:00Z" ) ) );

        final PageListFilter after2025 = new PageListFilter(
            null, null, null, null, Instant.parse( "2025-01-01T00:00:00Z" ), null, 50, 0 );
        final PageListFilter after2019 = new PageListFilter(
            null, null, null, null, Instant.parse( "2019-01-01T00:00:00Z" ), null, 50, 0 );

        assertFalse( engine.matchesFilter( page, Map.of(), after2025 ), "page predates the cutoff" );
        assertTrue( engine.matchesFilter( page, Map.of(), after2019 ), "page postdates the cutoff" );
    }

    @Test
    void matchesFilter_modifiedBeforeExcludesNewerPages() {
        final PageListEngine engine = new PageListEngine( new FakePageManager(), null, PageListEngineTest::toRetrievedPage );
        final Page page = mock( Page.class );
        when( page.getAuthor() ).thenReturn( "alice" );
        when( page.getLastModified() ).thenReturn( Date.from( Instant.parse( "2025-06-01T00:00:00Z" ) ) );

        final PageListFilter before2020 = new PageListFilter(
            null, null, null, null, null, Instant.parse( "2020-01-01T00:00:00Z" ), 50, 0 );
        final PageListFilter before2030 = new PageListFilter(
            null, null, null, null, null, Instant.parse( "2030-01-01T00:00:00Z" ), 50, 0 );

        assertFalse( engine.matchesFilter( page, Map.of(), before2020 ), "page postdates the cutoff" );
        assertTrue( engine.matchesFilter( page, Map.of(), before2030 ), "page predates the cutoff" );
    }
}

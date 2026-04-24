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
package com.wikantik.knowledge.structure;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultStructuralIndexServiceTest {

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;
    private DefaultStructuralIndexService svc;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
        svc = new DefaultStructuralIndexService( pageManager, dao );
    }

    private Page fakePage( final String name, final String frontmatter, final String body ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        when( pageManager.getPureText( p ) ).thenReturn( "---\n" + frontmatter + "\n---\n" + body );
        return p;
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_indexes_every_page_returned_by_pageManager() throws Exception {
        final Page a = fakePage( "HybridRetrieval",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN\n" +
                "title: Hybrid Retrieval\n" +
                "type: article\n" +
                "cluster: wikantik-development\n" +
                "tags: [retrieval, bm25]\n" +
                "summary: Hybrid retrieval reference.", "body" );
        final Page b = fakePage( "WikantikDevelopment",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A0\n" +
                "title: Wikantik Development\n" +
                "type: hub\n" +
                "cluster: wikantik-development\n" +
                "tags: [wikantik]\n" +
                "summary: Dev hub.", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var clusters = svc.listClusters();
        assertEquals( 1, clusters.size() );
        assertEquals( "wikantik-development", clusters.get( 0 ).name() );
        assertEquals( 2, clusters.get( 0 ).articleCount() );

        assertTrue( svc.getByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isPresent() );
        verify( dao, times( 2 ) ).upsert( any(), any(), any(), any(), any() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_synthesises_canonical_id_for_pages_missing_frontmatter_field() throws Exception {
        final Page a = fakePage( "RawPage", "title: Raw Page\ntype: article", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        svc.rebuild();

        final var health = svc.health();
        assertEquals( 1, health.unclaimedCanonicalIds() );
        assertEquals( 1, svc.snapshot().pageCount() );
        // Synthesised IDs live in memory only — they MUST NOT be written to the DB,
        // otherwise every restart would churn new rows into page_canonical_ids.
        verifyNoInteractions( dao );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void listPagesByFilter_round_trip_after_rebuild() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: A\ntype: article\ncluster: x\ntags: [t1]", "" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: B\ntype: hub\ncluster: x\ntags: [t1]", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var articles = svc.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), null, null, null, 10, null ) );
        assertEquals( 1, articles.size() );
        assertEquals( "A", articles.get( 0 ).slug() );
    }
}

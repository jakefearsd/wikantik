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
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduces D6 — was: get_page_by_id returns "no page for canonical_id" for fresh saves.
 *
 * <p>Production flow: {@code PUT /api/pages/{name}} → {@code StructuralSpinePageFilter.preSave}
 * injects a fresh {@code canonical_id} ULID into frontmatter and rewrites the body that gets
 * written to disk. POST_SAVE fires → {@link StructuralIndexEventListener#actionPerformed}
 * → {@link DefaultStructuralIndexService#onPageSaved} → {@link DefaultStructuralIndexService#rebuild}.
 *
 * <p>Bug: at rebuild time, {@code pageManager.getPureText(p)} can return the
 * pre-rewrite content (no canonical_id yet) because of caching/race timing in the
 * provider stack. {@code rebuild()} then treats the page as "unauthored", synthesises
 * a fresh in-memory ULID (different from the one the filter injected), and skips the
 * DAO upsert. The page is now invisible to {@code get_page_by_id} via the canonical_id
 * the user actually sees in their saved frontmatter.
 *
 * <p>Fix: {@link DefaultStructuralIndexService#onPageSaved} must re-read the page's
 * frontmatter directly and persist the canonical_id (DB + in-memory) for the just-saved
 * page, rather than relying on the next full rebuild's view of the cache.
 */
@SuppressWarnings( { "unchecked", "rawtypes" } )
class DefaultStructuralIndexServiceSavedPageRoundTripTest {

    private static final String INJECTED_ID = "01HZZZZZZZZZZZZZZZZZZZZZZZ";

    /**
     * Simulates the production race: at rebuild time the provider returns the pre-filter
     * (no-canonical_id) content, but the freshly-saved page on disk DOES contain the id
     * the filter injected. The fix path re-reads the just-saved page in {@code onPageSaved}
     * and persists the canonical_id explicitly.
     */
    @Test
    void onPageSaved_persists_canonical_id_even_when_rebuild_reads_stale_cache() throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "BrandNewPage" );
        when( page.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );

        // The cache is racy in production: the bulk rebuild's getAllPages/getPureText
        // returns the OLD content (no canonical_id), but a direct re-read of the named
        // page hits the freshly-saved content. We model this by giving getPage a Page
        // whose getPureText (via the named lookup) yields fresh content, while the
        // collection returned by getAllPages yields stale content.
        final String stale = "---\ntitle: BrandNewPage\ntype: article\n---\nbody";
        final String fresh = "---\ncanonical_id: " + INJECTED_ID
                + "\ntitle: BrandNewPage\ntype: article\n---\nbody";

        // Two distinct Page instances so we can return different getPureText values
        // for the rebuild path (stale) vs. the direct re-read path (fresh).
        final Page stalePage = mock( Page.class );
        when( stalePage.getName() ).thenReturn( "BrandNewPage" );
        when( stalePage.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );

        when( pageManager.getPage( "BrandNewPage" ) ).thenReturn( page );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( stalePage ) );
        when( pageManager.getPureText( stalePage ) ).thenReturn( stale );
        when( pageManager.getPureText( page ) ).thenReturn( fresh );

        final DefaultStructuralIndexService svc =
                new DefaultStructuralIndexService( pageManager, dao );

        // Trigger the POST_SAVE codepath: under the bug this calls rebuild() which reads
        // the stale content and synthesises an in-memory id; the DAO is never called for
        // INJECTED_ID and the projection cannot resolve it.
        svc.onPageSaved( "BrandNewPage" );

        // GREEN expectations after the fix:
        // 1. The DAO has the injected canonical_id persisted.
        verify( dao, times( 1 ) ).upsert( eq( INJECTED_ID ), eq( "BrandNewPage" ), any(), any(), any() );
        // 2. The in-memory projection resolves the injected id.
        assertTrue( svc.getByCanonicalId( INJECTED_ID ).isPresent(),
                "freshly-saved page must be findable by its injected canonical_id" );
    }
}

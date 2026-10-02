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
package com.wikantik.render;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The final-HTML cache ({@link CachingManager#CACHE_HTML}) was declared, evicted and consulted, but the Ehcache
 * manager never registered it, so it was inert. It is now live for page views; these tests pin what it may hold:
 * only the final, post-filtered HTML of a page view, invalidated when a referenced page is created.
 */
class RenderingManagerHtmlCacheTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void theHtmlCacheIsLiveByDefault() {
        assertTrue( engine.getManager( CachingManager.class ).enabled( CachingManager.CACHE_HTML ) );
    }

    @Test
    void aRepeatPageViewIsServedFromTheCacheWithoutRunningTheFilters() throws Exception {
        engine.saveText( "HcPlain", "just text\n" );
        final AtomicInteger postTranslates = countingPostFilter( "" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        final String first = rm.textToHTML( view( "HcPlain" ), "just text\n" );
        final String second = rm.textToHTML( view( "HcPlain" ), "just text\n" );

        assertEquals( first, second );
        assertEquals( 1, postTranslates.get(), "the second view is a cache hit: no parse, render or filter pass" );
    }

    /**
     * {@code getHTML(Context, String)} renders WITHOUT the post-translate filters. It used to store that HTML under the
     * same key {@code textToHTML} reads, so a page view could be served HTML the filters never saw (e.g. raw
     * {@code cite://} hrefs).
     */
    @Test
    void htmlRenderedWithoutPostFiltersIsNeverServedToAPageView() throws Exception {
        countingPostFilter( "<!--post-filtered-->" );
        engine.saveText( "HcFiltered", "body text\n" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        rm.getHTML( view( "HcFiltered" ), "body text\n" );
        final String viewed = rm.textToHTML( view( "HcFiltered" ), "body text\n" );

        assertTrue( viewed.contains( "<!--post-filtered-->" ), "a page view always carries the post-filter output: " + viewed );
    }

    @Test
    void creatingALinkedPageEvictsTheReferrersCachedHtml() throws Exception {
        final String text = "see [the target](HcLateTarget)\n";
        engine.saveText( "HcReferrer", text );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final String before = rm.textToHTML( view( "HcReferrer" ), text );
        assertTrue( before.contains( "createpage" ), before );
        assertNotNull( engine.getManager( CachingManager.class ).get( CachingManager.CACHE_HTML,
                "HcReferrer::" + engine.getManager( PageManager.class ).getPage( "HcReferrer" ).getVersion() + "::null",
                () -> null ), "the first view was cached (otherwise this test proves nothing)" );

        engine.saveText( "HcLateTarget", "now it exists\n" );

        final String after = rm.textToHTML( view( "HcReferrer" ), text );
        assertFalse( after.contains( "createpage" ), "the referrer must not keep serving a create-page link: " + after );
    }

    private Context view( final String page ) {
        final Context ctx = Wiki.context().create( engine, engine.getManager( PageManager.class ).getPage( page ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
    }

    private AtomicInteger countingPostFilter( final String marker ) {
        final AtomicInteger calls = new AtomicInteger();
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String postTranslate( final Context context, final String htmlContent ) {
                calls.incrementAndGet();
                return htmlContent + marker;
            }
        }, -1000 );
        return calls;
    }
}

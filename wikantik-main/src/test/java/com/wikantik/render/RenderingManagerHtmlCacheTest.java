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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.variables.VariableManager;
import jakarta.servlet.http.HttpServletRequest;
import org.mockito.Mockito;
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

import java.util.Locale;
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

    // ---- fix round 1 -------------------------------------------------------------------------------------------

    /** {@code ?wikantik.runFilters=false} used to reach the run-filters switch and cache the unfiltered HTML. */
    @Test
    void aRequestParameterCannotSwitchThePostFiltersOff() throws Exception {
        engine.saveText( "HcParam", "body text\n" );
        countingPostFilter( "<!--post-filtered-->" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "false" ).when( req ).getParameter( VariableManager.VAR_RUNFILTERS );

        final String attacked = rm.textToHTML( view( "HcParam", req ), "body text\n" );
        final String normal = rm.textToHTML( view( "HcParam" ), "body text\n" );

        assertTrue( attacked.contains( "<!--post-filtered-->" ), "the request cannot switch filters off: " + attacked );
        assertTrue( normal.contains( "<!--post-filtered-->" ), "the next viewer gets filtered HTML: " + normal );
    }

    @Test
    void aRenderWithFiltersSwitchedOffIsNeverCached() throws Exception {
        engine.saveText( "HcNoFilters", "body text\n" );
        countingPostFilter( "<!--post-filtered-->" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final Context internal = view( "HcNoFilters" );
        internal.setVariable( VariableManager.VAR_RUNFILTERS, "false" );

        final String unfiltered = rm.textToHTML( internal, "body text\n" );
        final String normal = rm.textToHTML( view( "HcNoFilters" ), "body text\n" );

        assertFalse( unfiltered.contains( "<!--post-filtered-->" ), "the internal switch still works" );
        assertTrue( normal.contains( "<!--post-filtered-->" ), "a filter-less render is never served to a page view" );
    }

    @Test
    void deletingALinkedPageEvictsTheReferrer() throws Exception {
        engine.saveText( "HcDelTarget", "exists\n" );
        final String text = "see [it](HcDelTarget)\n";
        engine.saveText( "HcDelReferrer", text );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        assertFalse( rm.textToHTML( view( "HcDelReferrer" ), text ).contains( "createpage" ) );

        engine.getManager( PageManager.class ).deletePage( "HcDelTarget" );

        assertTrue( rm.textToHTML( view( "HcDelReferrer" ), text ).contains( "createpage" ),
                "the deleted target must be a create link, not a cached live link" );
    }

    @Test
    void renamingEvictsReferrersOfTheOldAndTheNewName() throws Exception {
        engine.saveText( "HcRnOld", "exists\n" );
        final String toOld = "see [it](HcRnOld)\n";
        final String toNew = "see [it](HcRnNew)\n";
        engine.saveText( "HcRnToOld", toOld );
        engine.saveText( "HcRnToNew", toNew );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        assertFalse( rm.textToHTML( view( "HcRnToOld" ), toOld ).contains( "createpage" ) );
        assertTrue( rm.textToHTML( view( "HcRnToNew" ), toNew ).contains( "createpage" ) );

        final Context ctx = Wiki.context().create( engine, engine.getManager( PageManager.class ).getPage( "HcRnOld" ) );
        engine.getManager( PageRenamer.class ).renamePage( ctx, "HcRnOld", "HcRnNew", false );

        assertTrue( rm.textToHTML( view( "HcRnToOld" ), toOld ).contains( "createpage" ), "old name is gone" );
        assertFalse( rm.textToHTML( view( "HcRnToNew" ), toNew ).contains( "createpage" ), "new name now exists" );
    }

    /** Own-page attachment links resolve against the real attachment at render time (an "Owner/file" link does not). */
    @Test
    void uploadingAnAttachmentEvictsItsPage() throws Exception {
        final String text = "get [the file](doc.pdf)\n";
        engine.saveText( "HcAttHost", text );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final String before = rm.textToHTML( view( "HcAttHost" ), text );
        assertFalse( before.contains( "class=\"attachment\"" ), before );

        attach( "HcAttHost", "doc.pdf" );

        final String after = rm.textToHTML( view( "HcAttHost" ), text );
        assertTrue( after.contains( "class=\"attachment\"" ), "the new attachment must be linked: " + after );
    }

    @Test
    void deletingAnAttachmentEvictsItsPage() throws Exception {
        final String text = "get [the file](doc.pdf)\n";
        engine.saveText( "HcAttDelHost", text );
        attach( "HcAttDelHost", "doc.pdf" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        assertTrue( rm.textToHTML( view( "HcAttDelHost" ), text ).contains( "class=\"attachment\"" ) );

        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.deleteAttachment( am.getAttachmentInfo( "HcAttDelHost/doc.pdf" ) );

        final String after = rm.textToHTML( view( "HcAttDelHost" ), text );
        assertFalse( after.contains( "class=\"attachment\"" ), "a deleted attachment must not stay linked: " + after );
    }

    /** The create-page tooltip is localised from the viewer's locale; one viewer's language must not leak to another. */
    @Test
    void theCachedHtmlIsNeverServedInAnotherViewersLanguage() throws Exception {
        final String text = "see [missing](HcNoSuchLocalePage)\n";
        engine.saveText( "HcLocale", text );
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        final String spanish = rm.textToHTML( view( "HcLocale", requestIn( Locale.of( "es" ) ) ), text );
        final String english = rm.textToHTML( view( "HcLocale", requestIn( Locale.ENGLISH ) ), text );

        assertTrue( spanish.contains( "Crear" ), spanish );
        assertTrue( english.contains( "Create &#34;" ) || english.contains( "Create \"" ), english );
    }

    /** The cache entry is validated against the RAW page data; it used to be stored under the pre-filtered data's hash. */
    @Test
    void aPreTranslateFilterThatRewritesTheTextStillGetsCacheHits() throws Exception {
        engine.saveText( "HcPreFiltered", "raw text\n" );
        final AtomicInteger postTranslates = new AtomicInteger();
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preTranslate( final Context context, final String content ) {
                return content + "\n\nappended by a pre-translate filter\n";
            }

            @Override
            public String postTranslate( final Context context, final String htmlContent ) {
                postTranslates.incrementAndGet();
                return htmlContent;
            }
        }, -1000 );
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        rm.textToHTML( view( "HcPreFiltered" ), "raw text\n" );
        rm.textToHTML( view( "HcPreFiltered" ), "raw text\n" );

        assertEquals( 1, postTranslates.get(), "the second view is a cache hit" );
    }

    private static HttpServletRequest requestIn( final Locale locale ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( locale ).when( req ).getLocale();
        return req;
    }

    private void attach( final String owner, final String file ) throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, owner, file );
        att.setAuthor( "FirstPost" );
        engine.getManager( AttachmentManager.class ).storeAttachment( att, engine.makeAttachmentFile() );
    }

    private Context view( final String page, final HttpServletRequest request ) {
        final Page p = engine.getManager( PageManager.class ).getPage( page );
        final Context ctx = Wiki.context().create( engine, request, p );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
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

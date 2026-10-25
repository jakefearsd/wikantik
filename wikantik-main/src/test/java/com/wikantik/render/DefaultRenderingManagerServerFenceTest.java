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
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.ArticleSummary;
import com.wikantik.content.DefaultRecentArticlesManager;
import com.wikantik.content.RecentArticlesQuery;
import com.wikantik.markdown.extensions.fences.ServerDrawing;
import com.wikantik.markdown.extensions.fences.ServerFenceDrawer;
import com.wikantik.markdown.extensions.fences.ServerFenceResolver;
import com.wikantik.parser.MarkupParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-drawn fences are resolved once, at the outermost {@code textToHTML}: the HTML caches hold the placeholder,
 * nested renders (InsertPage, embeds) leave it for the outer render, and the drawing is never sanitized away.
 */
class DefaultRenderingManagerServerFenceTest {

    private static final String LIGHT = "<svg id=\"l\">L</svg>";
    private static final String DARK = "<svg id=\"d\">D</svg>";
    private static final String DIAGRAM = "```plantuml\nA -> B\n```\n";

    /** Counting fake drawer for {@code plantuml}. */
    private static final class CountingDrawer implements ServerFenceDrawer {
        final AtomicInteger calls = new AtomicInteger();
        volatile Function< Integer, ServerDrawing > outcome = n -> ServerDrawing.drawn( LIGHT, DARK );

        @Override
        public String language() {
            return "plantuml";
        }

        @Override
        public ServerDrawing draw( final String source, final long budgetMs ) {
            return outcome.apply( calls.incrementAndGet() );
        }

        @Override
        public long defaultBudgetMs() {
            return 1_000L;
        }
    }

    private final AtomicInteger parses = new AtomicInteger();
    private final CountingDrawer drawer = new CountingDrawer();
    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    /** An engine whose rendering manager counts parses and resolves through {@link #drawer}. */
    private void bootWithFakeDrawer() throws Exception {
        engine = TestEngine.build();
        final DefaultRenderingManager counting = new DefaultRenderingManager() {
            @Override
            public MarkupParser getParser( final Context context, final String pagedata ) {
                parses.incrementAndGet();
                return super.getParser( context, pagedata );
            }
        };
        counting.initialize( engine, engine.getWikiProperties() );
        counting.serverFences = new ServerFenceResolver( Map.of( "plantuml", drawer ), 20, 10_000L );
        engine.setManager( RenderingManager.class, counting );
    }

    private Context viewContext( final String name ) {
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( name ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
    }

    private String view( final String name ) {
        final String text = engine.getManager( PageManager.class ).getPureText( name, -1 );
        return engine.getManager( RenderingManager.class ).textToHTML( viewContext( name ), text );
    }

    private String cachedHtml( final String name ) {
        final int version = engine.getManager( PageManager.class ).getPage( name ).getVersion();
        final Object entry = engine.getManager( CachingManager.class )
                .get( CachingManager.CACHE_HTML, name + "::" + version + "::null", () -> null );
        assertNotNull( entry, "the page view was cached (otherwise this test proves nothing)" );
        return ( ( DefaultRenderingManager.HtmlCacheEntry ) entry ).html();
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for ( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + needle.length() ) ) {
            n++;
        }
        return n;
    }

    @Test
    void pageViewResolvesAndCacheHoldsPlaceholder() throws Exception {
        bootWithFakeDrawer();
        engine.saveText( "SfPlain", "Intro.\n\n" + DIAGRAM );

        final String html = view( "SfPlain" );

        assertTrue( html.contains( "data-fence-state=\"drawn\"" ), html );
        assertTrue( html.contains( LIGHT ) && html.contains( DARK ), html );
        final String cached = cachedHtml( "SfPlain" );
        assertTrue( cached.contains( "data-fence-drawing=\"" ), cached );
        assertFalse( cached.contains( "<svg" ), cached );
        assertFalse( cached.contains( "data-fence-state" ), cached );
    }

    @Test
    void secondViewDrawsAgainFromPlaceholder() throws Exception {
        bootWithFakeDrawer();
        engine.saveText( "SfTwice", DIAGRAM );
        view( "SfTwice" );
        parses.set( 0 );

        final String second = view( "SfTwice" );

        assertEquals( 0, parses.get(), "the second view is an HTML cache hit" );
        assertTrue( second.contains( LIGHT ), second );
        assertEquals( 2, drawer.calls.get(), "the cached placeholder is resolved again on every view" );
    }

    @Test
    void transientFailureNeverCached() throws Exception {
        bootWithFakeDrawer();
        drawer.outcome = n -> n == 1 ? ServerDrawing.refused( "busy", null, 0, true ) : ServerDrawing.drawn( LIGHT, DARK );
        engine.saveText( "SfBusy", DIAGRAM );

        final String first = view( "SfBusy" );
        final String second = view( "SfBusy" );

        assertTrue( first.contains( "data-fence-error=\"busy\"" ), first );
        assertTrue( second.contains( "data-fence-state=\"drawn\"" ), second );
        assertFalse( cachedHtml( "SfBusy" ).contains( "data-fence-error" ), "the busy outcome never reached the cache" );
    }

    @Test
    void nestedInsertPageDoesNotResolveTwice() throws Exception {
        bootWithFakeDrawer();
        engine.saveText( "SfInner", DIAGRAM );
        engine.saveText( "SfOuter", "Outer.\n\n[{InsertPage page='SfInner'}]\n" );
        assertEquals( 0, drawer.calls.get(), "the save's metadata parse runs InsertPage but must not draw" );

        final String html = view( "SfOuter" );

        assertEquals( 1, drawer.calls.get(), html );
        assertEquals( 1, count( html, "data-fence-state=\"drawn\"" ), html );
        assertTrue( html.contains( LIGHT ), "the drawing survives the outer sanitize: " + html );
        assertFalse( html.contains( "data-fence-drawing" ), html );
    }

    @Test
    void embedWithAllowHtmlDraws() throws Exception {
        bootWithFakeDrawer();
        assertTrue( Boolean.parseBoolean( engine.getWikiProperties().getProperty( MarkupParser.PROP_ALLOWHTML ) ) );
        engine.saveText( "SfBody", "Body text.\n\n" + DIAGRAM );
        engine.saveText( "SfEmbedHost", "Host.\n\n![[SfBody]]\n" );

        final String html = view( "SfEmbedHost" );

        assertTrue( html.contains( "data-embed=\"SfBody\"" ), html );
        assertEquals( 1, drawer.calls.get(), html );
        assertTrue( html.contains( "data-fence-state=\"drawn\"" ) && html.contains( LIGHT ), html );
    }

    @Test
    void recentArticleExcerptsNeverDraw() throws Exception {
        bootWithFakeDrawer();
        drawer.outcome = n -> ServerDrawing.drawn( "<svg><text>LIGHTSVGLABEL</text></svg>", "<svg><text>DARKSVGLABEL</text></svg>" );
        engine.saveText( "SfRecent", "Opening words of the article.\n\n" + DIAGRAM );
        final DefaultRecentArticlesManager recent = new DefaultRecentArticlesManager();
        recent.initialize( engine, engine.getWikiProperties() ); // binds the swapped-in rendering manager

        final List< ArticleSummary > articles = recent.getRecentArticles( viewContext( "SfRecent" ),
                new RecentArticlesQuery().count( 100 ).includeExcerpt( true ) );

        final ArticleSummary article = articles.stream().filter( a -> "SfRecent".equals( a.getName() ) ).findFirst()
                .orElseThrow( () -> new AssertionError( "SfRecent not listed: " + articles ) );
        assertNotNull( article.getExcerpt() );
        assertTrue( article.getExcerpt().contains( "Opening words" ), article.getExcerpt() );
        assertFalse( article.getExcerpt().contains( "SVGLABEL" ), article.getExcerpt() );
        assertEquals( 0, drawer.calls.get(), "an excerpt is plain text: its render must not draw diagrams" );
    }

    @Test
    void explicitResolveDelegatesToTheResolver() throws Exception {
        bootWithFakeDrawer();
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        engine.saveText( "SfExplicit", DIAGRAM );
        final Context ctx = viewContext( "SfExplicit" );
        final String placeholder = rm.renderEmbedBody( ctx, DIAGRAM );
        assertTrue( placeholder.contains( "data-fence-drawing=\"" ), "a nested-style render leaves the placeholder: " + placeholder );

        final String resolved = rm.resolveServerFences( placeholder );

        assertTrue( resolved.contains( LIGHT ), resolved );
        assertTrue( rm.serverFenceDrawer( "plantuml" ).isPresent() );
        assertTrue( rm.serverFenceDrawer( "nope" ).isEmpty() );
    }

    @Test
    void realEngineDrawsPlantUml() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "SfReal", "```plantuml\ntitle Real\nA -> B : hi\n```\n" );

        final String html = view( "SfReal" );

        assertTrue( html.contains( "data-fence-state=\"drawn\"" ), html );
        assertTrue( html.contains( "aria-label=\"Real\"" ), html );
        assertTrue( html.contains( "<svg" ), html );
    }

    @Test
    void killSwitchKeepsLinksAndRefuses() throws Exception {
        engine = TestEngine.build( TestEngine.with( "wikantik.fences.plantuml.enabled", "false" ) );
        engine.saveText( "SfTarget", "target\n" );
        engine.saveText( "SfKill", "```plantuml\nA -> B : [[/wiki/SfTarget]]\n```\n" );

        final String html = view( "SfKill" );

        assertTrue( html.contains( "data-fence-error=\"plantuml-disabled\"" ), html );
        assertFalse( html.contains( "<svg" ), html );
        assertTrue( engine.getManager( ReferenceManager.class ).findRefersTo( "SfKill" ).contains( "SfTarget" ),
                () -> "refs=" + engine.getManager( ReferenceManager.class ).findRefersTo( "SfKill" ) );
    }
}

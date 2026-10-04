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
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.cache.CachingManager;
import com.wikantik.parser.MarkupParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rendered body of an embedded page is reused across page views while it stays valid for every viewer. */
class EmbedBodyCacheTest {

    private final AtomicInteger parses = new AtomicInteger();
    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        final DefaultRenderingManager counting = new DefaultRenderingManager() {
            @Override
            public MarkupParser getParser( final Context context, final String pagedata ) {
                parses.incrementAndGet();
                return super.getParser( context, pagedata );
            }
        };
        counting.initialize( engine, engine.getWikiProperties() );
        engine.setManager( RenderingManager.class, counting );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void aViewerIndependentBodyIsReusedByTheNextView() throws Exception {
        engine.saveText( "CacheBody", "Plain **body**.\n" );
        engine.saveText( "CacheHost", "![[CacheBody]]\n" );
        view( "CacheHost" );
        parses.set( 0 );

        final String html = view( "CacheHost" );

        assertTrue( html.contains( "<strong>body</strong>" ), html );
        assertEquals( 0, parses.get(), "the host comes from the document cache, the body from the body cache" );
    }

    @Test
    void sectionEmbedsOfOnePageEachKeepTheirOwnBody() throws Exception {
        engine.saveText( "CacheBody", "## Alpha\n\nfirst section\n\n## Beta\n\nsecond section\n" );
        engine.saveText( "CacheHost", "![[CacheBody#Alpha]]\n\n![[CacheBody#Beta]]\n" );
        view( "CacheHost" );
        parses.set( 0 );

        final String html = view( "CacheHost" );

        final int first = html.indexOf( "first section" );
        final int second = html.indexOf( "second section" );
        assertTrue( first >= 0 && second > first, html );
        assertEquals( first, html.lastIndexOf( "first section" ), html );
        assertEquals( second, html.lastIndexOf( "second section" ), html );
        assertEquals( 0, parses.get(), "the host comes from the document cache, the body from the body cache" );
    }

    @Test
    void aViewerDependentBodyIsRenderedForEveryView() throws Exception {
        engine.saveText( "CacheBody", "Hello [{$username}].\n" );
        engine.saveText( "CacheHost", "![[CacheBody]]\n" );
        view( "CacheHost" );
        parses.set( 0 );

        view( "CacheHost" );

        assertEquals( 1, parses.get(), "the viewer-dependent body is parsed again" );
    }

    @Test
    void creatingAPageTheBodyLinksToRefreshesTheBody() throws Exception {
        // a classic link: an unresolved [[link]] keeps the body uncached until the title index is ready
        engine.saveText( "CacheBody", "See [the page](CacheNotYet).\n" );
        engine.saveText( "CacheHost", "![[CacheBody]]\n" );
        view( "CacheHost" );
        parses.set( 0 );
        assertTrue( view( "CacheHost" ).contains( "class=\"createpage\"" ) );
        assertEquals( 0, parses.get(), "the body is served from the cache before the linked page exists" );

        engine.saveText( "CacheNotYet", "Here now.\n" );

        final String html = view( "CacheHost" );
        assertFalse( html.contains( "createpage" ), html );
    }

    @Test
    void editingTheEmbeddedPageRefreshesTheBody() throws Exception {
        engine.saveText( "CacheBody", "old words\n" );
        engine.saveText( "CacheHost", "![[CacheBody]]\n" );
        view( "CacheHost" );

        engine.saveText( "CacheBody", "new words\n" );

        final String html = view( "CacheHost" );
        assertTrue( html.contains( "new words" ), html );
        assertFalse( html.contains( "old words" ), html );
    }

    @Test
    void aBodyRenderedForAnAdminIsNotShownToAGuestDeniedThePage() throws Exception {
        engine.saveText( "CacheSecret", "[{ALLOW view Admin}]\n\nclassified\n" );
        engine.saveText( "CacheHost", "![[CacheSecret]]\n" );
        assertTrue( view( adminContext( "CacheHost" ) ).contains( "classified" ) );

        final String guest = view( guestContext( "CacheHost" ) );

        assertFalse( guest.contains( "classified" ), guest );
        assertTrue( guest.contains( "wiki-embed-restricted" ), guest );
    }

    // ---- EmbedBodyCache.render directly ----------------------------------------------------------------------------

    private static final String SLOT_PAGE = "UnitPage::1::null";

    private Context unitContext() throws Exception {
        engine.saveText( "UnitPage", "unit\n" );
        return asView( Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( "UnitPage" ) ) );
    }

    private String unitRender( final Context context, final String markdown, final EmbedBodyCache.BodyRender render )
            throws Exception {
        return EmbedBodyCache.render( engine.getManager( CachingManager.class ), true, SLOT_PAGE, context, markdown, render );
    }

    @Test
    void anEvictionDuringARenderIsNotUndoneWhenThatRenderIsStored() throws Exception {
        final Context ctx = unitContext();
        final CachingManager caches = engine.getManager( CachingManager.class );
        unitRender( ctx, "first body", () -> "<p>first</p>" );
        unitRender( ctx, "second body", () -> {
            caches.remove( CachingManager.CACHE_HTML, SLOT_PAGE + EmbedBodyCache.SLOT_SUFFIX ); // the page is evicted mid-render
            return "<p>second</p>";
        } );
        final AtomicInteger renders = new AtomicInteger();

        unitRender( ctx, "first body", () -> {
            renders.incrementAndGet();
            return "<p>first</p>";
        } );

        assertEquals( 1, renders.get(), "a body evicted while another rendered must not come back" );
    }

    @Test
    void flagsTheHostRaisedStayRaisedAndTheBodyIsStillCached() throws Exception {
        final Context ctx = unitContext();
        ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
        ctx.setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
        unitRender( ctx, "plain", () -> "<p>plain</p>" );

        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_RENDER_UNCACHEABLE ) );
        final AtomicInteger renders = new AtomicInteger();
        unitRender( ctx, "plain", () -> {
            renders.incrementAndGet();
            return "<p>plain</p>";
        } );
        assertEquals( 0, renders.get(), "the body itself consulted no viewer, so it is reused" );
    }

    @Test
    void aFlagTheBodyRaisesReachesTheHostAndTheBodyIsNotCached() throws Exception {
        final Context ctx = unitContext();
        final AtomicInteger renders = new AtomicInteger();
        final EmbedBodyCache.BodyRender sensitive = () -> {
            renders.incrementAndGet();
            ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
            return "<p>hello viewer</p>";
        };
        unitRender( ctx, "who", sensitive );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );

        unitRender( ctx, "who", sensitive );

        assertEquals( 2, renders.get() );
    }

    @Test
    void aFailingBodyPutsTheHostFlagsBack() throws Exception {
        final Context ctx = unitContext();
        ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );

        assertThrows( IOException.class, () -> unitRender( ctx, "broken", () -> {
            throw new IOException( "boom" );
        } ) );

        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
        assertNull( ctx.getVariable( Context.VAR_RENDER_UNCACHEABLE ) );
    }

    private String view( final String name ) {
        return view( asView( Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( name ) ) ) );
    }

    private String view( final Context context ) {
        final String text = engine.getManager( PageManager.class ).getPureText( context.getPage().getName(), -1 );
        return engine.getManager( RenderingManager.class ).textToHTML( context, text );
    }

    private static Context asView( final Context context ) {
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return context;
    }

    private Context adminContext( final String name ) throws Exception {
        final HttpServletRequest request = ownSessionRequest( "embed-cache-admin-" );
        final Session admin = Wiki.session().find( engine, request );
        engine.getManager( AuthenticationManager.class ).login( admin, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( admin.isAuthenticated() );
        return asView( Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( name ) ) );
    }

    /** A never-logged-in viewer (the shared mock session is polluted by saveText's login). */
    private Context guestContext( final String name ) {
        final HttpServletRequest request = ownSessionRequest( "embed-cache-guest-" );
        assertFalse( Wiki.session().find( engine, request ).isAuthenticated() );
        return asView( Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( name ) ) );
    }

    private static HttpServletRequest ownSessionRequest( final String idPrefix ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession http = Mockito.mock( HttpSession.class );
        Mockito.doReturn( idPrefix + System.nanoTime() ).when( http ).getId();
        Mockito.doReturn( http ).when( request ).getSession();
        return request;
    }
}

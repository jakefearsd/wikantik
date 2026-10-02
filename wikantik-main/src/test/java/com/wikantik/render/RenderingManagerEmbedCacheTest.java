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
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.cache.CachingManager;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.markdown.MarkdownRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cache ordering for page embeds: the parsed document (placeholders only) is shared across viewers, while the
 * rendered HTML of a page that embeds another page is viewer-dependent and must never enter the HTML cache.
 */
class RenderingManagerEmbedCacheTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    /**
     * EhcacheCachingManager registers every cache except CACHE_HTML, so the HTML cache is inert by default; register it
     * here so the "not cached" assertions are not vacuous (the control test proves the cache is live).
     */
    @BeforeEach
    void enableHtmlCache() throws Exception {
        final CachingManager caches = engine.getManager( CachingManager.class );
        final java.lang.reflect.Method register = caches.getClass().getDeclaredMethod( "registerCache", String.class );
        register.setAccessible( true );
        register.invoke( caches, CachingManager.CACHE_HTML );
        assertTrue( caches.enabled( CachingManager.CACHE_HTML ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void embedRenderedAsAdminThenGuestKeepsTheSecretFromTheGuestAndOffTheHtmlCache() throws Exception {
        final String host = "EmbCacheHost";
        final String text = "intro\n\n![[EmbCacheSecret]]\n";
        engine.saveText( host, text );
        engine.saveText( "EmbCacheSecret", "[{ALLOW view Admin}]\n\ntop-secret-body\n" );
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        final String adminHtml = rm.textToHTML( pageViewContext( host, true ), text );
        final Context lastContext = pageViewContext( host, false );
        assertTrue( adminHtml.contains( "top-secret-body" ), "admin sees the embedded body: " + adminHtml );

        final String guestHtml = rm.textToHTML( pageViewContext( host, false ), text );
        assertFalse( guestHtml.contains( "top-secret-body" ), "guest must not see the restricted body: " + guestHtml );

        final CachingManager caches = engine.getManager( CachingManager.class );
        final String docId = cacheId( lastContext );
        assertNotNull( caches.get( CachingManager.CACHE_DOCUMENTS, docId, () -> null ),
                "the placeholder-only parsed document is shared, so it is cached" );
        assertNull( caches.get( CachingManager.CACHE_HTML, docId, () -> null ),
                "rendered HTML with an embed is viewer-dependent and must not be cached" );
    }

    @Test
    void controlPlainPageDoesPopulateTheHtmlCache() throws Exception {
        engine.saveText( "EmbCachePlain", "just text\n" );
        final Context ctx = pageViewContext( "EmbCachePlain", false );
        engine.getManager( RenderingManager.class ).textToHTML( ctx, "just text\n" );
        assertNotNull( engine.getManager( CachingManager.class ).get( CachingManager.CACHE_HTML, cacheId( ctx ), () -> null ),
                "otherwise the not-cached assertion above would be vacuous" );
    }

    /** Mirrors DefaultRenderingManager's cache key: name, version and the execute-plugins variable. */
    private static String cacheId( final Context ctx ) {
        return ctx.getRealPage().getName() + "::" + ctx.getRealPage().getVersion() + "::"
                + ctx.getVariable( Context.VAR_EXECUTE_PLUGINS );
    }

    private Context pageViewContext( final String name, final boolean admin ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession http = Mockito.mock( HttpSession.class );
        Mockito.doReturn( ( admin ? "emb-cache-admin-" : "emb-cache-guest-" ) + System.nanoTime() ).when( http ).getId();
        Mockito.doReturn( http ).when( request ).getSession();
        final Session session = Wiki.session().find( engine, request );
        if ( admin ) {
            engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
            assertTrue( session.isAuthenticated() );
        }
        final Context ctx = Wiki.context().create( engine, request, engine.getManager( PageManager.class ).getPage( name ) );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
    }
}

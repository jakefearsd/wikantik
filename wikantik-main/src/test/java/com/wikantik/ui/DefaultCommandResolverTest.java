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
package com.wikantik.ui;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Command;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests for {@link DefaultCommandResolver} covering uncovered branches:
 * null-request findCommand, VIEW with no page (front page resolution),
 * version parameter parsing in resolvePage, special page construction via properties,
 * getFinalPageName plural/wikify branches, and extractCommandFromPath
 * with hash/question marks in path.
 */
class DefaultCommandResolverTest {

    private TestEngine engine;
    private DefaultCommandResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( Engine.PROP_MATCHPLURALS, "yes" );
        engine = new TestEngine( props );
        resolver = new DefaultCommandResolver( engine, props );
        engine.saveText( "SinglePage", "content" );
        engine.saveText( "PluralPages", "content" );
    }

    @AfterEach
    void tearDown() throws Exception {
        final PageManager pm = engine.getManager( PageManager.class );
        pm.deletePage( "SinglePage" );
        pm.deletePage( "PluralPage" );
        pm.deletePage( "PluralPages" );
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // findCommand(null, defaultContext) – should fall back to static lookup
    // -----------------------------------------------------------------------

    @Test
    void findCommandWithNullRequestReturnsStaticCommand() {
        final Command cmd = resolver.findCommand( null, ContextEnum.PAGE_EDIT.getRequestContext() );
        assertEquals( PageCommand.EDIT, cmd );
    }

    @Test
    void findCommandWithNullRequestAndViewContextReturnsViewCommand() {
        // null request falls back to static lookup — returns the untargeted PAGE_VIEW command
        final Command cmd = resolver.findCommand( null, ContextEnum.PAGE_VIEW.getRequestContext() );
        assertNotNull( cmd );
        // Static lookup returns the base command (not targeted at front page, since there is no request)
        assertEquals( ContextEnum.PAGE_VIEW.getRequestContext(), cmd.getRequestContext() );
    }

    // -----------------------------------------------------------------------
    // findCommand – VIEW with no page param falls back to front page
    // -----------------------------------------------------------------------

    @Test
    void findCommandViewWithNoPageParamUsesFrontPage() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        final Command cmd = resolver.findCommand( req, ContextEnum.PAGE_VIEW.getRequestContext() );
        assertNotNull( cmd );
        final Object target = cmd.getTarget();
        assertNotNull( target, "VIEW should be targeted at front page when no page param given" );
        assertTrue( target instanceof Page );
        assertEquals( engine.getFrontPage(), ( ( Page ) target ).getName() );
    }

    // -----------------------------------------------------------------------
    // resolvePage – version parameter parsing (valid + invalid)
    // -----------------------------------------------------------------------

    @Test
    void resolvePageUsesVersionParameter() throws Exception {
        engine.saveText( "VersionedPage", "v1" );
        engine.saveText( "VersionedPage", "v2" );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp?page=VersionedPage&version=1" );
        Mockito.doReturn( "1" ).when( req ).getParameter( "version" );

        final Page page = resolver.resolvePage( req, "VersionedPage" );
        assertNotNull( page );
        assertEquals( "VersionedPage", page.getName() );
    }

    @Test
    void resolvePageIgnoresInvalidVersionParameter() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp?page=SinglePage&version=notAnInt" );
        Mockito.doReturn( "notAnInt" ).when( req ).getParameter( "version" );

        // Should not throw; falls back to latest version
        final Page page = resolver.resolvePage( req, "SinglePage" );
        assertNotNull( page );
        assertEquals( "SinglePage", page.getName() );
    }

    @Test
    void resolvePageWithNullVersionUsesLatest() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        Mockito.doReturn( null ).when( req ).getParameter( "version" );

        final Page page = resolver.resolvePage( req, "SinglePage" );
        assertNotNull( page );
        assertEquals( "SinglePage", page.getName() );
    }

    @Test
    void resolvePageReturnsNewPageObjectForNonExistentPage() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        Mockito.doReturn( null ).when( req ).getParameter( "version" );

        final Page page = resolver.resolvePage( req, "ThisPageDoesNotExistXYZ" );
        assertNotNull( page, "resolvePage should always return a non-null Page" );
    }

    // -----------------------------------------------------------------------
    // getFinalPageName – wikify branch (no direct match, try wikified form)
    // -----------------------------------------------------------------------

    @Test
    void getFinalPageNameReturnsNullForCompletelyUnknownPage() throws Exception {
        final String result = resolver.getFinalPageName( "AbsolutelyNonExistentPageXYZ999" );
        assertNull( result );
    }

    @Test
    void getFinalPageNameSingularPluralVariants() throws Exception {
        // PluralPages exists, SinglePage exists – verify mapping in both directions
        final String s = resolver.getFinalPageName( "SinglePages" ); // plural → singular
        assertEquals( "SinglePage", s );

        final String p = resolver.getFinalPageName( "PluralPage" ); // singular → plural
        assertEquals( "PluralPages", p );
    }

    // -----------------------------------------------------------------------
    // extractCommandFromPath – hash and question-mark stripping
    // -----------------------------------------------------------------------

    @Test
    void extractCommandFromPathStripsHashFragment() {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/search#section" ).when( req ).getServletPath();

        final Command cmd = resolver.extractCommandFromPath( req );
        // "search" maps to WIKI_FIND
        assertNotNull( cmd );
        assertEquals( ContextEnum.WIKI_FIND.getRequestContext(), cmd.getRequestContext() );
    }

    @Test
    void extractCommandFromPathStripsQueryString() {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/search?query=test" ).when( req ).getServletPath();

        final Command cmd = resolver.extractCommandFromPath( req );
        assertNotNull( cmd );
        assertEquals( ContextEnum.WIKI_FIND.getRequestContext(), cmd.getRequestContext() );
    }

    @Test
    void extractCommandFromPathReturnsNullForUnknownPath() {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/UnknownServlet.xyz" ).when( req ).getServletPath();

        final Command cmd = resolver.extractCommandFromPath( req );
        assertNull( cmd, "Unknown path should return null" );
    }

    @Test
    void extractCommandFromPathHandlesEmptyPath() {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/" ).when( req ).getServletPath();

        final Command cmd = resolver.extractCommandFromPath( req );
        assertNull( cmd );
    }

    // -----------------------------------------------------------------------
    // getSpecialPageReference returns null for unknown special page
    // -----------------------------------------------------------------------

    @Test
    void getSpecialPageReferenceReturnsNullForUnknownPage() {
        final String url = resolver.getSpecialPageReference( "NoSuchSpecialPage" );
        assertNull( url );
    }

    // -----------------------------------------------------------------------
    // WikiCommand.CREATE_GROUP targeting
    // -----------------------------------------------------------------------

    @Test
    void findCommandCreateGroupIsTargetedAtWikiName() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/new-group" );
        final Command cmd = resolver.findCommand( req, ContextEnum.PAGE_EDIT.getRequestContext() );
        assertNotNull( cmd );
        assertEquals( WikiCommand.CREATE_GROUP.getRequestContext(), cmd.getRequestContext() );
        assertEquals( engine.getApplicationName(), cmd.getTarget() );
    }
}

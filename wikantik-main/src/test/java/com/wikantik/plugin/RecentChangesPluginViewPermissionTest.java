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
package com.wikantik.plugin;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.render.RenderingManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecentChangesPlugin} lists pages for whoever views the page that hosts it, so it must list
 * only the pages that viewer may view, and must flag its render viewer-sensitive so the shared,
 * principal-less render caches never hand one viewer's listing to another.
 *
 * <p>Fixture notes: a non-empty ACL is deny-by-default, and {@code edit} implies {@code view}, so
 * {@code [{ALLOW view Admin}]} hides a page from every non-admin and {@code [{ALLOW edit Admin}]}
 * does the same while still letting an admin see it.</p>
 */
public class RecentChangesPluginViewPermissionTest {

    // Caching is deliberately ON: the render-sharing test below only means something with caches live.
    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "true" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    private static final String PLUGIN = "{INSERT com.wikantik.plugin.RecentChangesPlugin}";

    @BeforeEach
    public void setUp() throws Exception {
        testEngine.saveText( "RcvHost", "[{RecentChangesPlugin}]" );
        testEngine.saveText( "RcvOpen", "open page" );
        testEngine.saveText( "RcvSecret", "[{ALLOW view Admin}]\n\nclassified" );
        testEngine.saveText( "RcvEditLocked", "[{ALLOW edit Admin}]\n\nedit-locked" );
        testEngine.addAttachment( "RcvSecret", "hidden-file.txt", "attachment of a classified page".getBytes() );
        testEngine.addAttachment( "RcvOpen", "shown-file.txt", "attachment of an open page".getBytes() );
    }

    @AfterEach
    public void tearDown() {
        for ( final String name : new String[] { "RcvHost", "RcvOpen", "RcvSecret", "RcvEditLocked" } ) {
            testEngine.deleteTestPage( name );
            TestEngine.deleteAttachments( name );
        }
        TestEngine.emptyWorkDir();
    }

    private Context guestContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/RcvHost" );
        final Context ctx = Wiki.context().create( testEngine, request, host() );
        assertTrue( ctx.getWikiSession().isAnonymous(), "fixture: guest context must be anonymous" );
        return ctx;
    }

    private Context adminContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/RcvHost" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return Wiki.context().create( testEngine, request, host() );
    }

    private Page host() {
        return testEngine.getManager( PageManager.class ).getPage( "RcvHost" );
    }

    @Test
    public void restrictedPagesAreAbsentForAnonymousViewer() throws Exception {
        final String res = manager.execute( guestContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
        assertFalse( res.contains( "RcvSecret" ), "restricted page name leaked: " + res );
        assertFalse( res.contains( "Rcv Secret" ), "restricted page title leaked: " + res );
        assertFalse( res.contains( "RcvEditLocked" ), "edit-restricted page name leaked: " + res );
        assertFalse( res.contains( "Rcv Edit Locked" ), "edit-restricted page title leaked: " + res );
    }

    @Test
    public void restrictedPagesArePresentForAdministrator() throws Exception {
        final String res = manager.execute( adminContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvSecret\">Rcv Secret</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvEditLocked\">Rcv Edit Locked</a>" ), res );
    }

    @Test
    public void unrestrictedPagesAreUnaffected() throws Exception {
        final String res = manager.execute( guestContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvHost\">Rcv Host</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
    }

    @Test
    public void includeParameterCannotBypassTheViewCheck() throws Exception {
        final String res = manager.execute( guestContext(),
                "{INSERT com.wikantik.plugin.RecentChangesPlugin include='RcvSecret*'}" );

        assertEquals( "<table class=\"recentchanges\" cellpadding=\"4\"></table>", res );
    }

    @Test
    public void attachmentsOfRestrictedPagesAreHidden() throws Exception {
        final String guest = manager.execute( guestContext(), PLUGIN );
        assertTrue( guest.contains( "shown-file.txt" ), "attachment of an open page should be listed: " + guest );
        assertFalse( guest.contains( "hidden-file.txt" ), "attachment of a restricted page leaked: " + guest );

        final String admin = manager.execute( adminContext(), PLUGIN );
        assertTrue( admin.contains( "hidden-file.txt" ), admin );
    }

    @Test
    public void executionFlagsTheRenderViewerSensitive() throws Exception {
        final Context ctx = guestContext();

        manager.execute( ctx, PLUGIN );

        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ),
                "the listing depends on the viewer, so it must never enter the shared render caches" );
    }

    @Test
    public void renderedHostPageIsNotSharedBetweenViewers() throws Exception {
        final RenderingManager renderer = testEngine.getManager( RenderingManager.class );
        final String source = testEngine.getManager( PageManager.class ).getPureText( "RcvHost", -1 );

        final String asAdmin = renderer.textToHTML( adminContext(), source );
        final String asGuest = renderer.textToHTML( guestContext(), source );
        final String asAdminAgain = renderer.textToHTML( adminContext(), source );

        assertTrue( asAdmin.contains( "RcvSecret" ), "admin render should list the restricted page: " + asAdmin );
        assertFalse( asGuest.contains( "RcvSecret" ), "guest render must not reuse the admin listing: " + asGuest );
        assertTrue( asAdminAgain.contains( "RcvSecret" ) );
    }
}

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page-listing plugins ({@link IndexPlugin} and the {@link AbstractReferralPlugin} family) list pages for whoever
 * views the page that hosts them, so they must list only the pages that viewer may view, must not report the links of
 * a page the viewer may not view, and must flag the render viewer-sensitive so the shared, principal-less render
 * caches never hand one viewer's listing to another.
 *
 * <p>Two kinds of hidden page: {@code LpvPrivate} sits in a private cluster (hidden by the private-cluster gate, no
 * access rule of its own) and {@code LpvAclSecret} carries {@code [{ALLOW view Admin}]}.</p>
 */
class ListingPluginsViewPermissionTest {

    // Caching ON: the render-sharing test only means something with the caches live.
    static TestEngine testEngine;
    static PluginManager manager;

    private static final String[] PAGES = {
        "LpvHost", "LpvOpen", "LpvOpenLinker", "LpvPrivate", "LpvAclSecret", "LpvHub"
    };

    @BeforeAll
    static void setUp() throws Exception {
        testEngine = TestEngine.build( with( "wikantik.cache.enable", "true" ),
                                       with( "wikantik.access.privateClusters", "lpv-secret" ) );
        manager = testEngine.getManager( PluginManager.class );
        testEngine.saveText( "LpvHost", "[{IndexPlugin include='Lpv.*'}]" );
        testEngine.saveText( "LpvOpen", "Open page. See [LpvPrivate]() and [LpvGhostPublic]()." );
        testEngine.saveText( "LpvOpenLinker", "Links to [LpvOpen]()." );
        testEngine.saveText( "LpvPrivate",
                "---\ncluster: lpv-secret\n---\nPrivate. Links to [LpvOpen]() and [LpvGhostOnlyPrivate]().\n" );
        testEngine.saveText( "LpvAclSecret", "[{ALLOW view Admin}]\n\nClassified. Links to [LpvOpen]()." );
        testEngine.saveText( "LpvHub", "---\ntype: hub\ncluster: lpv-open\nrelated: [LpvOpen, LpvPrivate]\n---\nHub.\n" );
    }

    @AfterAll
    static void tearDown() {
        for ( final String name : PAGES ) {
            testEngine.deleteTestPage( name );
        }
        TestEngine.emptyWorkDir();
        testEngine.stop();
    }

    private static Context guestContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/LpvHost" );
        final Context ctx = Wiki.context().create( testEngine, request, host() );
        assertTrue( ctx.getWikiSession().isAnonymous(), "fixture: guest context must be anonymous" );
        return ctx;
    }

    private static Context adminContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/LpvHost" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return Wiki.context().create( testEngine, request, host() );
    }

    private static Page host() {
        return testEngine.getManager( PageManager.class ).getPage( "LpvHost" );
    }

    private static void assertHidden( final String res ) {
        assertFalse( res.contains( "LpvPrivate" ), "private-cluster page leaked: " + res );
        assertFalse( res.contains( "LpvAclSecret" ), "access-restricted page leaked: " + res );
    }

    @Test
    void indexListsOnlyViewablePages() throws Exception {
        final String guest = manager.execute( guestContext(), "{INSERT com.wikantik.plugin.IndexPlugin include='Lpv.*'}" );
        assertTrue( guest.contains( "LpvOpen" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), "{INSERT com.wikantik.plugin.IndexPlugin include='Lpv.*'}" );
        assertTrue( admin.contains( "LpvPrivate" ), admin );
        assertTrue( admin.contains( "LpvAclSecret" ), admin );
    }

    @Test
    void unusedListsOnlyViewablePages() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.UnusedPagesPlugin include='Lpv*'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvOpenLinker" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvAclSecret" ), admin );
    }

    @Test
    void undefinedListsOnlyNamesLinkedFromViewablePages() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.UndefinedPagesPlugin include='LpvGhost*'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvGhostPublic" ), guest );
        assertFalse( guest.contains( "LpvGhostOnlyPrivate" ), "a link target of a private page leaked: " + guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvGhostOnlyPrivate" ), admin );
    }

    @Test
    void referringUndefinedListsOnlyViewableReferrers() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.ReferringUndefinedPagesPlugin include='Lpv*'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvOpen" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvPrivate" ), admin );
    }

    @Test
    void referringListsOnlyViewableReferrers() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.ReferringPagesPlugin page='LpvOpen'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvOpenLinker" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvPrivate" ), admin );
        assertTrue( admin.contains( "LpvAclSecret" ), admin );
    }

    @Test
    void referringReportsNothingForAPageTheViewerMayNotView() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.ReferringPagesPlugin page='LpvPrivate'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertFalse( guest.contains( "LpvOpen" ), "the referrers of a hidden page leaked: " + guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvOpen" ), admin );
    }

    @Test
    void referredListsOnlyViewableTargets() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.ReferredPagesPlugin page='LpvOpenLinker' depth='3'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvOpen" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvPrivate" ), admin );
    }

    @Test
    void referredReportsNothingForAPageTheViewerMayNotView() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.ReferredPagesPlugin page='LpvPrivate'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertFalse( guest.contains( "LpvOpen" ), "the links of a hidden page leaked: " + guest );
        assertFalse( guest.contains( "LpvPrivate" ), "a hidden page was named: " + guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvOpen" ), admin );
    }

    @Test
    void hubSetListsOnlyViewableMembers() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.HubSetPlugin hub='LpvHub'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "LpvOpen" ), guest );
        assertHidden( guest );

        final String admin = manager.execute( adminContext(), plugin );
        assertTrue( admin.contains( "LpvPrivate" ), admin );
    }

    @ParameterizedTest
    @ValueSource( strings = { "IndexPlugin", "UnusedPagesPlugin", "UndefinedPagesPlugin", "ReferringPagesPlugin",
                              "ReferredPagesPlugin", "ReferringUndefinedPagesPlugin" } )
    void executionFlagsTheRenderViewerSensitive( final String plugin ) throws Exception {
        final Context ctx = guestContext();

        manager.execute( ctx, "{INSERT com.wikantik.plugin." + plugin + "}" );

        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ),
                plugin + " lists pages per viewer, so its render must never enter the shared render caches" );
    }

    @Test
    void renderedHostPageIsNotSharedBetweenViewers() throws Exception {
        final RenderingManager renderer = testEngine.getManager( RenderingManager.class );
        final String source = testEngine.getManager( PageManager.class ).getPureText( "LpvHost", -1 );

        final String asAdmin = renderer.textToHTML( adminContext(), source );
        final String asGuest = renderer.textToHTML( guestContext(), source );

        assertTrue( asAdmin.contains( "LpvPrivate" ), "admin render should list the private page: " + asAdmin );
        assertFalse( asGuest.contains( "LpvPrivate" ), "guest render must not reuse the admin listing: " + asGuest );
    }
}

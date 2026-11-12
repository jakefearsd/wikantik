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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugins that read ANOTHER page ({@link IfPlugin} with {@code page=}, {@link HubSetPlugin} with {@code hub=} and its
 * cards, {@link InsertPage}) must answer as if a page the viewer may not view did not exist, and must flag the render
 * viewer-sensitive. Otherwise anyone who can save a page could publish an oracle over a hidden page's text.
 */
class CrossPageReadPluginsViewPermissionTest {

    static TestEngine testEngine;
    static PluginManager manager;

    private static final String[] PAGES = { "CprHost", "CprPrivate", "CprPrivateHub", "CprOpen", "CprOpenHub" };

    @BeforeAll
    static void setUp() throws Exception {
        testEngine = TestEngine.build( with( "wikantik.cache.enable", "true" ),
                                       with( "wikantik.access.privateClusters", "cpr-secret" ) );
        manager = testEngine.getManager( PluginManager.class );
        testEngine.saveText( "CprHost", "host" );
        testEngine.saveText( "CprPrivate", "---\ncluster: cpr-secret\nsummary: zzsecretsummary\n---\nThe zzprivmarker192 box.\n" );
        testEngine.saveText( "CprPrivateHub", "---\ntype: hub\ncluster: cpr-secret\nrelated: [CprOpen]\n---\nPrivate hub.\n" );
        testEngine.saveText( "CprOpen", "---\nsummary: zzopensummary\n---\nOpen.\n" );
        testEngine.saveText( "CprOpenHub", "---\ntype: hub\ncluster: cpr-open\nrelated: [CprOpen, CprPrivate]\n---\nHub.\n" );
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
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/CprHost" );
        final Context ctx = Wiki.context().create( testEngine, request, host() );
        assertTrue( ctx.getWikiSession().isAnonymous(), "fixture: guest context must be anonymous" );
        return ctx;
    }

    private static Context adminContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/CprHost" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return Wiki.context().create( testEngine, request, host() );
    }

    private static Page host() {
        return testEngine.getManager( PageManager.class ).getPage( "CprHost" );
    }

    @Test
    void ifContainsCannotProbeAHiddenPage() throws Exception {
        final String plugin = "{IfPlugin page='CprPrivate' contains='zzprivmarker'\n\nPROBE-HIT}";
        assertFalse( manager.execute( guestContext(), plugin ).contains( "PROBE-HIT" ),
                "a guest learned what a hidden page contains" );
        assertTrue( manager.execute( adminContext(), plugin ).contains( "PROBE-HIT" ), "control: admin sees the match" );
    }

    @Test
    void ifContainsStillReadsAViewablePage() throws Exception {
        final String plugin = "{IfPlugin page='CprOpen' contains='Open'\n\nOPEN-HIT}";
        assertTrue( manager.execute( guestContext(), plugin ).contains( "OPEN-HIT" ) );
    }

    @Test
    void ifExistsTreatsAHiddenPageAsMissing() throws Exception {
        assertFalse( manager.execute( guestContext(), "{IfPlugin page='CprPrivate' exists='true'\n\nEXISTS}" )
                            .contains( "EXISTS" ), "a guest learned that a hidden page exists" );
        assertTrue( manager.execute( guestContext(), "{IfPlugin page='CprPrivate' exists='false'\n\nMISSING}" )
                           .contains( "MISSING" ), "a hidden page must read as missing" );
        assertTrue( manager.execute( adminContext(), "{IfPlugin page='CprPrivate' exists='true'\n\nEXISTS}" )
                           .contains( "EXISTS" ), "control: admin sees it exists" );
    }

    @Test
    void ifFlagsTheRenderViewerSensitive() throws Exception {
        final Context ctx = guestContext();
        manager.execute( ctx, "{IfPlugin page='CprOpen' exists='true'\n\nX}" );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test
    void hubSetOfAHiddenHubReadsAsMissing() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.HubSetPlugin hub='CprPrivateHub'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertFalse( guest.contains( "CprOpen" ), "the members of a hidden hub leaked: " + guest );
        assertTrue( guest.contains( "does not exist" ), guest );

        assertTrue( manager.execute( adminContext(), plugin ).contains( "CprOpen" ), "control: admin sees the members" );
    }

    @Test
    void hubSetCardsShowOnlyViewableMembers() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.HubSetPlugin hub='CprOpenHub' detail='cards'}";
        final String guest = manager.execute( guestContext(), plugin );
        assertTrue( guest.contains( "zzopensummary" ), guest );
        assertFalse( guest.contains( "zzsecretsummary" ), "a hidden member's summary leaked: " + guest );
        assertFalse( guest.contains( "CprPrivate" ), "a hidden member leaked: " + guest );
    }

    @Test
    void hubSetFlagsTheRenderViewerSensitiveEvenWhenTheHubIsMissing() throws Exception {
        final Context ctx = guestContext();
        manager.execute( ctx, "{INSERT com.wikantik.plugin.HubSetPlugin hub='CprPrivateHub'}" );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test
    void insertPageHonoursThePrivateClusterGate() throws Exception {
        final String plugin = "{INSERT com.wikantik.plugin.InsertPage page='CprPrivate'}";
        assertFalse( manager.execute( guestContext(), plugin ).contains( "zzprivmarker192" ),
                "a guest read a hidden page through InsertPage" );
        assertTrue( manager.execute( adminContext(), plugin ).contains( "zzprivmarker192" ), "control: admin reads it" );
    }
}

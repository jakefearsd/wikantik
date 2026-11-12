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
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The private-cluster rule inside the page-permission decision, on a real engine: a page in a private cluster is
 * hidden from everyone but administrators and the viewer groups, for every page action, and a page's own access rules
 * can only narrow that further.
 */
class PrivateClusterAuthorizationTest {

    private static final String VIEWERS = "HomeLabViewers";
    private static final String PRIVATE = "PrivateClusterRouterPage";
    private static final String PUBLIC = "PrivateClusterPublicPage";
    private static final String PRIVATE_FM = "---\ntitle: Router\ncluster: home-lab-infrastructure\n---\n";

    private TestEngine engine;
    private AuthorizationManager auth;
    private Session anonymous;
    private Session viewer;
    private Session outsider;
    private Session admin;
    @SuppressWarnings( "unused" ) // strong ref keeps the weakly-held listener alive
    private WikiEventListener listener;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build(
                with( PrivateClusterGate.PROP_PRIVATE_CLUSTERS, "home-lab-infrastructure, remote-host-management" ),
                with( PrivateClusterGate.PROP_VIEWERS, VIEWERS ) );
        auth = engine.getManager( AuthorizationManager.class );
        admin = login( Users.ADMIN, Users.ADMIN_PASS );
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group viewers = groups.parseGroup( VIEWERS, Users.BOB, true );
        groups.setGroup( admin, viewers );
        anonymous = WikiSessionTest.anonymousSession( engine );
        viewer = login( Users.BOB, Users.BOB_PASS );
        outsider = login( Users.CHARLIE, Users.CHARLIE_PASS );
        engine.saveText( PUBLIC, "---\ntitle: Public\ncluster: wikantik-development\n---\nOpen to all.\n" );
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.deleteQuietly( PRIVATE, PUBLIC );
        engine.getManager( GroupManager.class ).removeGroup( VIEWERS );
        engine.stop();
    }

    /**
     * A session of its own for {@code user}. Every mock request shares one session id, so the shared session is evicted
     * before the login (a fresh one) and after it (so a later login or an admin {@code saveText} cannot take it over).
     */
    private Session login( final String user, final String password ) throws Exception {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
        final Session session = WikiSessionTest.authenticatedSession( engine, user, password );
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
        return session;
    }

    /** The permission every surface asks for (see {@code PermissionFilter}): built from the stored page, so its ACL applies. */
    private Permission permission( final String page, final String action ) {
        final Page stored = engine.getManager( PageManager.class ).getPageWithoutMetadata( page, PageProvider.LATEST_VERSION );
        return PermissionFactory.getPagePermission( stored, action );
    }

    private Permission view( final String page ) {
        return permission( page, "view" );
    }

    private Permission edit( final String page ) {
        return permission( page, "edit" );
    }

    private List< WikiSecurityEvent > captureEvents() {
        final List< WikiSecurityEvent > events = new ArrayList<>();
        listener = e -> {
            if ( e instanceof WikiSecurityEvent se ) {
                events.add( se );
            }
        };
        auth.addWikiEventListener( listener );
        return events;
    }

    @Test
    void anonymousCannotViewAPrivatePageAndTheDenialSaysWhy() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );
        final List< WikiSecurityEvent > events = captureEvents();

        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ) );

        final WikiSecurityEvent denied = events.stream()
                .filter( e -> e.getType() == WikiSecurityEvent.ACCESS_DENIED ).findFirst().orElseThrow();
        assertEquals( "private-cluster", denied.getAttributes().get( "reason" ) );
        assertTrue( auth.checkPermission( anonymous, view( PUBLIC ) ), "a public page is unaffected" );
    }

    @Test
    void anAuthenticatedNonMemberCanNeitherViewNorEdit() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );

        assertFalse( auth.checkPermission( outsider, view( PRIVATE ) ) );
        assertFalse( auth.checkPermission( outsider, edit( PRIVATE ) ) );
        assertFalse( auth.isPermitted( outsider, permission( PRIVATE, "comment" ) ) );
        assertTrue( auth.checkPermission( outsider, edit( PUBLIC ) ), "the same user still edits public pages" );
    }

    @Test
    void aViewerGroupMemberCanViewAndEdit() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );

        assertTrue( auth.checkPermission( viewer, view( PRIVATE ) ) );
        assertTrue( auth.checkPermission( viewer, edit( PRIVATE ) ) );
    }

    @Test
    void anAdministratorCanViewAndEdit() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );

        assertTrue( auth.checkPermission( admin, view( PRIVATE ) ) );
        assertTrue( auth.checkPermission( admin, edit( PRIVATE ) ) );
    }

    @Test
    void theHubPageOfAPrivateClusterIsHidden() throws Exception {
        engine.saveText( PRIVATE, "---\ntitle: Home lab\ntype: hub\ncluster: home-lab-infrastructure\n---\nThe hub.\n" );

        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ) );
        assertTrue( auth.checkPermission( viewer, view( PRIVATE ) ) );
    }

    @Test
    void aSubClusterPageIsHidden() throws Exception {
        engine.saveText( PRIVATE, "---\ntitle: Nas\ncluster: remote-host-management/backups\n---\nBody.\n" );

        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ) );
    }

    @Test
    void aPageRuleOpeningThePageToAllGrantsNothingBeyondTheCluster() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "[{ALLOW view All}]\n\nLAN addresses.\n" );

        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ), "All does not re-open a hidden page" );
        assertFalse( auth.checkPermission( outsider, view( PRIVATE ) ) );
        assertTrue( auth.checkPermission( viewer, view( PRIVATE ) ) );
    }

    @Test
    void aPageRuleNarrowsAccessForViewers() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "[{ALLOW view Admin}]\n\nLAN addresses.\n" );

        assertFalse( auth.checkPermission( viewer, view( PRIVATE ) ), "the page's own rule still applies to viewers" );
        assertTrue( auth.checkPermission( admin, view( PRIVATE ) ) );
    }

    @Test
    void aLockedPrivatePageStaysLockedToViewers() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "Placeholder.\n" );
        final PageManager pages = engine.getManager( PageManager.class );
        // A lock is a stored disputed rule (a save would refuse it): write it to the store directly.
        pages.getProvider().putPageText( pages.getPage( PRIVATE ), PRIVATE_FM + "[{DENY view Bob}]\n\nBody.\n" );

        assertFalse( auth.checkPermission( viewer, view( PRIVATE ) ) );
        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ) );
        assertTrue( auth.checkPermission( admin, view( PRIVATE ) ) );
    }

    @Test
    void filterViewableDropsPrivatePagesForAnonymous() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );

        assertEquals( Set.of( PUBLIC ), auth.filterViewable( anonymous, List.of( PUBLIC, PRIVATE ) ) );
        assertEquals( Set.of( PUBLIC ), auth.filterViewable( outsider, List.of( PUBLIC, PRIVATE ) ) );
        assertEquals( Set.of( PUBLIC, PRIVATE ), auth.filterViewable( viewer, List.of( PUBLIC, PRIVATE ) ) );
        assertEquals( Set.of( PUBLIC, PRIVATE ), auth.filterViewable( admin, List.of( PUBLIC, PRIVATE ) ) );
    }

    @Test
    void anAttachmentOfAPrivatePageIsHidden() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );
        final File f = engine.makeAttachmentFile();
        final Attachment att = Wiki.contents().attachment( engine, PRIVATE, "diagram.txt" );
        att.setAuthor( "FirstPost" );
        engine.getManager( AttachmentManager.class ).storeAttachment( att, f );
        try {
            final Page stored = engine.getManager( PageManager.class ).getPage( PRIVATE + "/diagram.txt" );
            final Permission viewAtt = PermissionFactory.getPagePermission( stored, "view" );

            assertFalse( auth.checkPermission( anonymous, viewAtt ) );
            assertTrue( auth.checkPermission( viewer, viewAtt ) );
            assertEquals( Set.of(), auth.filterViewable( anonymous, List.of( PRIVATE + "/diagram.txt" ) ) );
        } finally {
            engine.getManager( AttachmentManager.class ).deleteAttachment( att );
        }
    }

    @Test
    void aPageMovedOutOfThePrivateClusterIsVisibleAtItsNextVersion() throws Exception {
        engine.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );
        assertFalse( auth.checkPermission( anonymous, view( PRIVATE ) ) );

        engine.saveText( PRIVATE, "---\ntitle: Router\ncluster: wikantik-development\n---\nNo addresses now.\n" );

        assertTrue( auth.checkPermission( anonymous, view( PRIVATE ) ) );
    }

    @Test
    void withTheSettingBlankAPageInTheClusterBehavesAsBefore() throws Exception {
        final TestEngine plain = TestEngine.build();
        try {
            plain.saveText( PRIVATE, PRIVATE_FM + "LAN addresses.\n" );
            final AuthorizationManager plainAuth = plain.getManager( AuthorizationManager.class );
            final Session anon = WikiSessionTest.anonymousSession( plain );
            final Page stored = plain.getManager( PageManager.class ).getPageWithoutMetadata( PRIVATE, PageProvider.LATEST_VERSION );

            assertTrue( plainAuth.checkPermission( anon, PermissionFactory.getPagePermission( stored, "view" ) ) );
            assertEquals( Set.of( PRIVATE ), plainAuth.filterViewable( anon, List.of( PRIVATE ) ) );
        } finally {
            plain.deleteQuietly( PRIVATE );
            plain.stop();
        }
    }
}

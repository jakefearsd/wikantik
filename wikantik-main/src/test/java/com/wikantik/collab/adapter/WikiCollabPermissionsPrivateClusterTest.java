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
package com.wikantik.collab.adapter;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The private-cluster rule on the drafts of deleted pages (rulings F1 and F3'): the page is gone, so no page permission
 * can see its cluster; the deleted page's last text is what decides, as it does for the page's own access rules.
 */
class WikiCollabPermissionsPrivateClusterTest {

    private static final String VIEWERS = "CollabHomeLabViewers";
    private static final String PRIVATE_TEXT = "---\ntitle: Router\ncluster: home-lab-infrastructure/lan\n---\n10.0.0.1\n";
    private static final String PUBLIC_TEXT = "---\ntitle: Notes\ncluster: home-lab-infrastructure-notes\n---\nbody\n";

    private TestEngine engine;
    private boolean groupCreated;

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            if ( groupCreated ) {
                engine.getManager( GroupManager.class ).removeGroup( VIEWERS );
            }
            engine.stop();
        }
    }

    private void gatedEngine() throws Exception {
        engine = TestEngine.build( with( "wikantik.access.privateClusters", "home-lab-infrastructure" ),
                with( "wikantik.access.privateClusters.viewers", VIEWERS ) );
        final Session admin = login( Users.ADMIN, Users.ADMIN_PASS );
        final GroupManager groups = engine.getManager( GroupManager.class );
        groups.setGroup( admin, groups.parseGroup( VIEWERS, Users.BOB, true ) );
        groupCreated = true;
    }

    /** A session of its own: every mock request shares one session id, so it is evicted around each login. */
    private Session login( final String user, final String password ) throws Exception {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
        final Session session = WikiSessionTest.authenticatedSession( engine, user, password );
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
        return session;
    }

    @Test
    void aNonViewerCannotJoinTheDraftOfADeletedPrivatePage() throws Exception {
        gatedEngine();
        final Session outsider = login( Users.CHARLIE, Users.CHARLIE_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertFalse( permissions.mayAccessAsIf( outsider, "GoneRouterPage", PRIVATE_TEXT ),
            "the deleted page's cluster still hides its unpublished text" );
        assertTrue( permissions.mayAccessAsIf( outsider, "GoneNotesPage", PUBLIC_TEXT ),
            "a cluster that only shares a prefix is not private" );
    }

    @Test
    void aPageRuleOpeningTheDeletedPrivatePageGrantsNothing() throws Exception {
        gatedEngine();
        final Session outsider = login( Users.CHARLIE, Users.CHARLIE_PASS );

        assertFalse( new WikiCollabPermissions( engine ).mayAccessAsIf( outsider, "GoneRouterPage",
            PRIVATE_TEXT + "[{ALLOW edit All}]\n" ) );
    }

    @Test
    void aViewerAndAnAdministratorCanJoinTheDraftOfADeletedPrivatePage() throws Exception {
        gatedEngine();
        final Session viewer = login( Users.BOB, Users.BOB_PASS );
        final Session admin = login( Users.ADMIN, Users.ADMIN_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertTrue( permissions.mayAccessAsIf( viewer, "GoneRouterPage", PRIVATE_TEXT ) );
        assertTrue( permissions.mayAccessAsIf( admin, "GoneRouterPage", PRIVATE_TEXT ) );
        assertFalse( permissions.mayAccessAsIf( viewer, "GoneRouterPage", PRIVATE_TEXT + "[{ALLOW edit Admin}]\n" ),
            "the deleted page's own rules still narrow access for viewers" );
    }

    @Test
    void aGuardFromAPrivatePageRestricts() throws Exception {
        gatedEngine();
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertTrue( permissions.restricts( "GoneRouterPage", PRIVATE_TEXT ),
            "only an administrator may publish over a guard that came from a private page" );
        assertFalse( permissions.restricts( "GoneNotesPage", PUBLIC_TEXT ) );
    }

    @Test
    void withTheSettingBlankTheClusterChangesNothing() throws Exception {
        engine = TestEngine.build();
        final Session outsider = login( Users.CHARLIE, Users.CHARLIE_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertTrue( permissions.mayAccessAsIf( outsider, "GoneRouterPage", PRIVATE_TEXT ) );
        assertFalse( permissions.restricts( "GoneRouterPage", PRIVATE_TEXT ) );
    }
}

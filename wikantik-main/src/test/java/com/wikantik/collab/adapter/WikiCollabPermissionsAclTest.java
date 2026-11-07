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

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.auth.Users;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real engine, real ACL and policy wiring: no mocked AuthorizationManager. */
class WikiCollabPermissionsAclTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( "AclLocked" );
        engine.deleteTestPage( "AclOpen" );
        engine.stop();
    }

    @Test
    void realAclDeniesEditToAnOrdinaryUserAndAnAclFreePageAllowsIt() throws Exception {
        engine.saveText( "AclLocked", "[{ALLOW edit SomeOtherUser}]\nlocked" );
        engine.saveText( "AclOpen", "open" );
        final Session user = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertFalse( permissions.canEdit( user, "AclLocked" ) );
        assertTrue( permissions.canEdit( user, "AclOpen" ) );
    }

    // test sessions share one id (one session per test): each test below logs in exactly one user

    /** Ruling F1: a deleted page's last text still decides who may join its draft, read as the wiki reads its rules. */
    @Test
    void aMissingPagesAccessIsJudgedByTheGivenTextsRules() throws Exception {
        final Session bob = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertFalse( permissions.isAdmin( bob ) );
        assertFalse( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW edit SomeOtherUser}]\nsecret" ) );
        assertTrue( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW edit Bob}]\nshared" ) );
        assertFalse( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW view Bob}]\nread only" ),
            "view alone is not enough to join a draft" );
        assertTrue( permissions.mayAccessAsIf( bob, "GonePage", "no rules at all" ) );
        assertTrue( permissions.mayAccessAsIf( bob, "GonePage", "`[{ALLOW edit SomeOtherUser}]` is only an example" ),
            "a rule shown as code is no rule, exactly as on a live page" );
        assertFalse( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW frobnicate Bob}]\nlocked" ),
            "a rule the wiki cannot read locks the page" );
    }

    @Test
    void nonSessionsAreRefused() {
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertFalse( permissions.mayAccessAsIf( "not a session", "GonePage", "open" ) );
        assertFalse( permissions.isAdmin( "not a session" ) );
    }

    @Test
    void anAdministratorPassesAnyRules() throws Exception {
        final Session admin = WikiSessionTest.adminSession( engine );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertTrue( permissions.isAdmin( admin ) );
        assertTrue( permissions.mayAccessAsIf( admin, "GonePage", "[{ALLOW edit SomeOtherUser}]\nsecret" ) );
    }

    // ---- re-review (b): the full base text, read as a live page's ---------------------------------------------------

    /** A rule in the frontmatter is disputed and locks the page, as on a live page: only administrators get in. */
    @Test
    void aFrontmatterRuleLocksTheDeletedPagesDraft() throws Exception {
        final Session bob = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );
        final String text = FrontmatterWriter.write( Map.of( "acl", "[{ALLOW edit Bob}]" ), "body\n" );

        assertFalse( new WikiCollabPermissions( engine ).mayAccessAsIf( bob, "GonePage", text ) );
    }

    @Test
    void anAdministratorStillPassesAFrontmatterLock() throws Exception {
        final Session admin = WikiSessionTest.adminSession( engine );
        final String text = FrontmatterWriter.write( Map.of( "acl", "[{ALLOW edit Bob}]" ), "body\n" );

        assertTrue( new WikiCollabPermissions( engine ).mayAccessAsIf( admin, "GonePage", text ) );
    }

    /** A rule inside an Obsidian comment never silently grants: the page locks, as a live page does. */
    @Test
    void aCommentedOutRuleLocksAsOnALivePage() throws Exception {
        final Session bob = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );

        assertFalse( new WikiCollabPermissions( engine ).mayAccessAsIf( bob, "GonePage",
            "%% [{ALLOW edit Bob}] %%\nbody\n" ) );
        assertTrue( new WikiCollabPermissions( engine ).mayAccessAsIf( bob, "GonePage", "%% just a note %%\nbody\n" ) );
    }

    @Test
    void aGroupRuleAdmitsTheGroupsMembers() throws Exception {
        final Session bob = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group group = groups.parseGroup( "CollabGuardGroup", "Bob", true );
        groups.setGroup( bob, group );
        try {
            final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );
            assertTrue( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW edit CollabGuardGroup}]\nbody" ) );
            assertFalse( permissions.mayAccessAsIf( bob, "GonePage", "[{ALLOW edit TV}]\nbody" ),
                "a group bob is not in" );
        } finally {
            groups.removeGroup( "CollabGuardGroup" );
        }
    }
}

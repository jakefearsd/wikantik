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

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group membership and user-targeted page ACLs identify a user by login name. Another user's
 * full name or wiki name, which are editable profile data, must never satisfy them. Real engine,
 * real group manager, real login.
 */
class DisplayNameMembershipTest {

    private static final String PASSWORD = "correct-horse-battery-staple-2";

    private TestEngine engine;
    private AuthorizationManager authz;
    private GroupManager groups;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        authz = engine.getManager( AuthorizationManager.class );
        groups = engine.getManager( GroupManager.class );
        saveUser( "boss", "The Boss", null );
        final Group admins = groups.parseGroup( "Admin", "boss", true );
        groups.setGroup( WikiSessionTest.adminSession( engine ), admins );
    }

    @AfterEach
    void tearDown() throws Exception {
        groups.removeGroup( "Admin" );
        engine.stop();
    }

    private void saveUser( final String login, final String fullName, final String wikiName ) throws Exception {
        final UserManager um = engine.getManager( UserManager.class );
        final UserProfile profile = um.getUserDatabase().newProfile();
        profile.setLoginName( login );
        profile.setFullname( fullName );
        if ( wikiName != null ) {
            profile.setWikiName( wikiName );
        }
        profile.setEmail( login + "@example.test" );
        profile.setPassword( PASSWORD );
        um.getUserDatabase().save( profile );
    }

    private Session login( final String login ) throws Exception {
        return WikiSessionTest.authenticatedSession( engine, login, PASSWORD );
    }

    private boolean inAdminGroup( final Session s ) {
        return Arrays.asList( s.getRoles() ).contains( new GroupPrincipal( "Admin" ) );
    }

    @Test
    void groupMemberByLoginNameIsMember() throws Exception {
        final Session boss = login( "boss" );
        assertTrue( inAdminGroup( boss ) );
        assertTrue( authz.checkPermission( boss, new AllPermission( engine.getApplicationName() ) ) );
    }

    @Test
    void fullNameEqualToMemberLoginDoesNotGrantMembership() throws Exception {
        saveUser( "mallory", "boss", "MalloryW" );
        final Session mallory = login( "mallory" );
        assertFalse( inAdminGroup( mallory ), "a full name must not satisfy group membership" );
        assertFalse( authz.checkPermission( mallory, new AllPermission( engine.getApplicationName() ) ) );
        assertFalse( groups.isUserInRole( mallory, new GroupPrincipal( "Admin" ) ) );
    }

    @Test
    void wikiNameEqualToMemberLoginDoesNotGrantMembership() throws Exception {
        saveUser( "mallory2", "Mallory Two", "boss" );
        final Session mallory = login( "mallory2" );
        assertFalse( inAdminGroup( mallory ), "a wiki name must not satisfy group membership" );
        assertFalse( authz.checkPermission( mallory, new AllPermission( engine.getApplicationName() ) ) );
    }

    @Test
    void pageAclNamingALoginIsNotSatisfiedByAnotherUsersFullName() throws Exception {
        engine.saveText( "BossOnlyPage", "[{ALLOW edit boss}]\nsecret" );
        final Page page = engine.getManager( PageManager.class ).getPage( "BossOnlyPage" );

        assertTrue( authz.checkPermission( login( "boss" ), PermissionFactory.getPagePermission( page, "edit" ) ) );

        saveUser( "mallory3", "boss", "MalloryThree" );
        assertFalse( authz.checkPermission( login( "mallory3" ), PermissionFactory.getPagePermission( page, "edit" ) ),
                "an ACL entry resolved to a login name must not match another user's full name" );
    }
}

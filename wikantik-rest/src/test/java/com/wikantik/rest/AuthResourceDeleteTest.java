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
package com.wikantik.rest;

import com.wikantik.TestEngine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

import jakarta.servlet.ServletConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuthResource#isLastAdmin(com.wikantik.api.core.Engine, String)}.
 * <p>
 * Covers Task 2: lockout-safe guard that prevents the last admin from self-deleting.
 * <p>
 * All tests inject mock GroupManager and UserManager so they do NOT read or write
 * the shared on-disk XML database files. This avoids cross-test file-system pollution
 * in the parallel test JVM (other test classes create real TestEngine instances that
 * mutate userdatabase.xml / groupdatabase.xml in target/test-classes).
 */
class AuthResourceDeleteTest {

    /**
     * The bootstrap admin login name used by the standard Wikantik setup.
     */
    private static final String BOOTSTRAP_ADMIN = "admin";

    private TestEngine engine;
    private AuthResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AuthResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.stop();
        }
    }

    // ----- helpers -----

    /**
     * Installs mock GroupManager and UserManager into the engine so that
     * isLastAdmin uses the supplied in-memory state instead of the shared XML files.
     */
    private void installMocks( final GroupManager mockGm, final UserDatabase mockDb ) {
        final UserManager mockUm = Mockito.mock( UserManager.class );
        Mockito.when( mockUm.getUserDatabase() ).thenReturn( mockDb );
        ( (com.wikantik.WikiEngine) engine ).setManager( GroupManager.class, mockGm );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, mockUm );
    }

    /**
     * Builds a mock Group with {@link Group#members()} returning principals for
     * the given wiki names. Uses real {@link WikiPrincipal} objects (not mocks)
     * to avoid Mockito's unfinished-stubbing detector.
     * <p>
     * IMPORTANT: the Group mock must be fully configured BEFORE any outer
     * {@code Mockito.when(...)} call that returns it as a value — calling
     * {@code Mockito.mock()} inside a {@code thenReturn()} argument breaks
     * Mockito's stubbing recorder.
     */
    private static Group buildAdminGroup( final String... wikiNames ) {
        final Principal[] members = new Principal[ wikiNames.length ];
        for ( int i = 0; i < wikiNames.length; i++ ) {
            members[i] = new WikiPrincipal( wikiNames[i] );
        }
        // Create and configure the mock BEFORE any outer Mockito.when() call.
        final Group group = Mockito.mock( Group.class );
        Mockito.when( group.members() ).thenReturn( members );
        return group;
    }

    /**
     * Creates a mock UserProfile with the given login name.
     * Must be built BEFORE any outer {@code Mockito.when(...)} call.
     */
    private static UserProfile buildProfile( final String loginName ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getLoginName() ).thenReturn( loginName );
        return p;
    }

    // ----- tests -----

    /**
     * With only the bootstrap admin present in the Admin group,
     * isLastAdmin must return true for that admin's login name.
     */
    @Test
    void lastRemainingAdminIsDetected() throws Exception {
        // Pre-build mocks before any Mockito.when() wiring
        final Group adminGroup    = buildAdminGroup( "Administrator" );
        final UserProfile profile = buildProfile( BOOTSTRAP_ADMIN );

        final GroupManager mockGm = Mockito.mock( GroupManager.class );
        Mockito.when( mockGm.getGroup( "Admin" ) ).thenReturn( adminGroup );

        final UserDatabase mockDb = Mockito.mock( UserDatabase.class );
        Mockito.when( mockDb.findByWikiName( "Administrator" ) ).thenReturn( profile );

        installMocks( mockGm, mockDb );

        assertTrue(
            AuthResource.isLastAdmin( engine, BOOTSTRAP_ADMIN ),
            "Expected isLastAdmin=true for the only admin '" + BOOTSTRAP_ADMIN + "'"
        );
    }

    /**
     * A user who is not in the Admin group must not be considered the last admin.
     */
    @Test
    void nonAdminIsNotLastAdmin() throws Exception {
        final Group adminGroup    = buildAdminGroup( "Administrator" );
        final UserProfile profile = buildProfile( BOOTSTRAP_ADMIN );

        final GroupManager mockGm = Mockito.mock( GroupManager.class );
        Mockito.when( mockGm.getGroup( "Admin" ) ).thenReturn( adminGroup );

        final UserDatabase mockDb = Mockito.mock( UserDatabase.class );
        Mockito.when( mockDb.findByWikiName( "Administrator" ) ).thenReturn( profile );

        installMocks( mockGm, mockDb );

        assertFalse(
            AuthResource.isLastAdmin( engine, "plainuser" ),
            "Expected isLastAdmin=false for a non-admin user"
        );
    }

    /**
     * When there are two admins, neither is the last admin.
     */
    @Test
    void withTwoAdminsNeitherIsLast() throws Exception {
        final Group adminGroup      = buildAdminGroup( "Administrator", "SecondAdminT2" );
        final UserProfile profile1  = buildProfile( BOOTSTRAP_ADMIN );
        final UserProfile profile2  = buildProfile( "secondadmin_t2" );

        final GroupManager mockGm = Mockito.mock( GroupManager.class );
        Mockito.when( mockGm.getGroup( "Admin" ) ).thenReturn( adminGroup );

        final UserDatabase mockDb = Mockito.mock( UserDatabase.class );
        Mockito.when( mockDb.findByWikiName( "Administrator" ) ).thenReturn( profile1 );
        Mockito.when( mockDb.findByWikiName( "SecondAdminT2" ) ).thenReturn( profile2 );

        installMocks( mockGm, mockDb );

        assertFalse(
            AuthResource.isLastAdmin( engine, BOOTSTRAP_ADMIN ),
            "Expected isLastAdmin=false for bootstrap admin when a second admin exists"
        );
        assertFalse(
            AuthResource.isLastAdmin( engine, "secondadmin_t2" ),
            "Expected isLastAdmin=false for second admin when bootstrap admin also exists"
        );
    }

    /**
     * If the Admin group has a member whose wiki name has no matching user profile
     * (e.g. stale entry), that entry is skipped. The resolvable admin is still
     * correctly identified as the last admin.
     */
    @Test
    void staleGroupMemberIsSkipped() throws Exception {
        final Group adminGroup    = buildAdminGroup( "Administrator", "DeletedUser" );
        final UserProfile profile = buildProfile( BOOTSTRAP_ADMIN );

        final GroupManager mockGm = Mockito.mock( GroupManager.class );
        Mockito.when( mockGm.getGroup( "Admin" ) ).thenReturn( adminGroup );

        final UserDatabase mockDb = Mockito.mock( UserDatabase.class );
        Mockito.when( mockDb.findByWikiName( "Administrator" ) ).thenReturn( profile );
        Mockito.when( mockDb.findByWikiName( "DeletedUser" ) )
            .thenThrow( new NoSuchPrincipalException( "not found" ) );

        installMocks( mockGm, mockDb );

        // "DeletedUser" is stale — only one real admin remains
        assertTrue(
            AuthResource.isLastAdmin( engine, BOOTSTRAP_ADMIN ),
            "Expected isLastAdmin=true when the only other group member has no profile"
        );
    }

    /**
     * If an internal error occurs (e.g. Admin group not found), the method must
     * fail-safe by returning true (refuse deletion rather than risk lockout).
     */
    @Test
    void failsafeOnMissingAdminGroup() throws Exception {
        final GroupManager mockGm = Mockito.mock( GroupManager.class );
        Mockito.when( mockGm.getGroup( "Admin" ) )
            .thenThrow( new NoSuchPrincipalException( "Admin group not found" ) );

        final UserDatabase mockDb = Mockito.mock( UserDatabase.class );

        installMocks( mockGm, mockDb );

        assertTrue(
            AuthResource.isLastAdmin( engine, BOOTSTRAP_ADMIN ),
            "Expected fail-safe true when Admin group cannot be found"
        );
    }

}

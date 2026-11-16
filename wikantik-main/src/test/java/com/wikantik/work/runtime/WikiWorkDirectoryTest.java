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
package com.wikantik.work.runtime;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiWorkDirectoryTest {

    private TestEngine engine;
    private GroupManager groups;
    private Session admin;
    private WikiWorkDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        groups = engine.getManager( GroupManager.class );
        admin = engine.adminSession();
        directory = new WikiWorkDirectory( engine );
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            groups.removeGroup( "DirCrew" );
        } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
            // the group was never created by this test
        }
        engine.stop();
    }

    private void setMembers( final String members ) throws Exception {
        final Group group = groups.parseGroup( "DirCrew", members, true );
        groups.setGroup( admin, group );
    }

    @Test
    void groupsOfFollowsMembershipOnEveryCall() throws Exception {
        assertFalse( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
        setMembers( "janne" );
        assertTrue( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
        setMembers( "other" );
        assertFalse( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
    }

    @Test
    void anUnknownLoginHasNoGroupsAndDoesNotExist() throws Exception {
        setMembers( "janne" );
        assertTrue( directory.groupsOf( "nobody-here" ).isEmpty() );
        assertFalse( directory.exists( "nobody-here" ) );
        assertTrue( directory.groupsOf( null ).isEmpty() );
        assertFalse( directory.exists( null ) );
    }

    @Test
    void aKnownLoginExists() {
        assertTrue( directory.exists( "janne" ) );
    }

    @Test
    void displayNameIsThePublicNameNotTheLogin() {
        assertEquals( "Janne Jalkanen", directory.displayName( "janne" ) );
        assertNotEquals( "janne", directory.displayName( "janne" ) );
    }

    @Test
    void aDisplayNameResolvesToItsLogin() {
        assertEquals( java.util.Optional.of( "janne" ), directory.loginForDisplayName( "Janne Jalkanen" ) );
        assertEquals( java.util.Optional.of( "janne" ), directory.loginForDisplayName( " janne jalkanen " ) );
        assertTrue( directory.loginForDisplayName( "janne" ).isEmpty(), "a login is not a display name" );
    }

    @Test
    void aMentionIdRoundTripsWhenTheAccountHasOne() {
        directory.mentionId( "janne" ).ifPresent( id ->
            assertEquals( java.util.Optional.of( "janne" ), directory.loginForMentionId( id ) ) );
        assertTrue( directory.loginForMentionId( "no-such-id" ).isEmpty() );
        assertTrue( directory.mentionId( "nobody-here" ).isEmpty() );
    }

    @Test
    void aValueThatNamesNoAccountIsNeverEchoed() {
        assertEquals( com.wikantik.api.identity.PublicIdentity.UNKNOWN_CONTRIBUTOR,
            directory.displayName( "nobody-here" ) );
    }

    /** A directory whose user database fails every lookup (an outage), and whose identity knows nobody. */
    private static WikiWorkDirectory failingDirectory() throws Exception {
        final com.wikantik.WikiEngine broken = org.mockito.Mockito.mock( com.wikantik.WikiEngine.class );
        final com.wikantik.auth.UserManager users = org.mockito.Mockito.mock( com.wikantik.auth.UserManager.class );
        final com.wikantik.auth.user.UserDatabase db = org.mockito.Mockito.mock( com.wikantik.auth.user.UserDatabase.class );
        org.mockito.Mockito.when( users.getUserDatabase() ).thenReturn( db );
        org.mockito.Mockito.when( db.findByLoginName( org.mockito.ArgumentMatchers.anyString() ) )
            .thenThrow( new IllegalStateException( "test: user database unreachable" ) );
        final com.wikantik.api.identity.PublicIdentity identity =
            org.mockito.Mockito.mock( com.wikantik.api.identity.PublicIdentity.class );
        org.mockito.Mockito.when( identity.userIdForLogin( org.mockito.ArgumentMatchers.anyString() ) )
            .thenReturn( java.util.Optional.empty() );
        org.mockito.Mockito.when( broken.getAuthSubsystem() ).thenReturn( new com.wikantik.auth.subsystem.AuthSubsystem
            .Services( null, null, users, null, null, null, null, null, identity ) );
        return new WikiWorkDirectory( broken );
    }

    @Test
    void anOwnerCheckDuringAUserDatabaseOutageIsUnavailableNotGone() throws Exception {
        final WikiWorkDirectory outage = failingDirectory();
        org.junit.jupiter.api.Assertions.assertThrows( com.wikantik.work.WorkUnavailableException.class,
            () -> outage.ownerExists( "lena" ), "a failed lookup must not read as \"no such user\"" );
        assertFalse( outage.exists( "lena" ), "the admin login check keeps failing closed as unknown" );
    }

    @Test
    void theOwnerCheckAnswersPlainlyWhenTheDatabaseAnswers() {
        assertTrue( directory.ownerExists( "janne" ) );
        assertFalse( directory.ownerExists( "nobody-here" ) );
        assertFalse( directory.ownerExists( null ) );
    }

    private static String loginOf( final Session session ) {
        return session.getLoginPrincipal().getName();
    }

    @Test
    void personIsEmptyForAnUnknownLogin() {
        assertTrue( directory.person( "nobody-here" ).isEmpty() );
        assertTrue( directory.person( null ).isEmpty() );
        assertTrue( directory.person( " " ).isEmpty() );
    }

    /**
     * The contract: a sponsor is an admin exactly when that person's own signed-in session would be. Each session is
     * read before the next is opened: the test engine's sessions share one mock HTTP session.
     */
    @Test
    void personAdminFlagMatchesTheSessionAdminFlag() throws Exception {
        final String adminLogin = loginOf( admin );
        final boolean adminBySession = WikiWorkActors.actorFor( engine, admin ).admin();
        assertTrue( adminBySession, "the test needs one real admin" );
        assertEquals( adminBySession, directory.person( adminLogin ).orElseThrow().admin() );

        final Session janne = engine.janneSession();
        final String janneLogin = loginOf( janne );
        final boolean janneBySession = WikiWorkActors.actorFor( engine, janne ).admin();
        assertFalse( janneBySession );
        assertEquals( janneBySession, directory.person( janneLogin ).orElseThrow().admin() );

        setMembers( janneLogin ); // a group that grants nothing changes neither answer
        assertFalse( directory.person( janneLogin ).orElseThrow().admin() );
        assertTrue( directory.person( adminLogin ).orElseThrow().admin() );
    }

    @Test
    void personCarriesTheGroupsTheSessionActorHas() throws Exception {
        final Session janne = engine.janneSession();
        setMembers( loginOf( janne ) );
        assertEquals( WikiWorkActors.actorFor( engine, janne ).groups(),
            directory.person( loginOf( janne ) ).orElseThrow().groups() );
        assertTrue( directory.person( loginOf( janne ) ).orElseThrow().groups().contains( "DirCrew" ) );
    }

    @Test
    void personThrowsUnavailableWhenTheUserDatabaseFails() throws Exception {
        final WikiWorkDirectory outage = failingDirectory();
        org.junit.jupiter.api.Assertions.assertThrows( com.wikantik.work.WorkUnavailableException.class,
            () -> outage.person( "lena" ), "a failed lookup is neither \"no such account\" nor an account" );
    }

    @Test
    void personThrowsUnavailableWhenGroupLookupFails() throws Exception {
        final com.wikantik.WikiEngine broken = org.mockito.Mockito.mock( com.wikantik.WikiEngine.class );
        final GroupManager failing = org.mockito.Mockito.mock( GroupManager.class );
        org.mockito.Mockito.when( failing.getRoles() ).thenThrow( new IllegalStateException( "test: groups down" ) );
        final com.wikantik.api.identity.PublicIdentity identity =
            org.mockito.Mockito.mock( com.wikantik.api.identity.PublicIdentity.class );
        org.mockito.Mockito.when( identity.userIdForLogin( "lena" ) ).thenReturn( java.util.Optional.of( "u-1" ) );
        org.mockito.Mockito.when( broken.getAuthSubsystem() ).thenReturn( new com.wikantik.auth.subsystem.AuthSubsystem
            .Services( null, null, null, failing, null, null, null, null, identity ) );
        org.junit.jupiter.api.Assertions.assertThrows( com.wikantik.work.WorkUnavailableException.class,
            () -> new WikiWorkDirectory( broken ).person( "lena" ) );
    }
}

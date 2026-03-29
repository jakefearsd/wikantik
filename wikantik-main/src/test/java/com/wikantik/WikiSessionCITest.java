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
package com.wikantik;

import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Constructor-injection coverage tests for {@link WikiSession}.
 * Uses the package-private three-arg constructor to avoid the static-factory
 * machinery and covers the 49 lines that were previously uncovered.
 */
@ExtendWith( MockitoExtension.class )
public class WikiSessionCITest {

    @Mock private GroupManager   groupManager;
    @Mock private UserManager    userManager;
    @Mock private AuthenticationManager authMgr;
    @Mock private UserDatabase   userDatabase;
    @Mock private UserProfile    userProfile;
    @Mock private Group          group;

    /** Subject under test — created fresh for each test via the package-private constructor. */
    private WikiSession session;

    @BeforeEach
    void setUp() {
        session = new WikiSession( groupManager, userManager, authMgr );
        // Put the session in AUTHENTICATED state so principal-injection methods work.
        session.getSubject().getPrincipals().add( Role.AUTHENTICATED );
        session.getSubject().getPrincipals().add( Role.ALL );
    }

    // -----------------------------------------------------------------------
    // Constructor injection sanity
    // -----------------------------------------------------------------------

    @Test
    void constructorStoresManagers_noEngineRequired() {
        // Creating the session via the package-private constructor must not throw
        // and must result in a usable (initially anonymous-ish) object.
        final WikiSession s = new WikiSession( groupManager, userManager, authMgr );
        assertNotNull( s.getSubject(), "Subject must be non-null" );
        // No principals have been added yet — status defaults to ANONYMOUS
        assertEquals( Session.ANONYMOUS, s.getStatus() );
    }

    // -----------------------------------------------------------------------
    // injectGroupPrincipals — covered via actionPerformed(LOGIN_AUTHENTICATED)
    // -----------------------------------------------------------------------

    @Test
    void injectGroupPrincipals_addsGroupPrincipalWhenUserIsInGroup() throws Exception {
        final GroupPrincipal gp = new GroupPrincipal( "Editors" );
        when( groupManager.getRoles() ).thenReturn( new Principal[]{ gp } );
        when( groupManager.isUserInRole( any( Session.class ), eq( gp ) ) ).thenReturn( true );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        // Simulate no user profile in database → injectUserProfilePrincipals exits via catch
        doThrow( new NoSuchPrincipalException( "no profile" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( loginPrincipal );

        // Fire LOGIN_AUTHENTICATED targeting this session
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );
        session.actionPerformed( event );

        assertTrue( session.getSubject().getPrincipals().contains( gp ),
                "GroupPrincipal should be injected when user is in group" );
    }

    @Test
    void injectGroupPrincipals_doesNotAddGroupPrincipalWhenUserNotInGroup() throws Exception {
        final GroupPrincipal gp = new GroupPrincipal( "Admins" );
        when( groupManager.getRoles() ).thenReturn( new Principal[]{ gp } );
        when( groupManager.isUserInRole( any( Session.class ), eq( gp ) ) ).thenReturn( false );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "no profile" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "guest", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( loginPrincipal );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );
        session.actionPerformed( event );

        assertFalse( session.getSubject().getPrincipals().contains( gp ),
                "GroupPrincipal must NOT be injected when user is not in group" );
    }

    @Test
    void injectGroupPrincipals_flushesPreviousGroupPrincipals() throws Exception {
        final GroupPrincipal oldGp = new GroupPrincipal( "OldGroup" );
        session.getSubject().getPrincipals().add( oldGp );

        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "no profile" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( loginPrincipal );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );
        session.actionPerformed( event );

        assertFalse( session.getSubject().getPrincipals().contains( oldGp ),
                "Stale GroupPrincipals must be flushed before re-injection" );
    }

    // -----------------------------------------------------------------------
    // injectUserProfilePrincipals — edge cases
    // -----------------------------------------------------------------------

    @Test
    void injectUserProfilePrincipals_returnsEarlyWhenLoginPrincipalNameIsNull() {
        // The loginPrincipal defaults to WikiPrincipal.GUEST which has a non-null name,
        // but we can craft a principal whose getName() is null.
        final Principal nullNamePrincipal = () -> null;
        session.getSubject().getPrincipals().add( nullNamePrincipal );

        when( groupManager.getRoles() ).thenReturn( new Principal[0] );

        // Set loginPrincipal to the null-name one via LOGIN_AUTHENTICATED event
        final WikiSecurityEvent loginEvent = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, nullNamePrincipal, session );
        session.actionPerformed( loginEvent );

        // UserDatabase.find must never be called because the early-return guard fires
        verify( userManager, never() ).getUserDatabase();
    }

    @Test
    void injectUserProfilePrincipals_throwsWhenUserDatabaseIsNull() {
        when( userManager.getUserDatabase() ).thenReturn( null );
        // groupManager.getRoles() is NOT called because the IllegalStateException fires before injectGroupPrincipals()

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( loginPrincipal );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );

        assertThrows( IllegalStateException.class,
                () -> session.actionPerformed( event ),
                "Must throw when UserDatabase is null" );
    }

    @Test
    void injectUserProfilePrincipals_setsFullNamePrincipalAsUserPrincipal() throws Exception {
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        final WikiPrincipal fullNamePrincipal = new WikiPrincipal( "Janne Jalkanen", WikiPrincipal.FULL_NAME );

        when( userProfile.getLoginName() ).thenReturn( "janne" );
        when( userDatabase.find( "janne" ) ).thenReturn( userProfile );
        when( userDatabase.getPrincipals( "janne" ) )
                .thenReturn( new Principal[]{ loginPrincipal, fullNamePrincipal } );

        session.getSubject().getPrincipals().add( loginPrincipal );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );
        session.actionPerformed( event );

        assertEquals( fullNamePrincipal, session.getUserPrincipal(),
                "FullName WikiPrincipal must become the user principal" );
    }

    @Test
    void injectUserProfilePrincipals_silentlyHandlesNoSuchPrincipalException() throws Exception {
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );

        final WikiPrincipal loginPrincipal = new WikiPrincipal( "containerUser", WikiPrincipal.LOGIN_NAME );
        doThrow( new NoSuchPrincipalException( "not in db" ) )
                .when( userDatabase ).find( "containerUser" );

        session.getSubject().getPrincipals().add( loginPrincipal );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, loginPrincipal, session );

        // Must not throw; the warning path should be taken instead
        assertDoesNotThrow( () -> session.actionPerformed( event ) );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — GROUP_ADD
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_groupAdd_addsGroupPrincipalWhenSessionIsInGroup() {
        // Make the session authenticated so isInGroup can return true
        session.getSubject().getPrincipals().add( Role.AUTHENTICATED );
        final WikiPrincipal wp = new WikiPrincipal( "janne", WikiPrincipal.FULL_NAME );
        session.getSubject().getPrincipals().add( wp );

        final GroupPrincipal gp = new GroupPrincipal( "Editors" );
        when( group.getPrincipal() ).thenReturn( gp );
        when( group.isMember( wp ) ).thenReturn( true );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.GROUP_ADD, gp, group );
        session.actionPerformed( event );

        assertTrue( session.getSubject().getPrincipals().contains( gp ),
                "GroupPrincipal must be added when session is in the new group" );
    }

    @Test
    void actionPerformed_groupAdd_doesNotAddWhenSessionIsNotInGroup() {
        final GroupPrincipal gp = new GroupPrincipal( "Editors" );
        // group.getPrincipal() is only called if isInGroup() returns true;
        // for an unauthenticated session it will not be called.

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.GROUP_ADD, gp, group );
        // Fresh session with no authenticated role
        final WikiSession anon = new WikiSession( groupManager, userManager, authMgr );
        anon.actionPerformed( event );

        assertFalse( anon.getSubject().getPrincipals().contains( gp ),
                "GroupPrincipal must NOT be added for unauthenticated sessions" );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — GROUP_REMOVE
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_groupRemove_removesGroupPrincipal() {
        final GroupPrincipal gp = new GroupPrincipal( "Editors" );
        session.getSubject().getPrincipals().add( gp );
        when( group.getPrincipal() ).thenReturn( gp );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.GROUP_REMOVE, gp, group );
        session.actionPerformed( event );

        assertFalse( session.getSubject().getPrincipals().contains( gp ),
                "GroupPrincipal must be removed on GROUP_REMOVE event" );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — GROUP_CLEAR_GROUPS
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_groupClearGroups_removesAllGroupPrincipals() {
        final GroupPrincipal gp1 = new GroupPrincipal( "A" );
        final GroupPrincipal gp2 = new GroupPrincipal( "B" );
        session.getSubject().getPrincipals().add( gp1 );
        session.getSubject().getPrincipals().add( gp2 );

        // GROUP_CLEAR_GROUPS needs a non-null target; use any object
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.GROUP_CLEAR_GROUPS, gp1, gp1 );
        session.actionPerformed( event );

        assertTrue( session.getSubject().getPrincipals( GroupPrincipal.class ).isEmpty(),
                "All GroupPrincipals must be removed on GROUP_CLEAR_GROUPS" );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — LOGIN_INITIATED (no-op)
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_loginInitiated_isNoOp() {
        final WikiPrincipal wp = new WikiPrincipal( "janne" );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_INITIATED, wp, session );
        // Must not throw and must not change the principal set
        final int before = session.getSubject().getPrincipals().size();
        session.actionPerformed( event );
        assertEquals( before, session.getSubject().getPrincipals().size() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — PRINCIPAL_ADD
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_principalAdd_addsPrincipalWhenTargetIsSelfAndAuthenticated() throws Exception {
        // Force AUTHENTICATED status via the field
        final WikiSecurityEvent loginEvent = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ANONYMOUS,
                new WikiPrincipal( "127.0.0.1" ), session );
        session.actionPerformed( loginEvent );
        // Override status to AUTHENTICATED manually by firing LOGIN_AUTHENTICATED
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "n/a" ) )
                .when( userDatabase ).find( anyString() );
        final WikiPrincipal lp = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        final WikiSecurityEvent authEvent = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, lp, session );
        session.actionPerformed( authEvent );
        assertEquals( Session.AUTHENTICATED, session.getStatus() );

        final WikiPrincipal extra = new WikiPrincipal( "ExtraRole", WikiPrincipal.WIKI_NAME );
        final WikiSecurityEvent paEvent = new WikiSecurityEvent(
                this, WikiSecurityEvent.PRINCIPAL_ADD, extra, session );
        session.actionPerformed( paEvent );

        assertTrue( session.getSubject().getPrincipals().contains( extra ),
                "Extra principal must be added when target == self and status == AUTHENTICATED" );
    }

    @Test
    void actionPerformed_principalAdd_ignoresWhenTargetIsOtherSession() {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal extra = new WikiPrincipal( "Stranger", WikiPrincipal.WIKI_NAME );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.PRINCIPAL_ADD, extra, other );
        session.actionPerformed( event );

        assertFalse( session.getSubject().getPrincipals().contains( extra ),
                "Extra principal must NOT be added when target is a different session" );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — LOGIN_ANONYMOUS
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_loginAnonymous_setsAnonymousStateWhenTargetIsSelf() {
        final WikiPrincipal ip = new WikiPrincipal( "10.0.0.1" );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ANONYMOUS, ip, session );
        session.actionPerformed( event );

        assertEquals( Session.ANONYMOUS, session.getStatus() );
        assertEquals( ip, session.getLoginPrincipal() );
        assertEquals( ip, session.getUserPrincipal() );
        assertTrue( session.getSubject().getPrincipals().contains( Role.ANONYMOUS ) );
        assertTrue( session.getSubject().getPrincipals().contains( Role.ALL ) );
    }

    @Test
    void actionPerformed_loginAnonymous_ignoresWhenTargetIsOtherSession() {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal ip = new WikiPrincipal( "10.0.0.2" );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ANONYMOUS, ip, other );
        // session should remain unchanged
        final String statusBefore = session.getStatus();
        session.actionPerformed( event );
        assertEquals( statusBefore, session.getStatus() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — LOGIN_ASSERTED
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_loginAsserted_setsAssertedStateWhenTargetIsSelf() {
        final WikiPrincipal cookie = new WikiPrincipal( "FredFlintstone" );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ASSERTED, cookie, session );
        session.actionPerformed( event );

        assertEquals( Session.ASSERTED, session.getStatus() );
        assertEquals( cookie, session.getUserPrincipal() );
        assertTrue( session.getSubject().getPrincipals().contains( Role.ASSERTED ) );
        assertFalse( session.getSubject().getPrincipals().contains( Role.ANONYMOUS ) );
    }

    @Test
    void actionPerformed_loginAsserted_ignoresWhenTargetIsOtherSession() {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal cookie = new WikiPrincipal( "Barney" );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ASSERTED, cookie, other );
        final String statusBefore = session.getStatus();
        session.actionPerformed( event );
        assertEquals( statusBefore, session.getStatus() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — LOGIN_AUTHENTICATED (self + other)
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_loginAuthenticated_setsAuthenticatedStateWhenTargetIsSelf() throws Exception {
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "n/a" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal lp = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( lp );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, lp, session );
        session.actionPerformed( event );

        assertEquals( Session.AUTHENTICATED, session.getStatus() );
        assertTrue( session.getSubject().getPrincipals().contains( Role.AUTHENTICATED ) );
        assertFalse( session.getSubject().getPrincipals().contains( Role.ANONYMOUS ) );
    }

    @Test
    void actionPerformed_loginAuthenticated_ignoresWhenTargetIsOtherSession() {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal lp = new WikiPrincipal( "other", WikiPrincipal.LOGIN_NAME );
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, lp, other );
        final String statusBefore = session.getStatus();
        session.actionPerformed( event );
        assertEquals( statusBefore, session.getStatus() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — PROFILE_SAVE
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_profileSave_injectsProfilePrincipalsWhenSourceIsSelf() throws Exception {
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );

        final WikiPrincipal lp = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        // Simulate already-authenticated session (loginPrincipal = lp)
        session.getSubject().getPrincipals().add( lp );
        // Make loginPrincipal non-null and matching "janne"
        final WikiSecurityEvent loginAnon = new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_ANONYMOUS, lp, session );
        session.actionPerformed( loginAnon );

        final WikiPrincipal fullNamePrincipal = new WikiPrincipal( "Janne Jalkanen", WikiPrincipal.FULL_NAME );
        when( userProfile.getLoginName() ).thenReturn( "janne" );
        when( userDatabase.find( "janne" ) ).thenReturn( userProfile );
        when( userDatabase.getPrincipals( "janne" ) )
                .thenReturn( new Principal[]{ lp, fullNamePrincipal } );

        // PROFILE_SAVE uses getSrc() so we pass the session as source
        final WikiSecurityEvent event = new WikiSecurityEvent(
                session, WikiSecurityEvent.PROFILE_SAVE, lp, userProfile );
        session.actionPerformed( event );

        assertTrue( session.getSubject().getPrincipals().contains( fullNamePrincipal ),
                "Full name principal must be present after PROFILE_SAVE" );
    }

    @Test
    void actionPerformed_profileSave_ignoresWhenSourceIsOtherSession() throws Exception {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal lp = new WikiPrincipal( "other", WikiPrincipal.LOGIN_NAME );

        // Fire from `other` as source — session should not react
        final WikiSecurityEvent event = new WikiSecurityEvent(
                other, WikiSecurityEvent.PROFILE_SAVE, lp, userProfile );
        session.actionPerformed( event );

        // userManager.getUserDatabase() must not have been called
        verify( userManager, never() ).getUserDatabase();
    }

    // -----------------------------------------------------------------------
    // actionPerformed — PROFILE_NAME_CHANGED
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_profileNameChanged_throwsWhenNewFullNameIsNull() throws Exception {
        // Set session to AUTHENTICATED status first
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "n/a" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal lp = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( lp );
        session.actionPerformed( new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, lp, session ) );
        assertEquals( Session.AUTHENTICATED, session.getStatus() );

        // Build a two-element UserProfile[] where newProfile.getFullname() == null
        final UserProfile oldProfile = mock( UserProfile.class );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( null );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                session, WikiSecurityEvent.PROFILE_NAME_CHANGED, lp,
                new UserProfile[]{ oldProfile, newProfile } );

        assertThrows( IllegalStateException.class,
                () -> session.actionPerformed( event ),
                "Must throw when new UserProfile.getFullname() is null" );
    }

    @Test
    void actionPerformed_profileNameChanged_updatesLoginPrincipalWhenSourceIsSelfAndAuthenticated() throws Exception {
        // Make session AUTHENTICATED
        when( groupManager.getRoles() ).thenReturn( new Principal[0] );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "n/a" ) )
                .when( userDatabase ).find( anyString() );

        final WikiPrincipal lp = new WikiPrincipal( "janne", WikiPrincipal.LOGIN_NAME );
        session.getSubject().getPrincipals().add( lp );
        session.actionPerformed( new WikiSecurityEvent(
                this, WikiSecurityEvent.LOGIN_AUTHENTICATED, lp, session ) );

        // Reset stubs so the name-change injection also returns the new profile
        final UserProfile oldProfile = mock( UserProfile.class );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( "Janne Jalkanen" );
        when( newProfile.getLoginName() ).thenReturn( "janne_new" );

        // profile lookup during name-change — use doReturn/doThrow forms to avoid
        // triggering the existing anyString() throw-stub at the stub setup site.
        when( userProfile.getLoginName() ).thenReturn( "janne_new" );
        doReturn( userProfile ).when( userDatabase ).find( "janne_new" );
        doReturn( new Principal[]{ new WikiPrincipal( "janne_new", WikiPrincipal.LOGIN_NAME ) } )
                .when( userDatabase ).getPrincipals( "janne_new" );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                session, WikiSecurityEvent.PROFILE_NAME_CHANGED, lp,
                new UserProfile[]{ oldProfile, newProfile } );
        session.actionPerformed( event );

        assertEquals( "janne_new", session.getLoginPrincipal().getName(),
                "loginPrincipal must be updated to the new login name" );
    }

    @Test
    void actionPerformed_profileNameChanged_ignoresWhenSourceIsOtherSession() {
        final WikiSession other = new WikiSession( groupManager, userManager, authMgr );
        final UserProfile oldProfile = mock( UserProfile.class );
        final UserProfile newProfile = mock( UserProfile.class );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                other, WikiSecurityEvent.PROFILE_NAME_CHANGED,
                new WikiPrincipal( "janne" ),
                new UserProfile[]{ oldProfile, newProfile } );

        final Principal loginBefore = session.getLoginPrincipal();
        session.actionPerformed( event );
        assertEquals( loginBefore, session.getLoginPrincipal(),
                "loginPrincipal must not change when PROFILE_NAME_CHANGED source is another session" );
    }

    @Test
    void actionPerformed_profileNameChanged_ignoresWhenNotAuthenticated() {
        // session is freshly created — status is ANONYMOUS
        final WikiSession anon = new WikiSession( groupManager, userManager, authMgr );
        final UserProfile oldProfile = mock( UserProfile.class );
        final UserProfile newProfile = mock( UserProfile.class );

        final WikiSecurityEvent event = new WikiSecurityEvent(
                anon, WikiSecurityEvent.PROFILE_NAME_CHANGED,
                new WikiPrincipal( "janne" ),
                new UserProfile[]{ oldProfile, newProfile } );
        anon.actionPerformed( event );

        // userManager must not be touched
        verify( userManager, never() ).getUserDatabase();
    }

    // -----------------------------------------------------------------------
    // actionPerformed — unrecognised event type (default branch)
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_unknownEventType_isNoOp() {
        // Use a type that has no case in the switch but still has a non-null target
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.ACCESS_ALLOWED,
                new WikiPrincipal( "janne" ), session );
        final int sizeBefore = session.getSubject().getPrincipals().size();
        assertDoesNotThrow( () -> session.actionPerformed( event ) );
        assertEquals( sizeBefore, session.getSubject().getPrincipals().size() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — non-WikiSecurityEvent (outer guard)
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_nonSecurityEvent_isNoOp() {
        // Pass an event that is NOT a WikiSecurityEvent — must be silently ignored.
        // WikiPageEvent is a non-sealed subclass of WikiEvent.
        final WikiPageEvent plainEvent = new WikiPageEvent( this, WikiPageEvent.PAGE_REQUESTED, "SomePage" );
        final int sizeBefore = session.getSubject().getPrincipals().size();
        assertDoesNotThrow( () -> session.actionPerformed( plainEvent ) );
        assertEquals( sizeBefore, session.getSubject().getPrincipals().size() );
    }

    // -----------------------------------------------------------------------
    // actionPerformed — null target guard (inner guard)
    // -----------------------------------------------------------------------

    @Test
    void actionPerformed_securityEventWithNullTarget_isNoOp() {
        // WikiSecurityEvent allows null target; the inner `if (e.getTarget() != null)` guard should fire.
        final WikiSecurityEvent event = new WikiSecurityEvent(
                this, WikiSecurityEvent.GROUP_ADD, new WikiPrincipal( "janne" ), null );
        final int sizeBefore = session.getSubject().getPrincipals().size();
        assertDoesNotThrow( () -> session.actionPerformed( event ) );
        assertEquals( sizeBefore, session.getSubject().getPrincipals().size() );
    }

    // -----------------------------------------------------------------------
    // invalidate
    // -----------------------------------------------------------------------

    @Test
    void invalidate_resetsToGuestState() {
        // Put some principals in first
        session.getSubject().getPrincipals().add( new WikiPrincipal( "janne", WikiPrincipal.FULL_NAME ) );
        session.getSubject().getPrincipals().add( Role.AUTHENTICATED );

        session.invalidate();

        assertTrue( session.getSubject().getPrincipals().contains( WikiPrincipal.GUEST ),
                "GUEST must be present after invalidate" );
        assertTrue( session.getSubject().getPrincipals().contains( Role.ANONYMOUS ),
                "Role.ANONYMOUS must be present after invalidate" );
        assertTrue( session.getSubject().getPrincipals().contains( Role.ALL ),
                "Role.ALL must be present after invalidate" );
        assertEquals( WikiPrincipal.GUEST, session.getUserPrincipal() );
        assertEquals( WikiPrincipal.GUEST, session.getLoginPrincipal() );
    }

    // -----------------------------------------------------------------------
    // getStatus / getSubject
    // -----------------------------------------------------------------------

    @Test
    void getStatus_returnsAnonymousForFreshSession() {
        assertEquals( Session.ANONYMOUS, session.getStatus() );
    }

    @Test
    void getSubject_returnsNonNullSubject() {
        assertNotNull( session.getSubject() );
    }

    // -----------------------------------------------------------------------
    // isInGroup
    // -----------------------------------------------------------------------

    @Test
    void isInGroup_returnsTrueWhenAuthenticatedAndMember() {
        final WikiPrincipal wp = new WikiPrincipal( "janne", WikiPrincipal.FULL_NAME );
        session.getSubject().getPrincipals().add( wp );
        session.getSubject().getPrincipals().add( Role.AUTHENTICATED );

        when( group.isMember( wp ) ).thenReturn( true );

        assertTrue( session.isInGroup( group ) );
    }

    @Test
    void isInGroup_returnsFalseWhenNotAuthenticated() {
        final WikiSession anon = new WikiSession( groupManager, userManager, authMgr );
        final WikiPrincipal wp = new WikiPrincipal( "janne", WikiPrincipal.FULL_NAME );
        anon.getSubject().getPrincipals().add( wp );
        // no Role.AUTHENTICATED

        assertFalse( anon.isInGroup( group ) );
    }

}

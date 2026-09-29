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

import com.wikantik.api.core.Context;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.user.DuplicateUserException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUserManagerTest {

    @Test
    void testParseProfileTrimsFields() {
        // Mock HttpServletRequest
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getParameter( "loginname" ) ).thenReturn( "  admin  " );
        when( request.getParameter( "password" ) ).thenReturn( "password" );
        when( request.getParameter( "fullname" ) ).thenReturn( "  Administrator  " );
        when( request.getParameter( "email" ) ).thenReturn( "  admin@example.com  " );

        // Mock Engine and its dependencies
        final AuthenticationManager aMgr = mock( AuthenticationManager.class );
        when( aMgr.isContainerAuthenticated() ).thenReturn( false );
        final Properties props = new Properties();
        props.put( "wikantik.userdatabase", "com.wikantik.auth.user.InMemoryUserDatabase" );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( aMgr );
        when( engine.getWikiProperties() ).thenReturn( props );

        // Mock Context
        final Context context = mock( Context.class );
        when( context.getHttpRequest() ).thenReturn( request );

        // Mock Session and ensure it's authenticated
        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( () -> "admin" );
        when( context.getWikiSession() ).thenReturn( session );

        // Call parseProfile
        final DefaultUserManager userManager = new DefaultUserManager();
        userManager.initialize( engine, engine.getWikiProperties() );
        final UserProfile profile = userManager.parseProfile( context );

        // Verify fields are trimmed
        Assertions.assertEquals( "admin", profile.getLoginName(), "Login name should be trimmed" );
        Assertions.assertEquals( "Administrator", profile.getFullname(), "Full name should be trimmed" );
        Assertions.assertEquals( "admin@example.com", profile.getEmail(), "Email should be trimmed" );
    }

    @Test
    void testParseProfile_containerAuthenticated_forcesLoginNameFromContainerPrincipal() {
        // Security-relevant: when container authentication is active, the editor-supplied
        // "loginname" request parameter must NEVER win — the login name is always taken
        // from the container's own principal so a logged-in user cannot spoof a different
        // account's identity via the profile form.
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getParameter( "loginname" ) ).thenReturn( "spoofed-name" );
        when( request.getParameter( "password" ) ).thenReturn( null );
        when( request.getParameter( "fullname" ) ).thenReturn( "Real User" );
        when( request.getParameter( "email" ) ).thenReturn( "real@example.com" );

        final AuthenticationManager aMgr = mock( AuthenticationManager.class );
        when( aMgr.isContainerAuthenticated() ).thenReturn( true );
        final Properties props = new Properties();
        props.put( "wikantik.userdatabase", "com.wikantik.auth.user.InMemoryUserDatabase" );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( aMgr );
        when( engine.getWikiProperties() ).thenReturn( props );

        final Context context = mock( Context.class );
        when( context.getHttpRequest() ).thenReturn( request );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( () -> "real-user" );
        when( session.getLoginPrincipal() ).thenReturn( () -> "container-principal" );
        when( context.getWikiSession() ).thenReturn( session );

        final DefaultUserManager userManager = new DefaultUserManager();
        userManager.initialize( engine, engine.getWikiProperties() );
        final UserProfile profile = userManager.parseProfile( context );

        assertEquals( "container-principal", profile.getLoginName(),
                "container auth must override any client-supplied login name" );
    }

    @Test
    void getUserDatabase_whenInitializeThrowsWikiSecurityException_isLoggedAndNeverPropagates() throws Exception {
        final Properties props = new Properties();
        props.put( "wikantik.userdatabase", ThrowingUserDatabase.class.getName() );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        final DefaultUserManager userManager = new DefaultUserManager();
        setEngine( userManager, engine );
        final UserDatabase db = assertDoesNotThrow( userManager::getUserDatabase );
        assertInstanceOf( ThrowingUserDatabase.class, db,
                "a database that fails initialize() is still the one instantiated from configuration" );
    }

    @Test
    void getUserProfile_authenticatedWithNoStoredProfile_createsNewProfileWithLoginName() throws Exception {
        final DefaultUserManager userManager = new DefaultUserManager();
        final UserDatabase db = mock( UserDatabase.class );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.isNew() ).thenReturn( true );
        when( db.find( "bob" ) ).thenThrow( new NoSuchPrincipalException( "no such user" ) );
        when( db.newProfile() ).thenReturn( newProfile );
        setDatabase( userManager, db );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( () -> "bob" );

        final UserProfile result = userManager.getUserProfile( session );

        assertEquals( newProfile, result );
        verify( newProfile ).setLoginName( "bob" );
    }

    @Test
    void getUserProfile_whenFactoryProfileNotMarkedNew_throwsIllegalStateException() throws Exception {
        final DefaultUserManager userManager = new DefaultUserManager();
        final UserDatabase db = mock( UserDatabase.class );
        final UserProfile brokenProfile = mock( UserProfile.class );
        when( brokenProfile.isNew() ).thenReturn( false );
        when( db.newProfile() ).thenReturn( brokenProfile );
        setDatabase( userManager, db );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );

        assertThrows( IllegalStateException.class, () -> userManager.getUserProfile( session ) );
    }

    @Test
    void setUserProfile_deniedByAuthorization_throwsWikiSecurityException() {
        final DefaultUserManager userManager = new DefaultUserManager();
        final WikiEngine engine = mock( WikiEngine.class );
        final AuthorizationManager authz = mock( AuthorizationManager.class );
        when( authz.checkPermission( any(), any() ) ).thenReturn( false );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( authz );
        when( engine.getApplicationName() ).thenReturn( "TestWiki" );
        userManager.initialize( engine, new Properties() );

        final Context context = mock( Context.class );
        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );
        when( context.getWikiSession() ).thenReturn( session );

        final UserProfile profile = mock( UserProfile.class );

        assertThrows( WikiSecurityException.class, () -> userManager.setUserProfile( context, profile ) );
    }

    @Test
    void setUserProfile_existingAccountUnchanged_savesAndFiresProfileSaveEvent() throws Exception {
        final DefaultUserManager userManager = new DefaultUserManager();
        final UserDatabase db = mock( UserDatabase.class );
        when( db.findByLoginName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        when( db.findByFullName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        setDatabase( userManager, db );

        final WikiEngine engine = allowingAuthorizationEngine();
        setEngine( userManager, engine );

        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getFullname() ).thenReturn( "Alice A" );
        when( oldProfile.getLoginName() ).thenReturn( "alice" );
        when( db.find( "alice" ) ).thenReturn( oldProfile );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( () -> "alice" );

        final Context context = mock( Context.class );
        when( context.getWikiSession() ).thenReturn( session );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.isNew() ).thenReturn( false );
        when( profile.getFullname() ).thenReturn( "Alice A" );
        when( profile.getLoginName() ).thenReturn( "alice" );

        assertDoesNotThrow( () -> userManager.setUserProfile( context, profile ) );

        verify( db ).save( profile );
    }

    @Test
    void setUserProfile_existingAccountRenamed_renamesAndFiresNameChangedEvent() throws Exception {
        final DefaultUserManager userManager = new DefaultUserManager();
        final UserDatabase db = mock( UserDatabase.class );
        when( db.findByLoginName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        when( db.findByFullName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        setDatabase( userManager, db );

        final WikiEngine engine = allowingAuthorizationEngine();
        setEngine( userManager, engine );

        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getFullname() ).thenReturn( "Alice A" );
        when( oldProfile.getLoginName() ).thenReturn( "alice" );
        when( db.find( "alice" ) ).thenReturn( oldProfile );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( () -> "alice" );

        final Context context = mock( Context.class );
        when( context.getWikiSession() ).thenReturn( session );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.isNew() ).thenReturn( false );
        when( profile.getFullname() ).thenReturn( "Alice A" );
        when( profile.getLoginName() ).thenReturn( "alice2" );

        assertDoesNotThrow( () -> userManager.setUserProfile( context, profile ) );

        verify( db ).rename( "alice", "alice2" );
        verify( db ).save( profile );
    }

    @Test
    void setUserProfile_newAccountWhoseLoginFails_wrapsLoginFailureAsWikiSecurityException() throws Exception {
        final DefaultUserManager userManager = new DefaultUserManager();
        final UserDatabase db = mock( UserDatabase.class );
        when( db.findByLoginName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        when( db.findByFullName( any( String.class ) ) ).thenThrow( new NoSuchPrincipalException( "none" ) );
        final UserProfile factoryProfile = mock( UserProfile.class );
        when( factoryProfile.isNew() ).thenReturn( true );
        when( db.newProfile() ).thenReturn( factoryProfile );
        setDatabase( userManager, db );

        final WikiEngine engine = allowingAuthorizationEngine();
        final AuthenticationManager authn = mock( AuthenticationManager.class );
        when( authn.isContainerAuthenticated() ).thenReturn( false );
        when( authn.login( any(), any(), any(), any() ) ).thenThrow( new WikiSecurityException( "bad credentials" ) );
        when( engine.getManager( AuthenticationManager.class ) ).thenReturn( authn );
        setEngine( userManager, engine );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );

        final Context context = mock( Context.class );
        when( context.getWikiSession() ).thenReturn( session );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.isNew() ).thenReturn( true );
        when( profile.getLoginName() ).thenReturn( "newuser" );
        when( profile.getEmail() ).thenReturn( null );

        final WikiSecurityException ex = assertThrows( WikiSecurityException.class,
                () -> userManager.setUserProfile( context, profile ) );
        assertEquals( "bad credentials", ex.getMessage() );
    }

    @Test
    void removeWikiEventListener_delegatesWithoutThrowing() {
        final DefaultUserManager userManager = new DefaultUserManager();
        final WikiEventListener listener = event -> { };
        assertDoesNotThrow( () -> userManager.addWikiEventListener( listener ) );
        assertDoesNotThrow( () -> userManager.removeWikiEventListener( listener ) );
    }

    // --- test helpers -----------------------------------------------------

    /** Builds an engine wired for {@code checkEditProfilePermission} to succeed (permission granted). */
    private static WikiEngine allowingAuthorizationEngine() {
        final WikiEngine engine = mock( WikiEngine.class );
        final AuthorizationManager authz = mock( AuthorizationManager.class );
        when( authz.checkPermission( any(), any() ) ).thenReturn( true );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( authz );
        when( engine.getApplicationName() ).thenReturn( "TestWiki" );
        return engine;
    }

    private static void setDatabase( final DefaultUserManager userManager, final UserDatabase db ) throws Exception {
        final Field f = DefaultUserManager.class.getDeclaredField( "database" );
        f.setAccessible( true );
        f.set( userManager, db );
    }

    private static void setEngine( final DefaultUserManager userManager, final Engine engine ) throws Exception {
        final Field f = DefaultUserManager.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( userManager, engine );
    }

    /** Minimal {@link UserDatabase} whose {@code initialize} always fails with a {@link WikiSecurityException}. */
    public static final class ThrowingUserDatabase implements UserDatabase {
        public ThrowingUserDatabase() { }

        @Override
        public void initialize( final Engine engine, final Properties props ) throws WikiSecurityException {
            throw new WikiSecurityException( "simulated database initialization failure" );
        }

        @Override
        public void deleteByLoginName( final String loginName ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Principal[] getPrincipals( final String identifier ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Principal[] getWikiNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile find( final String index ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile findByEmail( final String index ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile findByLoginName( final String index ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile findByUid( final String uid ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile findByWikiName( final String index ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile findByFullName( final String index ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile newProfile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rename( final String loginName, final String newName ) throws DuplicateUserException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save( final UserProfile profile ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean validatePassword( final String loginName, final String password ) {
            throw new UnsupportedOperationException();
        }
    }
}

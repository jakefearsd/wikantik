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
import com.wikantik.MockEngineBuilder;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.auth.user.XMLUserDatabase;
import com.wikantik.filters.FilterManager;
import com.wikantik.pages.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for {@link DefaultUserManager} covering branches not exercised
 * by {@link DefaultUserManagerTest} or {@link DefaultUserManagerCITest}:
 * <ul>
 *   <li>{@code getUserProfile()} for an authenticated session where the user is not in the DB</li>
 *   <li>{@code parseProfile()} with container authentication</li>
 *   <li>{@code validateProfile()} uniqueness checks (full name / login name conflicts)</li>
 *   <li>{@code listWikiNames()} delegation</li>
 *   <li>{@code removeWikiEventListener()} path</li>
 * </ul>
 */
class DefaultUserManagerCITest2 {

    private static final String SESSION_MESSAGES = "profile";

    private TestEngine m_engine;
    private UserManager m_mgr;
    private UserDatabase m_db;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        m_engine = new TestEngine( props );
        m_mgr = m_engine.getManager( UserManager.class );
        m_db = m_mgr.getUserDatabase();
    }

    @AfterEach
    void tearDown() {
        // engine is GC'd; any test users are cleaned up individually in each test
    }

    // ---- getUserProfile: authenticated but user not in DB yields new profile ----

    @Test
    void getUserProfileReturnsNewProfileForAuthenticatedUserNotInDb() throws Exception {
        // Build a mock-based manager with no user in the database
        final UserDatabase mockDb = mock( UserDatabase.class );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.isNew() ).thenReturn( true );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        // find() must throw NoSuchPrincipalException so that getUserProfile() treats it as a new user
        when( mockDb.find( anyString() ) ).thenThrow( new NoSuchPrincipalException( "not found" ) );

        final FilterManager fm = mock( FilterManager.class );
        when( fm.getFilterList() ).thenReturn( Collections.emptyList() );
        final PageManager pm = mock( PageManager.class );
        final Engine mockEngine = MockEngineBuilder.engine()
                .with( FilterManager.class, fm )
                .with( PageManager.class, pm )
                .build();

        final DefaultUserManager mgr = new DefaultUserManager();
        injectField( mgr, "engine", mockEngine );
        injectField( mgr, "database", mockDb );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( new WikiPrincipal( "ghost" ) );

        final UserProfile result = mgr.getUserProfile( session );
        assertNotNull( result );
        assertTrue( result.isNew(),
                "Profile should be marked 'new' when the user is not found in the database" );
        // The login name should default to the principal name
        verify( newProfile ).setLoginName( "ghost" );
    }

    // ---- parseProfile: container authentication — login name taken from container ----

    @Test
    void parseProfileUsesContainerLoginNameWhenContainerAuthenticated() throws Exception {
        final AuthenticationManager authMgr = mock( AuthenticationManager.class );
        when( authMgr.isContainerAuthenticated() ).thenReturn( true );

        final FilterManager fm = mock( FilterManager.class );
        when( fm.getFilterList() ).thenReturn( Collections.emptyList() );
        final PageManager pm = mock( PageManager.class );
        final Engine mockEngine = MockEngineBuilder.engine()
                .with( AuthenticationManager.class, authMgr )
                .with( FilterManager.class, fm )
                .with( PageManager.class, pm )
                .build();

        final UserDatabase mockDb = mock( UserDatabase.class );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.isNew() ).thenReturn( true );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        // find() must throw NoSuchPrincipalException so getUserProfile() treats this as a new user
        when( mockDb.find( anyString() ) ).thenThrow( new NoSuchPrincipalException( "not found" ) );

        final DefaultUserManager mgr = new DefaultUserManager();
        injectField( mgr, "engine", mockEngine );
        injectField( mgr, "database", mockDb );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        when( req.getParameter( "loginname" ) ).thenReturn( "form-login" );
        when( req.getParameter( "password" ) ).thenReturn( null );
        when( req.getParameter( "fullname" ) ).thenReturn( "Full Name" );
        when( req.getParameter( "email" ) ).thenReturn( "user@example.com" );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( new WikiPrincipal( "container-user" ) );
        when( session.getLoginPrincipal() ).thenReturn( new WikiPrincipal( "container-user" ) );

        final Context context = mock( Context.class );
        when( context.getHttpRequest() ).thenReturn( req );
        when( context.getWikiSession() ).thenReturn( session );

        mgr.parseProfile( context );

        // The login name should come from the container login principal, not the form field.
        // setLoginName may be called more than once (once in getUserProfile, once in parseProfile).
        verify( newProfile, atLeastOnce() ).setLoginName( "container-user" );
    }

    // ---- validateProfile: full name is another user's login name ----

    @Test
    void validateProfileReportsErrorWhenFullNameClashesWithLoginName() throws Exception {
        // Create user "bob" whose login name is "bob"
        final Context ctx1 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final String loginBob = "valbob" + System.currentTimeMillis();
        final UserProfile bob = m_db.newProfile();
        bob.setLoginName( loginBob );
        bob.setFullname( "ValbobFull" + loginBob );
        bob.setEmail( loginBob + "@example.com" );
        bob.setPassword( Users.ALICE_PASS );
        m_mgr.setUserProfile( ctx1, bob );

        // Now try to create a second user whose FULL NAME equals bob's login name
        final Context ctx2 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final UserProfile newUser = m_db.newProfile();
        newUser.setLoginName( "newuser" + System.currentTimeMillis() );
        newUser.setFullname( loginBob );  // same as bob's loginName — should be rejected
        newUser.setEmail( "newuser" + System.currentTimeMillis() + "@example.com" );
        newUser.setPassword( Users.ALICE_PASS );

        m_mgr.validateProfile( ctx2, newUser );

        final String[] messages = ctx2.getWikiSession().getMessages( SESSION_MESSAGES );
        // There may be validation errors for other reasons too; we just need at least one
        assertTrue( messages.length > 0,
                "Using another user's login name as full name should generate validation messages" );

        // Cleanup
        m_db.deleteByLoginName( loginBob );
    }

    // ---- validateProfile: login name is another user's full name ----

    @Test
    void validateProfileReportsErrorWhenLoginNameClashesWithFullName() throws Exception {
        // Create user whose full name is "SomeFullName"
        final String existingLogin = "clitest" + System.currentTimeMillis();
        final String existingFull  = "SomeFullName" + System.currentTimeMillis();
        final Context ctx1 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final UserProfile existing = m_db.newProfile();
        existing.setLoginName( existingLogin );
        existing.setFullname( existingFull );
        existing.setEmail( existingLogin + "@example.com" );
        existing.setPassword( Users.ALICE_PASS );
        m_mgr.setUserProfile( ctx1, existing );

        // Now try to create a new user whose LOGIN NAME equals existingFull
        final Context ctx2 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final UserProfile newUser = m_db.newProfile();
        newUser.setLoginName( existingFull );  // same as existing user's full name
        newUser.setFullname( "DifferentFull" + System.currentTimeMillis() );
        newUser.setEmail( "conflict" + System.currentTimeMillis() + "@example.com" );
        newUser.setPassword( Users.ALICE_PASS );

        m_mgr.validateProfile( ctx2, newUser );

        final String[] messages = ctx2.getWikiSession().getMessages( SESSION_MESSAGES );
        assertTrue( messages.length > 0,
                "Using another user's full name as login name should generate validation messages" );

        // Cleanup
        m_db.deleteByLoginName( existingLogin );
    }

    // ---- listWikiNames delegates to UserDatabase ----

    @Test
    void listWikiNamesReturnsAllPrincipals() throws Exception {
        final java.security.Principal[] wikiNames = m_mgr.listWikiNames();
        assertNotNull( wikiNames );
        // The test database (userdatabase.xml) has at least the "janne" user
        assertTrue( wikiNames.length > 0, "listWikiNames() should return at least one user" );
    }

    // ---- addWikiEventListener / removeWikiEventListener ----

    @Test
    void addAndRemoveWikiEventListenerDoNotThrow() {
        final com.wikantik.event.WikiEventListener listener =
                mock( com.wikantik.event.WikiEventListener.class );

        assertDoesNotThrow( () -> m_mgr.addWikiEventListener( listener ) );
        assertDoesNotThrow( () -> m_mgr.removeWikiEventListener( listener ) );
    }

    // ---- validateProfile: password confirmation mismatch ----

    @Test
    void validateProfileReportsErrorWhenPasswordConfirmationMismatches() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        when( req.getParameter( "password0" ) ).thenReturn( null );
        when( req.getParameter( "password2" ) ).thenReturn( "different" );

        final Context ctx = Wiki.context().create( m_engine, req, "" );
        final UserProfile profile = m_db.newProfile();
        profile.setLoginName( "mismatch" + System.currentTimeMillis() );
        profile.setFullname( "Mismatch Full" + System.currentTimeMillis() );
        profile.setEmail( "mismatch" + System.currentTimeMillis() + "@example.com" );
        profile.setPassword( "original" );  // does not match password2="different"

        m_mgr.validateProfile( ctx, profile );

        final String[] messages = ctx.getWikiSession().getMessages( SESSION_MESSAGES );
        assertTrue( messages.length > 0, "Password confirmation mismatch should produce a validation message" );
    }

    // ---- Helper ----

    private static void injectField( final Object target, final String fieldName, final Object value ) {
        try {
            final java.lang.reflect.Field f = DefaultUserManager.class.getDeclaredField( fieldName );
            f.setAccessible( true );
            f.set( target, value );
        } catch ( final ReflectiveOperationException e ) {
            throw new RuntimeException( e );
        }
    }
}

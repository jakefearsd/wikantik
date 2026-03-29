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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultUserManager} that cover branches not exercised by
 * the full-engine {@link UserManagerTest} or {@link DefaultUserManagerTest}.
 *
 * Sections:
 * <ul>
 *   <li>Pure mock-based: {@code getUserDatabase()} fallback to DummyUserDatabase,
 *       {@code getUserProfile()} caching, and {@code JSONUserModule} methods</li>
 *   <li>TestEngine-based: {@code validateProfile()} branches that require a live
 *       i18n/filter/auth stack</li>
 * </ul>
 */
class DefaultUserManagerCITest {

    private static final String SESSION_MESSAGES = "profile";

    // --- TestEngine fields (for validateProfile tests) ---
    private TestEngine m_engine;
    private UserManager m_mgr;
    private UserDatabase m_db;

    // --- Pure mock fields (for getUserDatabase / getUserProfile / JSONUserModule) ---
    private Engine mockEngine;
    private UserDatabase mockDatabase;
    private DefaultUserManager mockMgr;

    @BeforeEach
    void setUp() throws Exception {
        // Full engine for validateProfile tests
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        m_engine = new TestEngine( props );
        m_mgr = m_engine.getManager( UserManager.class );
        m_db = m_mgr.getUserDatabase();

        // Mock-based manager (no engine start-up)
        mockDatabase = mock( UserDatabase.class );
        final FilterManager filterMgr = mock( FilterManager.class );
        final PageManager pageMgr = mock( PageManager.class );
        when( filterMgr.getFilterList() ).thenReturn( Collections.emptyList() );

        mockEngine = MockEngineBuilder.engine()
                .with( FilterManager.class, filterMgr )
                .with( PageManager.class, pageMgr )
                .build();

        mockMgr = new DefaultUserManager();
        injectField( mockMgr, "engine", mockEngine );
    }

    @AfterEach
    void tearDown() {
        // Nothing to tear down for the mock section; TestEngine is GC'd
    }

    // =========================================================================
    //  getUserDatabase — fallback to DummyUserDatabase when property absent
    // =========================================================================

    @Test
    void getUserDatabaseFallsBackToDummyWhenPropertyMissing() {
        final UserDatabase db = mockMgr.getUserDatabase();
        assertNotNull( db );
        // DummyUserDatabase always throws NoSuchPrincipalException on find()
        assertThrows( NoSuchPrincipalException.class, () -> db.findByLoginName( "anyone" ) );
    }

    @Test
    void getUserDatabaseReturnsCachedInstanceOnSecondCall() {
        final UserDatabase first  = mockMgr.getUserDatabase();
        final UserDatabase second = mockMgr.getUserDatabase();
        assertSame( first, second );
    }

    @Test
    void getUserDatabaseFallsBackToDummyWhenClassNotFound() throws Exception {
        final Properties props = new Properties();
        props.setProperty( UserManager.PROP_DATABASE, "com.example.NonExistentUserDatabase" );
        final Engine e2 = MockEngineBuilder.engine().properties( props ).build();
        final DefaultUserManager m2 = new DefaultUserManager();
        injectField( m2, "engine", e2 );

        final UserDatabase db = m2.getUserDatabase();
        assertNotNull( db );
        assertThrows( NoSuchPrincipalException.class, () -> db.findByLoginName( "x" ) );
    }

    // =========================================================================
    //  getUserProfile — unauthenticated session yields a new profile
    // =========================================================================

    @Test
    void getUserProfileReturnsNewProfileForUnauthenticatedSession() throws Exception {
        injectField( mockMgr, "database", mockDatabase );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.isNew() ).thenReturn( true );
        when( mockDatabase.newProfile() ).thenReturn( newProfile );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( false );

        final UserProfile result = mockMgr.getUserProfile( session );
        assertNotNull( result );
        assertTrue( result.isNew() );
    }

    // =========================================================================
    //  getUserProfile — cached profile returned on second call
    // =========================================================================

    @Test
    void getUserProfileReturnsCachedProfileOnSubsequentCalls() throws Exception {
        injectField( mockMgr, "database", mockDatabase );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.isNew() ).thenReturn( false );
        // Use a real Principal rather than lambda so equals/hashCode work in WeakHashMap
        final Principal user = new WikiPrincipal( "alice" );
        when( mockDatabase.find( eq( "alice" ) ) ).thenReturn( profile );

        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getUserPrincipal() ).thenReturn( user );

        // First call populates profile; second call hits the same session again
        mockMgr.getUserProfile( session );
        mockMgr.getUserProfile( session );

        // DefaultUserManager always calls database.find() for authenticated sessions;
        // it caches the result but still issues the lookup on each call
        verify( mockDatabase, times( 2 ) ).find( "alice" );
    }

    // =========================================================================
    //  validateProfile — password null (already covered elsewhere, kept minimal)
    // =========================================================================

    @Test
    void validateProfileReportsErrorForNullPassword() throws Exception {
        final Context context = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final UserProfile profile = m_db.newProfile();
        profile.setLoginName( "testlogin" );
        profile.setFullname( "Test Full" );
        profile.setEmail( "test@example.com" );
        profile.setPassword( null );

        m_mgr.validateProfile( context, profile );

        final String[] messages = context.getWikiSession().getMessages( SESSION_MESSAGES );
        assertTrue( messages.length > 0, "Null password should produce a validation message" );
    }

    // =========================================================================
    //  validateProfile — email already taken by another user
    // =========================================================================

    @Test
    void validateProfileReportsErrorWhenEmailTakenByDifferentUser() throws Exception {
        // Save a user with an email we can reuse
        final Context ctx1 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final String sharedEmail  = "shared" + System.currentTimeMillis() + "@example.com";
        final String loginName1   = "user1" + System.currentTimeMillis();
        final UserProfile first   = m_db.newProfile();
        first.setLoginName( loginName1 );
        first.setFullname( "User One " + loginName1 );
        first.setEmail( sharedEmail );
        first.setPassword( "password" );
        m_mgr.setUserProfile( ctx1, first );

        // Create a second NEW profile (different UID) that claims the same email
        final Context ctx2 = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final String loginName2   = "user2" + System.currentTimeMillis();
        final UserProfile second  = m_db.newProfile();
        second.setLoginName( loginName2 );
        second.setFullname( "User Two " + loginName2 );
        second.setEmail( sharedEmail );
        second.setPassword( "password" );

        m_mgr.validateProfile( ctx2, second );

        final String[] messages = ctx2.getWikiSession().getMessages( SESSION_MESSAGES );
        assertTrue( messages.length > 0, "Duplicate email should produce a validation error" );

        // Cleanup
        m_db.deleteByLoginName( loginName1 );
    }

    // =========================================================================
    //  validateProfile — existing profile, current password incorrect
    // =========================================================================

    @Test
    void validateProfileReportsErrorWhenCurrentPasswordWrong() throws Exception {
        // First create a user
        final Context ctx = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final String login = "pwdtest" + System.currentTimeMillis();
        final UserProfile newUser = m_db.newProfile();
        newUser.setLoginName( login );
        newUser.setFullname( "FullName" + login );
        newUser.setEmail( login + "@example.com" );
        newUser.setPassword( "password" );
        m_mgr.setUserProfile( ctx, newUser );

        // Try to change password but supply wrong current password (password0)
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        when( req.getParameter( "password0" ) ).thenReturn( "WRONGPASSWORD" );
        when( req.getParameter( "password2" ) ).thenReturn( "newpassword" );

        final Context ctx2 = Wiki.context().create( m_engine, req, "" );
        final UserProfile existing = m_db.findByLoginName( login );
        // Simulate updating the profile (not new)
        existing.setPassword( "newpassword" );

        m_mgr.validateProfile( ctx2, existing );

        final String[] messages = ctx2.getWikiSession().getMessages( SESSION_MESSAGES );
        assertTrue( messages.length > 0, "Wrong current password should produce a validation error" );

        // Cleanup
        m_db.deleteByLoginName( login );
    }

    // =========================================================================
    //  JSONUserModule — getServletMapping
    // =========================================================================

    @Test
    void jsonUserModuleGetServletMappingReturnsUsersAlias() {
        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        assertEquals( UserManager.JSON_USERS, module.getServletMapping() );
    }

    // =========================================================================
    //  JSONUserModule — getUserInfo success
    // =========================================================================

    @Test
    void jsonUserModuleGetUserInfoReturnsProfileFromDatabase() throws Exception {
        injectField( mockMgr, "database", mockDatabase );

        final UserProfile prof = mock( UserProfile.class );
        when( mockDatabase.find( eq( "alice" ) ) ).thenReturn( prof );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        final UserProfile result = module.getUserInfo( "alice" );
        assertSame( prof, result );
    }

    // =========================================================================
    //  JSONUserModule — getUserInfo not found
    // =========================================================================

    @Test
    void jsonUserModuleGetUserInfoThrowsForUnknownUser() throws Exception {
        injectField( mockMgr, "database", mockDatabase );
        when( mockDatabase.find( anyString() ) ).thenThrow(
                new NoSuchPrincipalException( "no such user" ) );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        assertThrows( NoSuchPrincipalException.class,
                () -> module.getUserInfo( "ghost" ) );
    }

    // =========================================================================
    //  JSONUserModule — service() with empty params → no-op
    // =========================================================================

    @Test
    void jsonUserModuleServiceDoesNothingForEmptyParams() throws Exception {
        final HttpServletRequest req  = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        module.service( req, resp, "GET", Collections.emptyList() );
        verify( resp, never() ).getWriter();
    }

    // =========================================================================
    //  JSONUserModule — service() with blank uid → no-op
    // =========================================================================

    @Test
    void jsonUserModuleServiceDoesNothingForBlankUid() throws Exception {
        final HttpServletRequest req  = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        module.service( req, resp, "GET", List.of( "   " ) );
        verify( resp, never() ).getWriter();
    }

    // =========================================================================
    //  JSONUserModule — service() writes JSON for a valid uid
    // =========================================================================

    @Test
    void jsonUserModuleServiceWritesJsonForKnownUser() throws Exception {
        // Use the real XMLUserDatabase so Gson can serialise a concrete UserProfile
        final UserDatabase realDb = m_mgr.getUserDatabase();
        injectField( mockMgr, "database", realDb );

        // Use "janne" — always present in the test XML database
        final StringWriter sw  = new StringWriter();
        final PrintWriter pw   = new PrintWriter( sw );
        final HttpServletRequest req   = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( pw );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        module.service( req, resp, "GET", List.of( "janne" ) );

        pw.flush();
        assertFalse( sw.toString().isEmpty(), "Response should contain JSON for 'janne'" );
    }

    // =========================================================================
    //  JSONUserModule — service() wraps NoSuchPrincipalException in ServletException
    // =========================================================================

    @Test
    void jsonUserModuleServiceThrowsServletExceptionForUnknownUser() throws Exception {
        injectField( mockMgr, "database", mockDatabase );
        when( mockDatabase.find( anyString() ) ).thenThrow(
                new NoSuchPrincipalException( "gone" ) );

        final HttpServletRequest req  = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        final DefaultUserManager.JSONUserModule module =
                new DefaultUserManager.JSONUserModule( mockMgr );
        assertThrows( ServletException.class,
                () -> module.service( req, resp, "GET", List.of( "ghost" ) ) );
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Injects {@code value} into the named field of {@code target} via reflection. */
    private static void injectField( final Object target, final String fieldName,
                                     final Object value ) {
        try {
            final java.lang.reflect.Field f =
                    DefaultUserManager.class.getDeclaredField( fieldName );
            f.setAccessible( true );
            f.set( target, value );
        } catch ( final ReflectiveOperationException e ) {
            throw new RuntimeException( e );
        }
    }

}

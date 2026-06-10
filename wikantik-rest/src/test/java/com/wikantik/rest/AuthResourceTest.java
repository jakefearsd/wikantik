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

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.SessionSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.Principal;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class AuthResourceTest {

    private TestEngine engine;
    private AuthResource servlet;
    private final Gson gson = new Gson();

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

    @Test
    void testGetUserAnonymous() throws Exception {
        final String json = doGetUser();
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertNotNull( obj );
        assertFalse( obj.get( "authenticated" ).getAsBoolean() );
        assertEquals( "anonymous", obj.get( "username" ).getAsString() );
        assertTrue( obj.has( "roles" ) );
        assertTrue( obj.get( "roles" ).isJsonArray() );
    }

    @Test
    void testGetUserHasLoginPrincipal() throws Exception {
        final String json = doGetUser();
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "loginPrincipal" ) );
        assertNotNull( obj.get( "loginPrincipal" ).getAsString() );
    }

    @Test
    void testGetUserHasRoles() throws Exception {
        final String json = doGetUser();
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "roles" ).getAsJsonArray().size() > 0, "Anonymous user should have at least one role" );
    }

    @Test
    void testUnknownAuthEndpoint() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/unknown" );
        Mockito.doReturn( "/unknown" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginFailureReturns401() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "username", "nosuchuser" );
        body.addProperty( "password", "badpassword" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginMissingUsernameReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "password", "somepassword" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginMissingPasswordReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "username", "someuser" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginNullFieldsReturns400() throws Exception {
        // Explicit JSON nulls used to blow up with 500 (UnsupportedOperationException
        // from JsonNull.getAsString()) — must be a clean 400.
        final JsonObject body = new JsonObject();
        body.add( "username", com.google.gson.JsonNull.INSTANCE );
        body.add( "password", com.google.gson.JsonNull.INSTANCE );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginNonPrimitiveUsernameReturns400() throws Exception {
        // A JSON object/array where a string is expected must also degrade to 400,
        // not 500 (getAsString() throws on non-primitive JSON values).
        final JsonObject body = new JsonObject();
        body.add( "username", new JsonObject() );
        body.addProperty( "password", "x" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginInvalidJsonReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/login" );
        Mockito.doReturn( "/login" ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( "not json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLogoutSuccess() throws Exception {
        final String json = doPost( "logout", new JsonObject() );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
    }

    @Test
    void testUnknownPostEndpoint() throws Exception {
        final String json = doPost( "unknown", new JsonObject() );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testLoginEmptyUsernameReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "username", "   " );
        body.addProperty( "password", "somepassword" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetUserMissingPathReturns404() throws Exception {
        // No pathInfo at all
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPostMissingPathReturns404() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        final JsonObject body = new JsonObject();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- Task 6: User Preferences tests -----

    @Test
    void testGetProfileUnauthenticated() throws Exception {
        final String json = doGet( "profile" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
        assertEquals( "Authentication required", obj.get( "message" ).getAsString() );
    }

    @Test
    void testPutProfileUnauthenticated() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "New Name" );

        final String json = doPut( "profile", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
        assertEquals( "Authentication required", obj.get( "message" ).getAsString() );
    }

    @Test
    void testPutProfileMissingCurrentPasswordForPasswordChange() throws Exception {
        // Even though this user is unauthenticated (so it will fail at 401),
        // we test the body parsing separately.  For authenticated tests,
        // we'd need JAAS, which is not available in wikantik-rest tests.
        // This test verifies the endpoint exists and routes correctly.
        final JsonObject body = new JsonObject();
        body.addProperty( "newPassword", "NewPassword123!" );

        final String json = doPut( "profile", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Will get 401 because the mock session is unauthenticated
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUnknownPutEndpoint() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPut( "unknown", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- Task 7: Lost Password Recovery tests -----

    @Test
    void testResetPasswordMissingEmail() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPost( "reset-password", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "Email" ) );
    }

    @Test
    void testResetPasswordEmptyEmail() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "email", "   " );

        final String json = doPost( "reset-password", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testResetPasswordReturnsGenericSuccess() throws Exception {
        // Even for an unknown email, the response should be generic success
        // to prevent email enumeration
        final JsonObject body = new JsonObject();
        body.addProperty( "email", "nonexistent@example.com" );

        final String json = doPost( "reset-password", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertTrue( obj.get( "message" ).getAsString().contains( "If an account exists" ) );
    }

    // ----- Task 3: mustChangePassword flag tests -----

    @Test
    void loginResponseCarriesMustChangePasswordFlag() throws Exception {
        // Arrange: create a real user with passwordMustChange=true in the engine's DB.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "mustchangeuser" );
        profile.setFullname( "Must Change User" );
        profile.setEmail( "mustchange@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( true );
        db.save( profile );

        // Act: POST /api/auth/login with those credentials.
        final JsonObject body = new JsonObject();
        body.addProperty( "username", "mustchangeuser" );
        body.addProperty( "password", "Xk3-Valid-Pass-77!" );

        final String json = doPost( "login", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Assert: success=true and mustChangePassword=true
        assertTrue( obj.has( "success" ) && obj.get( "success" ).getAsBoolean(),
                "login should succeed, got: " + json );
        assertTrue( obj.has( "mustChangePassword" ),
                "response must carry mustChangePassword key, got: " + json );
        assertTrue( obj.get( "mustChangePassword" ).getAsBoolean(),
                "mustChangePassword should be true, got: " + json );
    }

    @Test
    void selfServicePasswordChangeClearsTheFlag() throws Exception {
        // Arrange: create a real user with passwordMustChange=true.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "changeflaguser" );
        profile.setFullname( "Change Flag User" );
        profile.setEmail( "changeflag@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( true );
        db.save( profile );

        // Stub the UserManager so the servlet uses our DB instance.
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        ( (com.wikantik.WikiEngine) engine ).setManager( com.wikantik.auth.UserManager.class, um );

        // Act: PUT /api/auth/profile as that user with a new password.
        final JsonObject body = new JsonObject();
        body.addProperty( "currentPassword", "Xk3-Valid-Pass-77!" );
        body.addProperty( "newPassword", "Nw9-Fresh-Pass-31!" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "changeflaguser" ) ) ) {
            // We need validatePassword to return true for the current password.
            // The real InMemoryUserDatabase handles this correctly via AbstractUserDatabase.
            doPut( "profile", body );
        }

        // Assert: flag is now false in the database.
        assertFalse( db.findByLoginName( "changeflaguser" ).isPasswordMustChange(),
                "passwordMustChange should be false after self-service password change" );
    }

    @Test
    void resetPasswordSetsTheFlag() throws Exception {
        // Arrange: create a real user with a known email.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "resetflaguser" );
        profile.setFullname( "Reset Flag User" );
        profile.setEmail( "resetflag@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( false );
        db.save( profile );

        // Act: POST /api/auth/reset-password for that email, mocking out MailUtil.
        final JsonObject body = new JsonObject();
        body.addProperty( "email", "resetflag@example.com" );

        try ( MockedStatic< com.wikantik.util.MailUtil > mail =
                      Mockito.mockStatic( com.wikantik.util.MailUtil.class ) ) {
            // MailUtil.sendMessage is a void static — default mock is a no-op, which is what we want.
            mail.when( () -> com.wikantik.util.MailUtil.sendMessage(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ) )
                    .then( invocation -> null );

            final String json = doPost( "reset-password", body );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            assertTrue( obj.get( "success" ).getAsBoolean(),
                    "reset-password should return success, got: " + json );
        }

        // Assert: flag is now true in the database.
        assertTrue( db.findByEmail( "resetflag@example.com" ).isPasswordMustChange(),
                "passwordMustChange should be true after email-based password reset" );
    }

    // ----- Helper methods -----

    private String doGet( final String action ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doGetUser() throws Exception {
        return doGet( "user" );
    }

    private String doPost( final String action, final JsonObject body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private String doPut( final String action, final JsonObject body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );
        return sw.toString();
    }

    // ----- Additional authenticated-path coverage (mocked session) -----

    private static Session authedSession( final String login ) {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.isAuthenticated() ).thenReturn( true );
        final Principal p = Mockito.mock( Principal.class );
        Mockito.when( p.getName() ).thenReturn( login );
        Mockito.when( s.getUserPrincipal() ).thenReturn( p );
        Mockito.when( s.getLoginPrincipal() ).thenReturn( p );
        Mockito.when( s.getRoles() ).thenReturn( new Principal[]{ p } );
        return s;
    }

    private static MockedStatic< Wiki > stubWikiSession( final Session session ) {
        final MockedStatic< Wiki > wiki = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final SessionSPI spi = Mockito.mock( SessionSPI.class );
        Mockito.when( spi.find( any(), any() ) ).thenReturn( session );
        wiki.when( Wiki::session ).thenReturn( spi );
        return wiki;
    }

    private static UserProfile profileFor( final String login, final String email ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getLoginName() ).thenReturn( login );
        Mockito.when( p.getFullname() ).thenReturn( "Full " + login );
        Mockito.when( p.getEmail() ).thenReturn( email );
        Mockito.when( p.getBio() ).thenReturn( "bio" );
        Mockito.when( p.getWikiName() ).thenReturn( login );
        Mockito.when( p.getCreated() ).thenReturn( new Date( 1_700_000_000_000L ) );
        Mockito.when( p.getLastModified() ).thenReturn( new Date( 1_700_000_100_000L ) );
        return p;
    }

    @Test
    void handleGetUser_authenticatedReturnsPrincipalAndRoles() throws Exception {
        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doGetUser(), JsonObject.class );
            assertTrue( obj.get( "authenticated" ).getAsBoolean() );
            assertEquals( "alice", obj.get( "username" ).getAsString() );
            assertTrue( obj.has( "roles" ) );
        }
    }

    @Test
    void handleGetProfile_returnsProfileWhenFound() throws Exception {
        final UserProfile profile = profileFor( "alice", "alice@x.com" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doGet( "profile" ), JsonObject.class );
            assertEquals( "alice", obj.get( "loginName" ).getAsString() );
            assertEquals( "alice@x.com", obj.get( "email" ).getAsString() );
        }
    }

    @Test
    void handleGetProfile_returns404WhenPrincipalNotFound() throws Exception {
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "ghost" ) ).thenThrow( new NoSuchPrincipalException( "no" ) );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "ghost" ) ) ) {
            final JsonObject obj = gson.fromJson( doGet( "profile" ), JsonObject.class );
            assertEquals( 404, obj.get( "status" ).getAsInt() );
        }
    }

    @Test
    void handleUpdateProfile_savesFullNameAndEmailAndBio() throws Exception {
        final UserProfile profile = profileFor( "alice", "alice@x.com" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Alice Smith" );
        body.addProperty( "email", "alice.smith@x.com" );
        body.addProperty( "bio", "engineer" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            doPut( "profile", body );
        }
        Mockito.verify( profile ).setFullname( "Alice Smith" );
        Mockito.verify( profile ).setEmail( "alice.smith@x.com" );
        Mockito.verify( profile ).setBio( "engineer" );
        Mockito.verify( db ).save( profile );
    }

    @Test
    void handleUpdateProfile_rejectsBioOver1000Chars() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "bio", "x".repeat( 1001 ) );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 400, obj.get( "status" ).getAsInt() );
        }
        Mockito.verify( db, Mockito.never() ).save( any() );
    }

    @Test
    void handleUpdateProfile_rejectsPasswordChangeWithoutCurrent() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "newPassword", "Str0ng!Pass#123" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 400, obj.get( "status" ).getAsInt() );
        }
    }

    @Test
    void handleUpdateProfile_rejectsPasswordChangeWithWrongCurrent() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.when( db.validatePassword( "alice", "bad" ) ).thenReturn( false );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "newPassword", "Str0ng!Pass#123" );
        body.addProperty( "currentPassword", "bad" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 403, obj.get( "status" ).getAsInt() );
        }
    }

    @Test
    void handleUpdateProfile_rejectsWeakNewPassword() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.when( db.validatePassword( "alice", "ok" ) ).thenReturn( true );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "newPassword", "123" );
        body.addProperty( "currentPassword", "ok" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 400, obj.get( "status" ).getAsInt() );
        }
        Mockito.verify( db, Mockito.never() ).save( any() );
    }

    @Test
    void handleUpdateProfile_surfacesWikiSecurityExceptionAs500() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.doThrow( new WikiSecurityException( "db down" ) ).when( db ).save( any() );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Alice" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 500, obj.get( "status" ).getAsInt() );
        }
    }
}

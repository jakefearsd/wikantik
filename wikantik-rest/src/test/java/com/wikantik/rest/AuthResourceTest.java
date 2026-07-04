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
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.sso.SSOConfig;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

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

    // ----- Task 6 (Fix 1): GET /api/auth/user must carry mustChangePassword -----

    @Test
    void getUserCarriesMustChangePasswordFlag() throws Exception {
        // Arrange: create a user with passwordMustChange=true in the engine's DB.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "mustchangegetuser" );
        profile.setFullname( "Must Change Get User" );
        profile.setEmail( "mustchangeget@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( true );
        db.save( profile );

        // Act: GET /api/auth/user with an authenticated session for that user.
        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "mustchangegetuser" ) ) ) {
            final JsonObject obj = gson.fromJson( doGetUser(), JsonObject.class );

            // Assert: response includes mustChangePassword=true.
            assertTrue( obj.has( "mustChangePassword" ),
                    "GET /api/auth/user must carry mustChangePassword key" );
            assertTrue( obj.get( "mustChangePassword" ).getAsBoolean(),
                    "mustChangePassword should be true for a flagged user" );
        }
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

    @Test
    void testResetPasswordRateLimitedAfterThreeAttemptsPerHour() throws Exception {
        // A 4th request within the hour for the same (real, known) email must be
        // short-circuited by the rate limiter BEFORE the db lookup / mail send —
        // generic success is still returned (no enumeration signal), but the mail
        // send count must stop growing after the 3rd attempt. If the rate limiter
        // were removed, the 4th call would also send mail (call count 4), so this
        // assertion fails under that regression.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "ratelimituser" );
        profile.setFullname( "Rate Limit User" );
        profile.setEmail( "rate-limit-target@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        db.save( profile );

        final JsonObject body = new JsonObject();
        body.addProperty( "email", "rate-limit-target@example.com" );

        try ( MockedStatic< com.wikantik.util.MailUtil > mail =
                      Mockito.mockStatic( com.wikantik.util.MailUtil.class ) ) {
            mail.when( () -> com.wikantik.util.MailUtil.sendMessage(
                            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ) )
                    .then( invocation -> null );

            for ( int i = 0; i < 3; i++ ) {
                final JsonObject obj = gson.fromJson( doPost( "reset-password", body ), JsonObject.class );
                assertTrue( obj.get( "success" ).getAsBoolean() );
            }
            mail.verify( () -> com.wikantik.util.MailUtil.sendMessage(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ), Mockito.times( 3 ) );

            final JsonObject fourth = gson.fromJson( doPost( "reset-password", body ), JsonObject.class );
            assertTrue( fourth.get( "success" ).getAsBoolean(),
                    "rate-limited response must still be generic success" );

            // Still exactly 3 — the 4th attempt must NOT have reached the mail-send step.
            mail.verify( () -> com.wikantik.util.MailUtil.sendMessage(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ), Mockito.times( 3 ) );
        }
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

    @Test
    void resetPasswordMailFailureStillReturnsGenericSuccessAndLeavesPasswordUnchanged() throws Exception {
        // Arrange: a real user with a known email and a known (unchanged) password.
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "mailfailuser" );
        profile.setFullname( "Mail Fail User" );
        profile.setEmail( "mailfail@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( false );
        db.save( profile );

        final JsonObject body = new JsonObject();
        body.addProperty( "email", "mailfail@example.com" );

        try ( MockedStatic< com.wikantik.util.MailUtil > mail =
                      Mockito.mockStatic( com.wikantik.util.MailUtil.class ) ) {
            mail.when( () -> com.wikantik.util.MailUtil.sendMessage(
                            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ) )
                    .thenThrow( new RuntimeException( "SMTP unreachable" ) );

            final String json = doPost( "reset-password", body );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            // Generic success even on mail failure — prevents email enumeration.
            assertTrue( obj.get( "success" ).getAsBoolean(),
                    "reset-password must still report generic success, got: " + json );
        }

        // The password must NOT have been changed since the email send failed
        // before the save() call in the try block.
        assertTrue( db.validatePassword( "mailfailuser", "Xk3-Valid-Pass-77!" ),
                "password must be unchanged when the reset email fails to send" );
        assertFalse( db.findByEmail( "mailfail@example.com" ).isPasswordMustChange(),
                "passwordMustChange must remain false when the reset email fails to send" );
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

    private String doDelete( final String action, final JsonObject body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );
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

    // ----- GET /api/auth/{me,session,status} (D24 aliases) -----

    @Test
    void handleGetMe_anonymousReturnsUnauthenticatedShapeWithNullFields() throws Exception {
        final JsonObject obj = gson.fromJson( doGet( "me" ), JsonObject.class );
        assertFalse( obj.get( "authenticated" ).getAsBoolean() );
        // RestServletBase's shared Gson does not serializeNulls(), so null-valued
        // map entries (login/fullName for an anonymous caller) are omitted entirely.
        assertFalse( obj.has( "login" ), "anonymous login must be omitted (null), not present" );
        assertFalse( obj.has( "fullName" ), "anonymous fullName must be omitted (null), not present" );
        assertFalse( obj.has( "mustChangePassword" ), "anonymous callers must not carry mustChangePassword" );
        assertTrue( obj.has( "roles" ) );
    }

    @Test
    void handleGetMe_sessionAndStatusAliasesReturnSameShapeAsMe() throws Exception {
        final JsonObject viaSession = gson.fromJson( doGet( "session" ), JsonObject.class );
        final JsonObject viaStatus = gson.fromJson( doGet( "status" ), JsonObject.class );
        assertFalse( viaSession.get( "authenticated" ).getAsBoolean() );
        assertFalse( viaStatus.get( "authenticated" ).getAsBoolean() );
    }

    @Test
    void handleGetMe_authenticatedCarriesLoginFullNameRolesAndMustChangePassword() throws Exception {
        final com.wikantik.auth.UserManager um =
                engine.getManager( com.wikantik.auth.UserManager.class );
        final UserDatabase db = um.getUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "mustchangemeuser" );
        profile.setFullname( "Must Change Me User" );
        profile.setEmail( "mustchangeme@example.com" );
        profile.setPassword( "Xk3-Valid-Pass-77!" );
        profile.setPasswordMustChange( true );
        db.save( profile );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "mustchangemeuser" ) ) ) {
            final JsonObject obj = gson.fromJson( doGet( "me" ), JsonObject.class );
            assertTrue( obj.get( "authenticated" ).getAsBoolean() );
            assertEquals( "mustchangemeuser", obj.get( "login" ).getAsString() );
            assertEquals( "mustchangemeuser", obj.get( "fullName" ).getAsString() );
            assertTrue( obj.get( "mustChangePassword" ).getAsBoolean() );
            assertTrue( obj.get( "roles" ).getAsJsonArray().size() > 0 );
        }
    }

    // ----- GET /api/auth/user: sso descriptor -----

    @Test
    void ssoInfo_disabledByDefault() throws Exception {
        final JsonObject obj = gson.fromJson( doGetUser(), JsonObject.class );
        final JsonObject sso = obj.getAsJsonObject( "sso" );
        assertFalse( sso.get( "enabled" ).getAsBoolean() );
        assertFalse( sso.has( "loginUrl" ), "loginUrl must be omitted when SSO is disabled" );
        assertFalse( sso.has( "providerLabel" ) );
    }

    private JsonObject doGetUserWithSsoProps( final String discoveryUri ) throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        if ( discoveryUri != null ) {
            props.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, discoveryUri );
        }
        final TestEngine ssoEngine = new TestEngine( props );
        try {
            final AuthResource ssoServlet = new AuthResource();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( ssoEngine.getServletContext() ).when( config ).getServletContext();
            ssoServlet.init( config );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/user" );
            Mockito.doReturn( "/user" ).when( request ).getPathInfo();
            Mockito.doReturn( "/JSPWiki" ).when( request ).getContextPath();

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

            ssoServlet.doGet( request, response );
            return gson.fromJson( sw.toString(), JsonObject.class );
        } finally {
            ssoEngine.stop();
        }
    }

    @Test
    void ssoInfo_enabledWithGoogleDiscoveryUriYieldsGoogleLabelAndLoginUrl() throws Exception {
        final JsonObject sso = doGetUserWithSsoProps( "https://accounts.google.com/.well-known/openid-configuration" )
                .getAsJsonObject( "sso" );
        assertTrue( sso.get( "enabled" ).getAsBoolean() );
        assertEquals( "/JSPWiki/sso/login", sso.get( "loginUrl" ).getAsString() );
        assertEquals( "Google", sso.get( "providerLabel" ).getAsString() );
    }

    @Test
    void ssoInfo_enabledWithFacebookDiscoveryUriYieldsFacebookLabel() throws Exception {
        final JsonObject sso = doGetUserWithSsoProps( "https://www.facebook.com/.well-known/openid-configuration" )
                .getAsJsonObject( "sso" );
        assertEquals( "Facebook", sso.get( "providerLabel" ).getAsString() );
    }

    @Test
    void ssoInfo_enabledWithUnknownDiscoveryUriYieldsGenericLabel() throws Exception {
        final JsonObject sso = doGetUserWithSsoProps( "https://idp.example.com/.well-known/openid-configuration" )
                .getAsJsonObject( "sso" );
        assertEquals( "single sign-on", sso.get( "providerLabel" ).getAsString() );
    }

    // ----- DELETE /api/auth/profile (self-service account deletion) -----

    @Test
    void handleDeleteProfile_unauthenticatedReturns401() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "alice" );
        final JsonObject obj = gson.fromJson( doDelete( "profile", body ), JsonObject.class );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
    }

    @Test
    void handleDeleteProfile_unknownActionReturns404() throws Exception {
        final JsonObject obj = gson.fromJson( doDelete( "bogus", new JsonObject() ), JsonObject.class );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void handleDeleteProfile_confirmMismatchReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "someoneelse" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doDelete( "profile", body ), JsonObject.class );
            assertEquals( 400, obj.get( "status" ).getAsInt() );
            assertTrue( obj.get( "message" ).getAsString().contains( "confirmLoginName" ) );
        }
    }

    @Test
    void handleDeleteProfile_adminSessionReturns409() throws Exception {
        final Session admin = Mockito.mock( Session.class );
        Mockito.when( admin.isAuthenticated() ).thenReturn( true );
        final Principal p = Mockito.mock( Principal.class );
        Mockito.when( p.getName() ).thenReturn( "adminuser" );
        Mockito.when( admin.getLoginPrincipal() ).thenReturn( p );
        // Build the admin-role principal BEFORE opening the getRoles() stub — nesting a
        // fresh mock()/when() cycle inside an in-progress thenReturn(...) argument confuses
        // Mockito's stubbing-progress tracker (UnfinishedStubbingException).
        final Principal adminRole = principalNamedAdmin();
        Mockito.when( admin.getRoles() ).thenReturn( new Principal[]{ adminRole } );

        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "adminuser" );

        try ( MockedStatic< Wiki > w = stubWikiSession( admin ) ) {
            final JsonObject obj = gson.fromJson( doDelete( "profile", body ), JsonObject.class );
            assertEquals( 409, obj.get( "status" ).getAsInt() );
        }
    }

    private static Principal principalNamedAdmin() {
        final Principal p = Mockito.mock( Principal.class );
        Mockito.when( p.getName() ).thenReturn( "Admin" );
        return p;
    }

    @Test
    void handleDeleteProfile_noSuchPrincipalReturns404() throws Exception {
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.doThrow( new NoSuchPrincipalException( "no such user" ) )
                .when( db ).deleteByLoginName( "alice" );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final GroupManager gm = Mockito.mock( GroupManager.class );
        Mockito.when( gm.getRoles() ).thenReturn( new Principal[ 0 ] );
        ( (com.wikantik.WikiEngine) engine ).setManager( GroupManager.class, gm );

        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "alice" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doDelete( "profile", body ), JsonObject.class );
            assertEquals( 404, obj.get( "status" ).getAsInt() );
        }
        Mockito.verify( db, Mockito.never() ).save( any() );
    }

    @Test
    void handleDeleteProfile_wikiSecurityExceptionReturns500() throws Exception {
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.doThrow( new WikiSecurityException( "db down" ) )
                .when( db ).deleteByLoginName( "alice" );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final GroupManager gm = Mockito.mock( GroupManager.class );
        Mockito.when( gm.getRoles() ).thenReturn( new Principal[ 0 ] );
        ( (com.wikantik.WikiEngine) engine ).setManager( GroupManager.class, gm );

        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "alice" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doDelete( "profile", body ), JsonObject.class );
            assertEquals( 500, obj.get( "status" ).getAsInt() );
        }
    }

    @Test
    void handleDeleteProfile_successDeletesAccountAndInvalidatesSession() throws Exception {
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final GroupManager gm = Mockito.mock( GroupManager.class );
        Mockito.when( gm.getRoles() ).thenReturn( new Principal[ 0 ] );
        ( (com.wikantik.WikiEngine) engine ).setManager( GroupManager.class, gm );

        final JsonObject body = new JsonObject();
        body.addProperty( "confirmLoginName", "alice" );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/profile" );
        Mockito.doReturn( "/profile" ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( httpSession ).when( request ).getSession( false );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            servlet.doDelete( request, response );
        }

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "deleted" ).getAsBoolean() );
        assertEquals( "alice", obj.get( "loginName" ).getAsString() );
        Mockito.verify( db ).deleteByLoginName( "alice" );
        Mockito.verify( gm ).getRoles();
        Mockito.verify( httpSession ).invalidate();
    }

    // ----- POST /api/auth/login: remember-me cookie on success -----

    @Test
    void loginWithCookieAuthenticationEnabledIssuesRememberMeCookie() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( com.wikantik.auth.AuthenticationManager.PROP_ALLOW_COOKIE_AUTH, "true" );
        final TestEngine cookieEngine = new TestEngine( props );
        try {
            final com.wikantik.auth.UserManager um =
                    cookieEngine.getManager( com.wikantik.auth.UserManager.class );
            final UserDatabase db = um.getUserDatabase();
            final UserProfile profile = db.newProfile();
            profile.setLoginName( "cookieuser" );
            profile.setFullname( "Cookie User" );
            profile.setEmail( "cookieuser@example.com" );
            profile.setPassword( "Xk3-Valid-Pass-77!" );
            db.save( profile );

            final AuthResource cookieServlet = new AuthResource();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( cookieEngine.getServletContext() ).when( config ).getServletContext();
            cookieServlet.init( config );

            final JsonObject body = new JsonObject();
            body.addProperty( "username", "cookieuser" );
            body.addProperty( "password", "Xk3-Valid-Pass-77!" );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/login" );
            Mockito.doReturn( "/login" ).when( request ).getPathInfo();
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
            Mockito.doReturn( false ).when( request ).isSecure();

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

            cookieServlet.doPost( request, response );

            final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
            assertTrue( obj.get( "success" ).getAsBoolean(), "login should succeed, got: " + sw );
            Mockito.verify( response ).addCookie( any( jakarta.servlet.http.Cookie.class ) );
        } finally {
            cookieEngine.stop();
        }
    }
}

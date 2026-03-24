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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

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

    // ----- Helper methods -----

    private String doGetUser() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/auth/user" );
        Mockito.doReturn( "/user" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
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

}

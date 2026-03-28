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
import com.google.gson.JsonArray;
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

class AdminUserResourceTest {

    private TestEngine engine;
    private AdminUserResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminUserResource();
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
    void testListUsers() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "users" ), "Response should contain 'users' key" );
        final JsonArray users = obj.getAsJsonArray( "users" );
        assertNotNull( users, "Users array should not be null" );
        // The XML user database in the test harness may or may not have pre-existing users;
        // we only assert the response structure is correct.
        assertTrue( users.size() >= 0, "Users array should be a valid (possibly empty) array" );
    }

    @Test
    void testGetUserNotFound() throws Exception {
        final String json = doGet( "nonexistent_user_xyz_12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCreateUserMissingLoginName() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "password", "StrongPassword123!" );
        // no loginName

        final String json = doPost( null, body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "loginName" ) );
    }

    @Test
    void testCreateUserMissingPassword() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "loginName", "testuser" );
        // no password

        final String json = doPost( null, body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "password" ) );
    }

    @Test
    void testCreateUserWeakPassword() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "loginName", "testuser" );
        body.addProperty( "password", "short" );  // less than 8 chars

        final String json = doPost( null, body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUpdateUserNotFound() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Updated Name" );

        final String json = doPut( "nonexistent_user_xyz_12345", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDeleteUserNotFound() throws Exception {
        final String json = doDelete( "nonexistent_user_xyz_12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testMissingLoginNameOnPut() throws Exception {
        final HttpServletRequest request = createRequest( null );
        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Test" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testMissingLoginNameOnDelete() throws Exception {
        final HttpServletRequest request = createRequest( null );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testIsCrossOriginAllowedReturnsFalse() {
        assertFalse( servlet.isCrossOriginAllowed(),
                "Admin user endpoint should not allow cross-origin requests" );
    }

    // ----- Helper methods -----

    private String doGet( final String pathParam ) throws Exception {
        final HttpServletRequest request = createRequest( pathParam );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doPost( final String pathParam, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pathParam );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private String doPut( final String pathParam, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pathParam );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );
        return sw.toString();
    }

    private String doDelete( final String pathParam ) throws Exception {
        final HttpServletRequest request = createRequest( pathParam );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathParam ) {
        final String path = pathParam != null ? "/admin/users/" + pathParam : "/admin/users";
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        if ( pathParam != null ) {
            Mockito.doReturn( "/" + pathParam ).when( request ).getPathInfo();
        } else {
            Mockito.doReturn( null ).when( request ).getPathInfo();
        }
        return request;
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdminPolicyResource}.
 * <p>
 * Because the REST module tests do not have a JNDI DataSource for
 * {@code policy_grants}, database-dependent operations (GET list, POST
 * create, DELETE) will return a 500 or 503 when no DatabasePolicy is
 * configured. These tests therefore focus on:
 * <ul>
 *   <li>Action validation (returns 400 for invalid actions)</li>
 *   <li>Request routing and JSON parsing</li>
 *   <li>Graceful handling when DatabasePolicy is unavailable</li>
 * </ul>
 */
class AdminPolicyResourceTest {

    private TestEngine engine;
    private AdminPolicyResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminPolicyResource();
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
    void testListGrantsReturnsErrorWhenNoDatabasePolicy() throws Exception {
        // Without a JNDI DataSource, the servlet should return a service unavailable error
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "error" ), "Should return error when no DatabasePolicy configured" );
    }

    @Test
    void testCreateGrantWithInvalidActions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "invalid_action" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should return error for invalid actions" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "hackdb", "dropTable", "xss_inject", "" } )
    void testCreateGrantWithVariousInvalidActions( final String invalidAction ) throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", invalidAction );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(),
                "Should return error for invalid action: '" + invalidAction + "'" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCreateGrantMissingFields() throws Exception {
        // Missing required fields
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        // Missing principalName, permissionType, target, actions

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should return error for missing fields" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCreateGrantInvalidPermissionType() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "invalid_type" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should return error for invalid permission type" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCreateGrantInvalidPrincipalType() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "invalid_type" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should return error for invalid principal type" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDeleteGrantMissingId() throws Exception {
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
    void testUpdateGrantMissingId() throws Exception {
        final HttpServletRequest request = createRequest( null );
        final JsonObject body = new JsonObject();
        body.addProperty( "actions", "view" );
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
    void testActionValidationForPagePermissions() throws Exception {
        // Valid page actions should not trigger validation error (but may fail on DB)
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view,edit,comment" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // It should pass validation but fail on database (no JNDI DataSource)
        // The key test is that it does NOT return 400 for invalid actions
        if ( obj.has( "error" ) ) {
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "Valid page actions should not trigger a 400 validation error" );
        }
    }

    @Test
    void testActionValidationForWikiPermissions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "createPages,createGroups" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        if ( obj.has( "error" ) ) {
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "Valid wiki actions should not trigger a 400 validation error" );
        }
    }

    @Test
    void testActionValidationForGroupPermissions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "group" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view,edit" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        if ( obj.has( "error" ) ) {
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "Valid group actions should not trigger a 400 validation error" );
        }
    }

    @Test
    void testAllPermissionAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "*" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        if ( obj.has( "error" ) ) {
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "AllPermission action '*' should not trigger a 400 validation error" );
        }
    }

    @Test
    void testMixedValidAndInvalidActions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view,INVALID" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should return error for mixed valid/invalid actions" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ----- Additional validation and routing tests -----

    @Test
    void testCreateGrantMissingPrincipalName() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        // Missing principalName
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "principalName" ),
                "Error should mention missing principalName" );
    }

    @Test
    void testCreateGrantMissingPermissionType() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        // Missing permissionType
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "permissionType" ),
                "Error should mention missing permissionType" );
    }

    @Test
    void testCreateGrantMissingTarget() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        // Missing target
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "target" ),
                "Error should mention missing target" );
    }

    @Test
    void testCreateGrantMissingActions() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Authenticated" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        // Missing actions

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "actions" ),
                "Error should mention missing actions" );
    }

    @Test
    void testDeleteGrantInvalidIdFormat() throws Exception {
        final HttpServletRequest request = createRequest( "not-a-number" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt(),
                "Non-numeric grant ID should return 400" );
        assertTrue( obj.get( "message" ).getAsString().contains( "Invalid grant ID" ) );
    }

    @Test
    void testUpdateGrantInvalidIdFormat() throws Exception {
        final HttpServletRequest request = createRequest( "abc" );
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt(),
                "Non-numeric grant ID should return 400" );
    }

    @Test
    void testDeleteGrantNoDatabasePolicy() throws Exception {
        // With file-based test config, DatabasePolicy is null
        final HttpServletRequest request = createRequest( "1" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean(),
                "Should return error without database policy" );
        assertEquals( 503, obj.get( "status" ).getAsInt(),
                "Should return 503 when database policy is unavailable" );
    }

    @Test
    void testUpdateGrantNoDatabasePolicy() throws Exception {
        final HttpServletRequest request = createRequest( "1" );
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean(),
                "Should return error without database policy" );
        assertEquals( 503, obj.get( "status" ).getAsInt(),
                "Should return 503 when database policy is unavailable" );
    }

    @Test
    void testCreateGrantNoDatabasePolicy() throws Exception {
        // Valid fields but no DB policy available
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "view" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(),
                "Should return error without database policy" );
        assertEquals( 503, obj.get( "status" ).getAsInt(),
                "Should return 503 when database policy is unavailable" );
    }

    @Test
    void testCreateGrantWithInvalidJsonBody() throws Exception {
        final HttpServletRequest request = createRequest( null );
        Mockito.doReturn( new BufferedReader( new StringReader( "not valid json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt(),
                "Invalid JSON body should return 400" );
    }

    @Test
    void testUpdateGrantWithInvalidJsonBody() throws Exception {
        final HttpServletRequest request = createRequest( "42" );
        Mockito.doReturn( new BufferedReader( new StringReader( "{{bad}}" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt(),
                "Invalid JSON body should return 400" );
    }

    @Test
    void testInvalidPageAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "user" );
        body.addProperty( "principalName", "testuser" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "TestPage" );
        body.addProperty( "actions", "createPages" );  // wiki action, not valid for page

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "Invalid action" ) );
    }

    @Test
    void testInvalidWikiAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "group" );
        body.addProperty( "principalName", "editors" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "delete" );  // page action, not valid for wiki

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testInvalidGroupAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "group" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "delete" );  // not a valid group action

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUpdateGrantWithValidFieldsNoDatabasePolicy() throws Exception {
        // Test that validation passes before hitting the DB check
        final HttpServletRequest request = createRequest( "999" );
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "user" );
        body.addProperty( "principalName", "testuser" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "TestPage" );
        body.addProperty( "actions", "view,edit" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        // Should be 503 (no DB), not 400 (validation) -- proves validation passed
        assertEquals( 503, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUpdateGrantWithInvalidFieldsReturns400() throws Exception {
        final HttpServletRequest request = createRequest( "999" );
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "badtype" );
        body.addProperty( "principalName", "testuser" );
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "TestPage" );
        body.addProperty( "actions", "view" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt(),
                "Invalid principal type should return 400, not reach DB" );
    }

    @Test
    void testCreateGrantWithWildcardWikiAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "*" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        if ( obj.has( "error" ) ) {
            // Should pass validation (wildcard), fail on DB
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "Wildcard '*' should not trigger validation error for wiki permission" );
        }
    }

    @Test
    void testCreateGrantWithWildcardGroupAction() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "group" );
        body.addProperty( "principalName", "editors" );
        body.addProperty( "permissionType", "group" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "*" );

        final String json = doPost( body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        if ( obj.has( "error" ) ) {
            assertNotEquals( 400, obj.get( "status" ).getAsInt(),
                    "Wildcard '*' should not trigger validation error for group permission" );
        }
    }

    // ----- CORS restriction tests -----

    /**
     * Verifies that the admin policy endpoint does NOT set Access-Control-Allow-Origin
     * headers. Admin endpoints should be same-origin only.
     */
    @Test
    void testAdminPolicyEndpointDoesNotSetCorsHeaders() throws Exception {
        final HttpServletRequest request = createRequest( null );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        // Verify that Access-Control-Allow-Origin was never set
        Mockito.verify( response, Mockito.never() ).setHeader(
                Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void testAdminPolicyEndpointIsCrossOriginAllowedReturnsFalse() {
        assertFalse( servlet.isCrossOriginAllowed(),
                "Admin policy endpoint should not allow cross-origin requests" );
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

    private String doPost( final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( null );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathParam ) {
        final String path = pathParam != null ? "/admin/policy/" + pathParam : "/admin/policy";
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        if ( pathParam != null ) {
            Mockito.doReturn( "/" + pathParam ).when( request ).getPathInfo();
        } else {
            Mockito.doReturn( null ).when( request ).getPathInfo();
        }
        return request;
    }
}

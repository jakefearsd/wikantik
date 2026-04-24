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
import com.wikantik.WikiEngine;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.DatabasePolicy;
import com.wikantik.auth.DefaultAuthorizationManager;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Exercises the DB-happy paths of {@link AdminPolicyResource} by installing a
 * mock {@link DefaultAuthorizationManager} that returns a {@link DatabasePolicy}
 * backed by a Mockito-stubbed {@link DataSource}. Complements
 * {@link AdminPolicyResourceTest}, which covers the validation branches that
 * never reach JDBC.
 */
class AdminPolicyResourceDbTest {

    private TestEngine engine;
    private AdminPolicyResource servlet;
    private DatabasePolicy dbPolicy;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement ps;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        dataSource = Mockito.mock( DataSource.class );
        connection = Mockito.mock( Connection.class );
        ps = Mockito.mock( PreparedStatement.class );
        Mockito.when( dataSource.getConnection() ).thenReturn( connection );
        Mockito.when( connection.prepareStatement( anyString() ) ).thenReturn( ps );
        Mockito.when( connection.prepareStatement( anyString(), Mockito.anyInt() ) ).thenReturn( ps );

        dbPolicy = Mockito.mock( DatabasePolicy.class );
        Mockito.when( dbPolicy.getDataSource() ).thenReturn( dataSource );
        Mockito.when( dbPolicy.getTableName() ).thenReturn( "policy_grants" );

        final DefaultAuthorizationManager authMgr = Mockito.mock( DefaultAuthorizationManager.class );
        Mockito.when( authMgr.getDatabasePolicy() ).thenReturn( dbPolicy );
        ( (WikiEngine) engine ).setManager( AuthorizationManager.class, authMgr );

        servlet = new AdminPolicyResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    // ---- GET /admin/policy ----

    @Test
    void listGrants_returnsRowsFromDataSource() throws Exception {
        final ResultSet rs = Mockito.mock( ResultSet.class );
        Mockito.when( ps.executeQuery() ).thenReturn( rs );
        Mockito.when( rs.next() ).thenReturn( true, false );
        Mockito.when( rs.getInt( "id" ) ).thenReturn( 42 );
        Mockito.when( rs.getString( "principal_type" ) ).thenReturn( "role" );
        Mockito.when( rs.getString( "principal_name" ) ).thenReturn( "Admin" );
        Mockito.when( rs.getString( "permission_type" ) ).thenReturn( "wiki" );
        Mockito.when( rs.getString( "target" ) ).thenReturn( "*" );
        Mockito.when( rs.getString( "actions" ) ).thenReturn( "login" );

        final JsonObject obj = doGet();
        assertTrue( obj.has( "grants" ) );
        final JsonObject first = obj.getAsJsonArray( "grants" ).get( 0 ).getAsJsonObject();
        assertEquals( 42, first.get( "id" ).getAsInt() );
        assertEquals( "Admin", first.get( "principalName" ).getAsString() );
        assertEquals( "login", first.get( "actions" ).getAsString() );
    }

    @Test
    void listGrants_returns500OnSqlException() throws Exception {
        Mockito.when( ps.executeQuery() ).thenThrow( new SQLException( "boom" ) );
        final JsonObject obj = doGet();
        assertEquals( 500, obj.get( "status" ).getAsInt() );
    }

    @Test
    void listGrants_returns503WhenNoDatabasePolicy() throws Exception {
        // Swap in an AuthorizationManager that has no DatabasePolicy.
        final DefaultAuthorizationManager bare = Mockito.mock( DefaultAuthorizationManager.class );
        Mockito.when( bare.getDatabasePolicy() ).thenReturn( null );
        ( (WikiEngine) engine ).setManager( AuthorizationManager.class, bare );
        final JsonObject obj = doGet();
        assertEquals( 503, obj.get( "status" ).getAsInt() );
    }

    // ---- POST /admin/policy ----

    @Test
    void createGrant_happyPathReturns201WithGeneratedId() throws Exception {
        final ResultSet keys = Mockito.mock( ResultSet.class );
        Mockito.when( ps.getGeneratedKeys() ).thenReturn( keys );
        Mockito.when( keys.next() ).thenReturn( true );
        Mockito.when( keys.getInt( 1 ) ).thenReturn( 7 );
        Mockito.when( ps.executeUpdate() ).thenReturn( 1 );

        final JsonObject body = validBody();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doPost( postRequest( null, body ), resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( 7, obj.get( "id" ).getAsInt() );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_CREATED );
        Mockito.verify( dbPolicy ).refresh();
    }

    @Test
    void createGrant_validationFailureReturns400() throws Exception {
        // Empty principalType → 400
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "login" );

        final JsonObject obj = doPost( body );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createGrant_rejectsInvalidPermissionType() throws Exception {
        final JsonObject body = validBody();
        body.addProperty( "permissionType", "not-a-type" );
        final JsonObject obj = doPost( body );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createGrant_rejectsInvalidPrincipalType() throws Exception {
        final JsonObject body = validBody();
        body.addProperty( "principalType", "not-a-principal" );
        final JsonObject obj = doPost( body );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createGrant_rejectsInvalidPageAction() throws Exception {
        final JsonObject body = validBody();
        body.addProperty( "permissionType", "page" );
        body.addProperty( "target", "TestPage" );
        body.addProperty( "actions", "bogus-action" );
        final JsonObject obj = doPost( body );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void createGrant_returns409OnSqlException() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenThrow( new SQLException( "dup" ) );
        final JsonObject obj = doPost( validBody() );
        assertEquals( 409, obj.get( "status" ).getAsInt() );
    }

    // ---- PUT /admin/policy/{id} ----

    @Test
    void updateGrant_returns400WhenIdMissing() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/policy" );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( validBody().toString() ) ) )
            .when( req ).getReader();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doPut( req, resp );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void updateGrant_returns400WhenIdNonNumeric() throws Exception {
        final JsonObject obj = doPut( "/abc", validBody() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void updateGrant_returns404WhenNoRowUpdated() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenReturn( 0 );
        final JsonObject obj = doPut( "/7", validBody() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void updateGrant_happyPathReturnsShapedResponse() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenReturn( 1 );
        final JsonObject obj = doPut( "/7", validBody() );
        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( 7, obj.get( "id" ).getAsInt() );
        Mockito.verify( dbPolicy ).refresh();
    }

    @Test
    void updateGrant_returns500OnSqlException() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenThrow( new SQLException( "boom" ) );
        final JsonObject obj = doPut( "/7", validBody() );
        assertEquals( 500, obj.get( "status" ).getAsInt() );
    }

    // ---- DELETE /admin/policy/{id} ----

    @Test
    void deleteGrant_happyPathReturnsSuccess() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenReturn( 1 );
        final JsonObject obj = doDelete( "/9" );
        assertTrue( obj.get( "success" ).getAsBoolean() );
        Mockito.verify( dbPolicy ).refresh();
    }

    @Test
    void deleteGrant_returns404WhenNoRowDeleted() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenReturn( 0 );
        final JsonObject obj = doDelete( "/9" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void deleteGrant_returns400WhenIdNonNumeric() throws Exception {
        final JsonObject obj = doDelete( "/abc" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void deleteGrant_returns400WhenIdMissing() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/policy" );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doDelete( req, resp );
        assertEquals( 400,
            gson.fromJson( sw.toString(), JsonObject.class ).get( "status" ).getAsInt() );
    }

    @Test
    void deleteGrant_returns500OnSqlException() throws Exception {
        Mockito.when( ps.executeUpdate() ).thenThrow( new SQLException( "boom" ) );
        final JsonObject obj = doDelete( "/9" );
        assertEquals( 500, obj.get( "status" ).getAsInt() );
    }

    // ---- helpers ----

    private JsonObject validBody() {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "login" );
        return body;
    }

    private JsonObject doGet() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/policy" );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doGet( req, resp );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    private HttpServletRequest postRequest( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/admin/policy" + ( pathInfo != null ? pathInfo : "" ) );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) )
            .when( req ).getReader();
        return req;
    }

    private JsonObject doPost( final JsonObject body ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doPost( postRequest( null, body ), resp );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    private JsonObject doPut( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/policy" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) )
            .when( req ).getReader();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doPut( req, resp );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    private JsonObject doDelete( final String pathInfo ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/policy" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.doDelete( req, resp );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }
}

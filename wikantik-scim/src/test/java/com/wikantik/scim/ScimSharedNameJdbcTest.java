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
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.TestJNDIContext;
import com.wikantik.WikiEngine;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.JDBCUserDatabase;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCIM provisioning of two people who share a name, against a real {@link JDBCUserDatabase} on
 * PostgreSQL (where {@code users.wiki_name} is UNIQUE). Only the servlet plumbing (engine, request,
 * response) is mocked; every read and write goes to the database.
 */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class ScimSharedNameJdbcTest {

    private static final String PREFIX = "scimsn-";

    private DataSource ds;
    private JDBCUserDatabase db;
    private ScimUserResource resource;

    @BeforeAll
    void startDatabase() throws Exception {
        ds = PostgresTestDb.createDataSource();
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch ( final NameAlreadyBoundException e ) {
            LogManager.getLogger( ScimSharedNameJdbcTest.class ).debug( "java:comp/env already bound: {}", e.getMessage() );
        }
        final Context ctx = ( Context ) initCtx.lookup( "java:comp/env" );
        try {
            ctx.bind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        } catch ( final NameAlreadyBoundException e ) {
            ctx.rebind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteTestUsers();
        db = new JDBCUserDatabase();
        db.initialize( null, new Properties() );
        final WikiEngine engine = mock( WikiEngine.class );
        final UserManager um = mock( UserManager.class );
        when( engine.getManager( UserManager.class ) ).thenReturn( um );
        when( um.getUserDatabase() ).thenReturn( db );
        resource = new ScimUserResource();
        final Field f = ScimUserResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, engine );
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteTestUsers();
    }

    private void deleteTestUsers() throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "DELETE FROM users WHERE login_name LIKE ?" ) ) {
            ps.setString( 1, PREFIX + "%" );
            ps.executeUpdate();
        }
    }

    private record Reply( HttpServletResponse resp, JsonObject body ) {}

    private Reply call( final String method, final String pathInfo, final String json ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        when( req.getMethod() ).thenReturn( method );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost/scim/v2/Users" ) );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( json ) ) );
        resource.service( req, resp );
        return new Reply( resp, JsonParser.parseString( sw.toString() ).getAsJsonObject() );
    }

    private String create( final String login, final String json ) throws Exception {
        final Reply r = call( "POST", null, json );
        verify( r.resp() ).setStatus( 201 );
        return r.body().get( "id" ).getAsString();
    }

    @Test
    void secondJohnSmithIsCreatedAndKeepsItsNumberedWikiNameAcrossResync() throws Exception {
        create( PREFIX + "js1", "{\"userName\":\"" + PREFIX + "js1\",\"name\":{\"formatted\":\"John Smith\"}}" );
        final Reply second = call( "POST", null, "{\"userName\":\"" + PREFIX + "js2\",\"name\":{\"formatted\":\"John Smith\"}}" );
        verify( second.resp() ).setStatus( 201 );
        assertEquals( "John Smith", second.body().getAsJsonObject( "name" ).get( "formatted" ).getAsString() );
        assertEquals( "JohnSmith2", second.body().get( "displayName" ).getAsString(),
                "the 201 reports the stored, numbered wiki name" );
        final String id = second.body().get( "id" ).getAsString();

        // The IdP re-sends the same name on its next sync.
        final Reply put = call( "PUT", "/" + id, "{\"userName\":\"" + PREFIX + "js2\",\"name\":{\"formatted\":\"John Smith\"},"
                + "\"emails\":[{\"value\":\"js2@example.test\",\"primary\":true}]}" );
        assertEquals( "JohnSmith2", put.body().get( "displayName" ).getAsString(), put.body().toString() );
        assertEquals( "JohnSmith2", db.findByLoginName( PREFIX + "js2" ).getWikiName() );
        assertEquals( PREFIX + "js1", db.findByWikiName( "JohnSmith" ).getLoginName() );

        final Reply patch = call( "PATCH", "/" + id,
                "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"John Smith\"}}]}" );
        assertEquals( "JohnSmith2", patch.body().get( "displayName" ).getAsString(), patch.body().toString() );
    }

    @Test
    void sharedDisplayNameIsNumberedAndKeptOnResend() throws Exception {
        create( PREFIX + "dn1", "{\"userName\":\"" + PREFIX + "dn1\",\"displayName\":\"Pat Lee\"}" );
        final Reply second = call( "POST", null, "{\"userName\":\"" + PREFIX + "dn2\",\"displayName\":\"Pat Lee\"}" );
        verify( second.resp() ).setStatus( 201 );
        assertEquals( "Pat Lee2", second.body().get( "displayName" ).getAsString() );
        final String id = second.body().get( "id" ).getAsString();

        final Reply put = call( "PUT", "/" + id, "{\"userName\":\"" + PREFIX + "dn2\",\"displayName\":\"Pat Lee\"}" );
        assertEquals( "Pat Lee2", put.body().get( "displayName" ).getAsString(), put.body().toString() );
    }
}

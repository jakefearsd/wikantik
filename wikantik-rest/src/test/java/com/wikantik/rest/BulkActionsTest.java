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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterises, to the byte, the shared bulk-action contract of {@link AdminUserResource} and
 * {@link AdminApiKeysResource} (the 400 texts, the blank-id guard, the envelope) and the 404/400 bodies of the
 * three servlets that dispatch by path ({@link AuthResource}, {@link AdminUserResource},
 * {@link AdminApiKeysResource}) for unknown paths, trailing slashes and a missing login or id. Written against the
 * hand-routed code and kept unchanged across the {@code BulkActions} and route-table refactors.
 */
class BulkActionsTest {

    private static TestEngine engine;
    private static AdminUserResource users;
    private static AdminApiKeysResource keys;
    private static AuthResource auth;
    private ApiKeyService keyService;

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        users = RestTestSupport.initServlet( AdminUserResource::new, engine );
        keys = RestTestSupport.initServlet( AdminApiKeysResource::new, engine );
        auth = RestTestSupport.initServlet( AuthResource::new, engine );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @BeforeEach
    void setUp() {
        keyService = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( keyService );
    }

    @AfterEach
    void tearDown() {
        ApiKeyServiceHolder.setForTesting( null );
    }

    private static String call( final HttpServlet servlet, final String method, final String pathInfo,
                                final String body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/x" );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        }
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        switch ( method ) {
            case "GET" -> invoke( servlet, "doGet", request, response );
            case "POST" -> invoke( servlet, "doPost", request, response );
            case "PUT" -> invoke( servlet, "doPut", request, response );
            default -> invoke( servlet, "doDelete", request, response );
        }
        // the wire form is pretty-printed; compare key order and values, not whitespace
        return new com.google.gson.Gson().toJson( com.google.gson.JsonParser.parseString( sw.toString() ) );
    }

    private static void invoke( final HttpServlet servlet, final String name, final HttpServletRequest request,
                                final HttpServletResponse response ) throws Exception {
        final var m = servlet.getClass().getDeclaredMethod( name, HttpServletRequest.class, HttpServletResponse.class );
        m.setAccessible( true );
        try {
            m.invoke( servlet, request, response );
        } catch ( final java.lang.reflect.InvocationTargetException e ) {
            throw ( Exception ) e.getCause();
        }
    }

    private static String err( final int status, final String message ) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + message + "\"}";
    }

    // ---- API keys bulk ----

    private String keyBulk( final String body ) throws Exception {
        return call( keys, "POST", "/bulk-action", body );
    }

    @Test
    void keysMissingAction() throws Exception {
        assertEquals( err( 400, "action is required (supported: revoke)" ), keyBulk( "{\"ids\":[\"1\"]}" ) );
        assertEquals( err( 400, "action is required (supported: revoke)" ), keyBulk( "{\"action\":\" \"}" ) );
    }

    @Test
    void keysUnknownAction() throws Exception {
        assertEquals( err( 400, "Unsupported action \\u0027frob\\u0027 — supported: revoke" ),
                keyBulk( "{\"action\":\"frob\",\"ids\":[\"1\"]}" ) );
    }

    @Test
    void keysMissingOrBadIds() throws Exception {
        final String msg = err( 400, "ids is required and must be a JSON array" );
        assertEquals( msg, keyBulk( "{\"action\":\"revoke\"}" ) );
        assertEquals( msg, keyBulk( "{\"action\":\"revoke\",\"ids\":\"1\"}" ) );
        assertEquals( err( 400, "ids must not be empty" ), keyBulk( "{\"action\":\"revoke\",\"ids\":[]}" ) );
    }

    @Test
    void keysBlankIdsAndMixedBatch() throws Exception {
        when( keyService.findById( anyInt() ) ).thenReturn( Optional.empty() );
        when( keyService.revoke( eq( 1 ), any() ) ).thenReturn( true );
        when( keyService.revoke( eq( 2 ), any() ) ).thenReturn( false );
        when( keyService.revoke( eq( 4 ), any() ) ).thenThrow( new IllegalStateException( "boom secret sql" ) );
        assertEquals( "{\"succeeded\":[\"1\"],\"failed\":["
                + "{\"id\":\"\\\"\\\"\",\"error\":\"id must be a non-blank string or integer\"},"
                + "{\"id\":\"{}\",\"error\":\"id must be a non-blank string or integer\"},"
                + "{\"id\":\"2\",\"error\":\"Key not found or already revoked\"},"
                + "{\"id\":\"abc\",\"error\":\"id must be numeric; got \\u0027abc\\u0027\"},"
                + "{\"id\":\"4\",\"error\":\"Could not revoke this key; see the server log.\"}],"
                + "\"status\":\"completed\",\"message\":\"1 of 6 keys revoked\"}",
                keyBulk( "{\"action\":\"revoke\",\"ids\":[\"\",{},1,2,\"abc\",4]}" ) );
    }

    @Test
    void keysPathBodies() throws Exception {
        assertEquals( err( 400, "Key id required in path" ), call( keys, "DELETE", null, null ) );
        assertEquals( err( 400, "Key id required in path" ), call( keys, "DELETE", "/", null ) );
        assertEquals( err( 400, "Key id must be numeric" ), call( keys, "DELETE", "/abc", null ) );
        assertEquals( err( 400, "Key id must be numeric" ), call( keys, "DELETE", "/1/", null ) );
        // a trailing slash is not the bulk route: it falls through to key generation
        assertEquals( err( 400, "principalLogin is required" ), call( keys, "POST", "/bulk-action/", "{}" ) );
        assertEquals( err( 400, "principalLogin is required" ), call( keys, "POST", "/other", "{}" ) );
    }

    // ---- users bulk ----

    private String userBulk( final String body ) throws Exception {
        return call( users, "POST", "/bulk-action", body );
    }

    @Test
    void usersMissingAndUnknownAction() throws Exception {
        final String sup = "lock, unlock, delete, add-to-group";
        assertEquals( err( 400, "action is required (supported: " + sup + ")" ), userBulk( "{\"ids\":[\"a\"]}" ) );
        assertEquals( err( 400, "Unsupported action \\u0027frob\\u0027 — supported: " + sup ),
                userBulk( "{\"action\":\"frob\",\"ids\":[\"a\"]}" ) );
    }

    @Test
    void usersMissingOrBadIds() throws Exception {
        assertEquals( err( 400, "ids is required and must be a JSON array" ), userBulk( "{\"action\":\"lock\"}" ) );
        assertEquals( err( 400, "ids must not be empty" ), userBulk( "{\"action\":\"lock\",\"ids\":[]}" ) );
    }

    @Test
    void usersGroupPrepare() throws Exception {
        assertEquals( err( 400, "group is required for action \\u0027add-to-group\\u0027" ),
                userBulk( "{\"action\":\"add-to-group\",\"ids\":[\"a\"]}" ) );
        assertEquals( err( 400, "Group not found: NoSuchGroupZzz" ),
                userBulk( "{\"action\":\"add-to-group\",\"ids\":[\"a\"],\"group\":\"NoSuchGroupZzz\"}" ) );
    }

    @Test
    void usersBlankIdAndMixedBatch() throws Exception {
        final String out = userBulk( "{\"action\":\"unlock\",\"ids\":[\"\",5,\"admin\",\"zzNoSuchUser\"]}" );
        assertEquals( "{\"succeeded\":[\"admin\"],\"failed\":["
                + "{\"id\":\"\\\"\\\"\",\"error\":\"id must be a non-blank string\"},"
                + "{\"id\":\"5\",\"error\":\"Not in database: 5\"},"
                + "{\"id\":\"zzNoSuchUser\",\"error\":\"Not in database: zzNoSuchUser\"}],"
                + "\"status\":\"completed\",\"message\":\"1 of 4 users unlocked\"}", out );
    }

    // ---- users paths ----

    @Test
    void usersPathBodies() throws Exception {
        assertEquals( err( 404, "Unknown endpoint" ), call( users, "POST", "/someone", "{}" ) );
        assertEquals( err( 404, "Unknown endpoint" ), call( users, "POST", "/bulk-action/", "{}" ) );
        assertEquals( err( 404, "Unknown endpoint" ), call( users, "POST", "/a/lockx", "{}" ) );
        assertEquals( err( 400, "Login name required in path" ), call( users, "PUT", null, "{}" ) );
        assertEquals( err( 400, "Login name required in path" ), call( users, "PUT", "/", "{}" ) );
        assertEquals( err( 400, "Login name required in path" ), call( users, "DELETE", null, null ) );
        assertEquals( err( 400, "Login name required in path" ), call( users, "DELETE", "/", null ) );
        assertEquals( err( 404, "User not found: nobodyZz" ), call( users, "GET", "/nobodyZz", null ) );
        assertEquals( err( 404, "User not found: nobodyZz/" ), call( users, "GET", "/nobodyZz/", null ) );
        assertEquals( err( 404, "User not found: nobodyZz" ),
                call( users, "POST", "/nobodyZz/lock", "{}" ) );
        assertEquals( err( 404, "User not found: nobodyZz" ), call( users, "POST", "/nobodyZz/unlock", "{}" ) );
    }

    // ---- auth paths ----

    @Test
    void authUnknownPaths() throws Exception {
        assertEquals( err( 404, "Unknown auth endpoint: null" ), call( auth, "GET", null, null ) );
        assertEquals( err( 404, "Unknown auth endpoint: null" ), call( auth, "GET", "/", null ) );
        assertEquals( err( 404, "Unknown auth endpoint: nope" ), call( auth, "GET", "/nope", null ) );
        assertEquals( err( 404, "Unknown auth endpoint: me/" ), call( auth, "GET", "/me/", null ) );
        assertEquals( err( 404, "Unknown auth endpoint: user/x" ), call( auth, "GET", "/user/x", null ) );
        assertEquals( err( 404, "Unknown auth endpoint: login/" ), call( auth, "POST", "/login/", "{}" ) );
        assertEquals( err( 404, "Unknown auth endpoint: user" ), call( auth, "POST", "/user", "{}" ) );
        assertEquals( err( 404, "Unknown auth endpoint: null" ), call( auth, "POST", null, "{}" ) );
        assertEquals( err( 404, "Unknown auth endpoint: login" ), call( auth, "PUT", "/login", "{}" ) );
        assertEquals( err( 404, "Unknown auth endpoint: profile/" ), call( auth, "PUT", "/profile/", "{}" ) );
        assertEquals( err( 404, "Unknown auth endpoint: user" ), call( auth, "DELETE", "/user", null ) );
        assertEquals( err( 404, "Unknown auth endpoint: null" ), call( auth, "DELETE", null, null ) );
    }
}

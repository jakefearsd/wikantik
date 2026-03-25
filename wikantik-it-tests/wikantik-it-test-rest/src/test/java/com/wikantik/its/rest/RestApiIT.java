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
package com.wikantik.its.rest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Wikantik REST API.
 * <p>
 * Makes real HTTP calls against a Cargo-deployed Tomcat instance.
 * Uses {@code java.net.http.HttpClient} (JDK 21) and Gson for JSON parsing.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class RestApiIT {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:8080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( new CookieManager( null, CookiePolicy.ACCEPT_ALL ) )
                .build();
    }

    // ---- helper methods ----

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    @SuppressWarnings( "unused" )
    private HttpResponse< String > post( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .DELETE()
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > patch( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .method( "PATCH", HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private Map< String, Object > parseJson( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    // ---- Page CRUD tests ----

    @Test
    @Order( 1 )
    void testGetMainPage() throws Exception {
        final HttpResponse< String > resp = get( "/api/pages/Main" );
        assertEquals( 200, resp.statusCode(), "GET /api/pages/Main should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "Main", json.get( "name" ) );
        assertNotNull( json.get( "content" ), "Page should have content" );
        assertNotNull( json.get( "version" ), "Page should have version" );
        assertTrue( ( Boolean ) json.get( "exists" ), "Page should exist" );
    }

    @Test
    @Order( 2 )
    void testGetNonexistentPage() throws Exception {
        final HttpResponse< String > resp = get( "/api/pages/NoSuchPage" );
        assertEquals( 404, resp.statusCode(), "GET for a nonexistent page should return 404" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( ( Boolean ) json.get( "error" ), "Response should indicate an error" );
    }

    @Test
    @Order( 3 )
    void testCreateAndReadPage() throws Exception {
        final String pageName = "RestTestPage";
        final String content = "Hello from REST integration test";
        final String body = GSON.toJson( Map.of( "content", content, "changeNote", "created by RestApiIT" ) );

        // Create
        final HttpResponse< String > putResp = put( "/api/pages/" + pageName, body );
        assertEquals( 200, putResp.statusCode(), "PUT should return 200 on create" );

        final Map< String, Object > putJson = parseJson( putResp.body() );
        assertTrue( ( Boolean ) putJson.get( "success" ), "PUT should report success" );

        // Read back
        final HttpResponse< String > getResp = get( "/api/pages/" + pageName );
        assertEquals( 200, getResp.statusCode(), "GET should return 200 for newly created page" );

        final Map< String, Object > getJson = parseJson( getResp.body() );
        assertEquals( pageName, getJson.get( "name" ) );
        assertTrue( getJson.get( "content" ).toString().contains( content ),
                "Content should match what was written" );
    }

    @Test
    @Order( 4 )
    void testDeletePage() throws Exception {
        final String pageName = "RestDeleteTestPage";
        final String body = GSON.toJson( Map.of( "content", "Page to be deleted" ) );

        // Create
        final HttpResponse< String > putResp = put( "/api/pages/" + pageName, body );
        assertEquals( 200, putResp.statusCode(), "PUT should return 200 on create" );

        // Delete
        final HttpResponse< String > delResp = delete( "/api/pages/" + pageName );
        assertEquals( 200, delResp.statusCode(), "DELETE should return 200" );

        final Map< String, Object > delJson = parseJson( delResp.body() );
        assertTrue( ( Boolean ) delJson.get( "success" ), "DELETE should report success" );

        // Verify it is gone
        final HttpResponse< String > getResp = get( "/api/pages/" + pageName );
        assertEquals( 404, getResp.statusCode(), "Deleted page should return 404" );
    }

    @Test
    @Order( 5 )
    @SuppressWarnings( "unchecked" )
    void testListPages() throws Exception {
        final HttpResponse< String > resp = get( "/api/pages" );
        assertEquals( 200, resp.statusCode(), "GET /api/pages should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertNotNull( json.get( "pages" ), "Response should contain a pages array" );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) json.get( "pages" );
        assertFalse( pages.isEmpty(), "Pages list should not be empty" );

        final double total = ( ( Number ) json.get( "total" ) ).doubleValue();
        assertTrue( total > 0, "Total page count should be greater than 0" );
    }

    // ---- Search tests ----

    @Test
    @Order( 6 )
    void testSearch() throws Exception {
        // Allow a brief pause for the search index to build
        Thread.sleep( 2000 );

        final HttpResponse< String > resp = get( "/api/search?q=Main" );
        assertEquals( 200, resp.statusCode(), "GET /api/search?q=Main should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "Main", json.get( "query" ) );
        assertNotNull( json.get( "results" ), "Response should contain results" );
    }

    @Test
    @Order( 7 )
    void testSearchEmptyQuery() throws Exception {
        final HttpResponse< String > resp = get( "/api/search" );
        assertEquals( 400, resp.statusCode(), "GET /api/search without query should return 400" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( ( Boolean ) json.get( "error" ), "Response should indicate an error" );
    }

    // ---- History tests ----

    @Test
    @Order( 8 )
    void testPageHistory() throws Exception {
        final HttpResponse< String > resp = get( "/api/history/Main" );
        assertEquals( 200, resp.statusCode(), "GET /api/history/Main should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "Main", json.get( "name" ) );
        assertNotNull( json.get( "versions" ), "Response should contain versions array" );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > versions = ( List< Map< String, Object > > ) json.get( "versions" );
        assertFalse( versions.isEmpty(), "Versions list should not be empty" );
    }

    // ---- Backlinks tests ----

    @Test
    @Order( 9 )
    void testBacklinks() throws Exception {
        // Create a page that links to Main
        final String linkerPage = "RestBacklinkTestPage";
        final String content = "This page links to [Main]().";
        final String body = GSON.toJson( Map.of( "content", content ) );
        put( "/api/pages/" + linkerPage, body );

        // Give the reference manager a moment to index the new link
        Thread.sleep( 2000 );

        final HttpResponse< String > resp = get( "/api/backlinks/Main" );
        assertEquals( 200, resp.statusCode(), "GET /api/backlinks/Main should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "Main", json.get( "name" ) );
        assertNotNull( json.get( "backlinks" ), "Response should contain backlinks" );

        @SuppressWarnings( "unchecked" )
        final List< String > backlinks = ( List< String > ) json.get( "backlinks" );
        assertTrue( backlinks.contains( linkerPage ),
                "Backlinks for Main should include " + linkerPage );
    }

    // ---- Auth tests ----

    @Test
    @Order( 10 )
    void testAnonymousUser() throws Exception {
        final HttpResponse< String > resp = get( "/api/auth/user" );
        assertEquals( 200, resp.statusCode(), "GET /api/auth/user should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertFalse( ( Boolean ) json.get( "authenticated" ), "Anonymous user should not be authenticated" );
    }

    @Test
    @Order( 11 )
    void testLoginLogout() throws Exception {
        // Use a dedicated client with its own cookie jar for session isolation
        final CookieManager cookieManager = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        final HttpClient authClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( cookieManager )
                .build();

        // Login
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpRequest loginReq = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( loginBody ) )
                .build();
        final HttpResponse< String > loginResp = authClient.send( loginReq, HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, loginResp.statusCode(), "POST /api/auth/login should return 200" );

        final Map< String, Object > loginJson = parseJson( loginResp.body() );
        assertTrue( ( Boolean ) loginJson.get( "success" ), "Login should succeed" );

        // Check user info while logged in
        final HttpRequest userReq = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/user" ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        final HttpResponse< String > userResp = authClient.send( userReq, HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, userResp.statusCode() );

        final Map< String, Object > userJson = parseJson( userResp.body() );
        assertTrue( ( Boolean ) userJson.get( "authenticated" ), "User should be authenticated after login" );

        // Logout
        final HttpRequest logoutReq = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/logout" ) )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( "{}" ) )
                .build();
        final HttpResponse< String > logoutResp = authClient.send( logoutReq, HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, logoutResp.statusCode(), "POST /api/auth/logout should return 200" );

        final Map< String, Object > logoutJson = parseJson( logoutResp.body() );
        assertTrue( ( Boolean ) logoutJson.get( "success" ), "Logout should succeed" );
    }

    // ---- Render, Version, PATCH tests ----

    @Test
    @Order( 12 )
    void testGetPageRendered() throws Exception {
        final HttpResponse< String > resp = get( "/api/pages/Main?render=true" );
        assertEquals( 200, resp.statusCode(), "GET /api/pages/Main?render=true should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertNotNull( json.get( "contentHtml" ), "Response should include contentHtml" );
        assertTrue( json.get( "contentHtml" ).toString().contains( "<" ),
                "contentHtml should contain HTML tags" );
    }

    @Test
    @Order( 13 )
    void testGetPageVersion() throws Exception {
        final String pageName = "RestVersionTestPage";

        // Save version 1
        final String body1 = GSON.toJson( Map.of( "content", "First version content", "changeNote", "v1" ) );
        put( "/api/pages/" + pageName, body1 );

        // Save version 2
        final String body2 = GSON.toJson( Map.of( "content", "Second version content", "changeNote", "v2" ) );
        put( "/api/pages/" + pageName, body2 );

        // Retrieve version 1
        final HttpResponse< String > resp = get( "/api/pages/" + pageName + "?version=1" );
        assertEquals( 200, resp.statusCode(), "GET with ?version=1 should return 200" );

        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( json.get( "content" ).toString().contains( "First version content" ),
                "Version 1 should contain first version's content" );
        assertEquals( 1.0, ( ( Number ) json.get( "version" ) ).doubleValue(),
                "Version field should be 1" );
    }

    @Test
    @Order( 14 )
    @SuppressWarnings( "unchecked" )
    void testPatchMetadata() throws Exception {
        final String pageName = "RestPatchTestPage";

        // Create a page with metadata
        final String createBody = GSON.toJson( Map.of(
                "content", "Patch test body",
                "metadata", Map.of( "type", "article", "tags", List.of( "original" ) )
        ) );
        put( "/api/pages/" + pageName, createBody );

        // PATCH to add a new tag via merge
        final String patchBody = GSON.toJson( Map.of(
                "metadata", Map.of( "category", "patched" ),
                "action", "merge"
        ) );
        final HttpResponse< String > patchResp = patch( "/api/pages/" + pageName, patchBody );
        assertEquals( 200, patchResp.statusCode(), "PATCH should return 200" );

        final Map< String, Object > patchJson = parseJson( patchResp.body() );
        assertTrue( ( Boolean ) patchJson.get( "success" ), "PATCH should report success" );

        // GET and verify merged metadata
        final HttpResponse< String > getResp = get( "/api/pages/" + pageName );
        assertEquals( 200, getResp.statusCode() );

        final Map< String, Object > getJson = parseJson( getResp.body() );
        final Map< String, Object > meta = ( Map< String, Object > ) getJson.get( "metadata" );
        assertNotNull( meta, "Metadata should be present" );
        assertEquals( "article", meta.get( "type" ), "Original type should be preserved" );
        assertEquals( "patched", meta.get( "category" ), "New category should be present" );
    }

    // ---- CORS tests ----

    @Test
    @Order( 15 )
    void testCorsHeaders() throws Exception {
        final HttpResponse< String > resp = get( "/api/pages/Main" );
        assertEquals( 200, resp.statusCode() );

        final String corsHeader = resp.headers().firstValue( "Access-Control-Allow-Origin" ).orElse( null );
        assertNotNull( corsHeader, "Response should include Access-Control-Allow-Origin header" );
        assertEquals( "*", corsHeader, "CORS origin should be wildcard" );
    }

}

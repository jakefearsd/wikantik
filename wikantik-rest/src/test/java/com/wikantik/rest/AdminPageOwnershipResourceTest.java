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
import com.wikantik.api.comments.PageOwnership;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AdminPageOwnershipResource}. Uses a Mockito spy
 * so the seam methods {@code pageOwners()}, {@code userExists(...)},
 * and {@code currentUser(...)} can be overridden without touching the
 * full {@code WikiEngine}/JNDI plumbing.
 */
class AdminPageOwnershipResourceTest {

    private final Gson gson = new Gson();
    private PageOwnerService pageOwners;
    private AdminPageOwnershipResource servlet;

    @BeforeEach
    void setUp() {
        this.pageOwners = Mockito.mock( PageOwnerService.class );
        this.servlet = Mockito.spy( new AdminPageOwnershipResource() );
        Mockito.doReturn( pageOwners ).when( servlet ).pageOwners();
        Mockito.doReturn( "admin" ).when( servlet ).currentUser( Mockito.any() );
        // Known logins; "ghost" is unknown.
        Mockito.doReturn( true ).when( servlet ).userExists( "alice" );
        Mockito.doReturn( true ).when( servlet ).userExists( "bob" );
        Mockito.doReturn( true ).when( servlet ).userExists( "admin" );
        Mockito.doReturn( false ).when( servlet ).userExists( "ghost" );
    }

    /* ---- GET ---- */

    @Test
    void get_filter_orphaned_returns_rows_and_total() throws Exception {
        Mockito.when( pageOwners.listOrphaned( 50, 0 ) ).thenReturn( List.of(
                new PageOwnership( "CID-1", null, "admin", Instant.parse( "2026-01-01T00:00:00Z" ) ),
                new PageOwnership( "CID-2", null, "admin", Instant.parse( "2026-01-02T00:00:00Z" ) ) ) );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 2 );

        final JsonObject body = get( "?filter=orphaned" );
        assertEquals( 2, body.get( "total" ).getAsInt() );
        final JsonArray pages = body.getAsJsonArray( "pages" );
        assertEquals( 2, pages.size() );
        final JsonObject row = pages.get( 0 ).getAsJsonObject();
        assertEquals( "CID-1", row.get( "canonicalId" ).getAsString() );
        // Gson drops null map values by default — ownerLogin must be absent or json-null.
        assertTrue( !row.has( "ownerLogin" ) || row.get( "ownerLogin" ).isJsonNull(),
                "orphaned row must have null ownerLogin: " + row );
    }

    @Test
    void get_filter_by_owner_returns_owner_rows() throws Exception {
        Mockito.when( pageOwners.listByOwner( "alice", 50, 0 ) ).thenReturn( List.of(
                new PageOwnership( "CID-A", "alice", "admin", Instant.parse( "2026-01-01T00:00:00Z" ) ) ) );
        Mockito.when( pageOwners.countByOwner( "alice" ) ).thenReturn( 1 );

        final JsonObject body = get( "?filter=by-owner&owner=alice" );
        assertEquals( 1, body.get( "total" ).getAsInt() );
        assertEquals( "alice", body.getAsJsonArray( "pages" )
                .get( 0 ).getAsJsonObject().get( "ownerLogin" ).getAsString() );
    }

    @Test
    void get_filter_by_owner_with_orphaned_sentinel_routes_to_orphaned_listing() throws Exception {
        Mockito.when( pageOwners.listOrphaned( 50, 0 ) ).thenReturn( List.of(
                new PageOwnership( "CID-9", null, "admin", Instant.parse( "2026-01-01T00:00:00Z" ) ) ) );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 1 );

        final JsonObject body = get( "?filter=by-owner&owner=%3Corphaned%3E" );
        assertEquals( 1, body.get( "total" ).getAsInt() );
        Mockito.verify( pageOwners ).listOrphaned( 50, 0 );
        Mockito.verify( pageOwners, Mockito.never() )
                .listByOwner( Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt() );
    }

    @Test
    void get_with_no_filter_is_400() throws Exception {
        final JsonObject body = get( "" );
        assertTrue( body.get( "error" ).getAsBoolean() );
        assertEquals( 400, body.get( "status" ).getAsInt() );
    }

    @Test
    void get_with_bad_filter_is_400() throws Exception {
        final JsonObject body = get( "?filter=nope" );
        assertTrue( body.get( "error" ).getAsBoolean() );
        assertEquals( 400, body.get( "status" ).getAsInt() );
    }

    /* ---- POST /reassign ---- */

    @Test
    void post_reassign_valid_pages_updates_each() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "CID-1" );
        pages.add( "CID-2" );
        body.add( "pages", pages );
        body.addProperty( "newOwner", "bob" );

        final JsonObject res = post( "/reassign", body );
        assertEquals( 2, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners ).setOwner( "CID-1", "bob", "admin" );
        Mockito.verify( pageOwners ).setOwner( "CID-2", "bob", "admin" );
    }

    @Test
    void post_reassign_unknown_new_owner_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "CID-1" );
        body.add( "pages", pages );
        body.addProperty( "newOwner", "ghost" );

        final JsonObject res = post( "/reassign", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
        Mockito.verify( pageOwners, Mockito.never() )
                .setOwner( Mockito.anyString(), Mockito.anyString(), Mockito.anyString() );
    }

    @Test
    void post_reassign_sentinel_new_owner_orphans_pages() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "CID-1" );
        body.add( "pages", pages );
        body.addProperty( "newOwner", "<orphaned>" );

        final JsonObject res = post( "/reassign", body );
        assertEquals( 1, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners ).setOwner( "CID-1", null, "admin" );
    }

    @Test
    void post_reassign_empty_pages_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.add( "pages", new JsonArray() );
        body.addProperty( "newOwner", "bob" );
        final JsonObject res = post( "/reassign", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    /* ---- POST /reassign-by-user ---- */

    @Test
    void post_reassign_by_user_bulk_reassign() throws Exception {
        Mockito.when( pageOwners.bulkReassign( "alice", "bob", "admin" ) ).thenReturn( 3 );
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        body.addProperty( "toOwner", "bob" );
        final JsonObject res = post( "/reassign-by-user", body );
        assertEquals( 3, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners ).bulkReassign( "alice", "bob", "admin" );
    }

    @Test
    void post_reassign_by_user_from_orphaned_calls_reassignFromOrphaned() throws Exception {
        Mockito.when( pageOwners.reassignFromOrphaned( "bob", "admin" ) ).thenReturn( 5 );
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "<orphaned>" );
        body.addProperty( "toOwner", "bob" );
        final JsonObject res = post( "/reassign-by-user", body );
        assertEquals( 5, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners ).reassignFromOrphaned( "bob", "admin" );
        Mockito.verify( pageOwners, Mockito.never() )
                .bulkReassign( Mockito.anyString(), Mockito.anyString(), Mockito.anyString() );
    }

    @Test
    void post_reassign_by_user_to_orphaned_calls_orphanByOwner() throws Exception {
        Mockito.when( pageOwners.orphanByOwner( "alice", "admin" ) ).thenReturn( 4 );
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        body.addProperty( "toOwner", "<orphaned>" );
        final JsonObject res = post( "/reassign-by-user", body );
        assertEquals( 4, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners ).orphanByOwner( "alice", "admin" );
    }

    @Test
    void post_reassign_by_user_unknown_to_owner_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        body.addProperty( "toOwner", "ghost" );
        final JsonObject res = post( "/reassign-by-user", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
        Mockito.verify( pageOwners, Mockito.never() )
                .bulkReassign( Mockito.anyString(), Mockito.anyString(), Mockito.anyString() );
    }

    @Test
    void post_reassign_by_user_missing_fields_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        // missing toOwner
        final JsonObject res = post( "/reassign-by-user", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void post_unknown_route_is_404() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "x", "y" );
        final JsonObject res = post( "/no-such", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    /* ---- additional coverage: production userExists, clamps, no-op orphan→orphan, parseJsonBody ---- */

    @Test
    void post_reassign_orphaned_to_orphaned_is_a_no_op() throws Exception {
        // Both sentinels → updated = 0 without touching pageOwners.
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "<orphaned>" );
        body.addProperty( "toOwner", "<orphaned>" );
        final JsonObject res = post( "/reassign-by-user", body );
        assertEquals( 0, res.get( "updated" ).getAsInt() );
        Mockito.verify( pageOwners, Mockito.never() )
                .bulkReassign( Mockito.anyString(), Mockito.anyString(), Mockito.anyString() );
        Mockito.verify( pageOwners, Mockito.never() )
                .reassignFromOrphaned( Mockito.anyString(), Mockito.anyString() );
        Mockito.verify( pageOwners, Mockito.never() )
                .orphanByOwner( Mockito.anyString(), Mockito.anyString() );
    }

    @Test
    void get_with_invalid_limit_and_offset_falls_back_to_defaults() throws Exception {
        // NumberFormatException branches in both clampLimit and clampNonNeg.
        Mockito.when( pageOwners.listOrphaned( 50, 0 ) ).thenReturn( List.of() );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 0 );
        final JsonObject body = get( "?filter=orphaned&limit=garbage&offset=garbage" );
        // Default limit=50, default offset=0 — must hit listOrphaned with those values.
        Mockito.verify( pageOwners ).listOrphaned( 50, 0 );
        assertEquals( 0, body.get( "total" ).getAsInt() );
    }

    @Test
    void get_with_negative_offset_clamps_to_zero() throws Exception {
        Mockito.when( pageOwners.listOrphaned( 50, 0 ) ).thenReturn( List.of() );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 0 );
        get( "?filter=orphaned&offset=-7" );
        Mockito.verify( pageOwners ).listOrphaned( 50, 0 );
    }

    @Test
    void get_with_zero_limit_falls_back_to_default() throws Exception {
        // clampLimit returns DEFAULT_LIMIT when parsed value <= 0.
        Mockito.when( pageOwners.listOrphaned( 50, 0 ) ).thenReturn( List.of() );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 0 );
        get( "?filter=orphaned&limit=0" );
        Mockito.verify( pageOwners ).listOrphaned( 50, 0 );
    }

    @Test
    void get_with_huge_limit_clamps_to_MAX_LIMIT() throws Exception {
        Mockito.when( pageOwners.listOrphaned( 500, 0 ) ).thenReturn( List.of() );
        Mockito.when( pageOwners.countOrphaned() ).thenReturn( 0 );
        get( "?filter=orphaned&limit=99999" );
        Mockito.verify( pageOwners ).listOrphaned( 500, 0 );
    }

    @Test
    void post_reassign_with_malformed_json_body_is_400() throws Exception {
        // parseJsonBody returns null on parse failure and sends 400; the handler
        // bails out cleanly. We bypass `post()` so we can inject malformed JSON.
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/page-ownership" );
        Mockito.doReturn( "/reassign" ).when( req ).getPathInfo();
        Mockito.doReturn( "POST" ).when( req ).getMethod();
        Mockito.doReturn( new BufferedReader( new StringReader( "{not-json" ) ) ).when( req ).getReader();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        final JsonObject res = gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
        assertTrue( res.get( "error" ).getAsBoolean() );
    }

    @Test
    void post_reassign_with_missing_newOwner_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "CID-1" );
        body.add( "pages", pages );
        // intentionally no newOwner
        final JsonObject res = post( "/reassign", body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void production_userExists_returns_true_when_login_resolves() throws Exception {
        // Use the real (non-spied) userExists path by NOT stubbing it on a fresh
        // spy. We back it with a mocked UserDatabase via the `users()` seam.
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( db.findByLoginName( "real" ) ).thenReturn( Mockito.mock( UserProfile.class ) );

        final AdminPageOwnershipResource fresh = Mockito.spy( new AdminPageOwnershipResource() );
        Mockito.doReturn( pageOwners ).when( fresh ).pageOwners();
        Mockito.doReturn( "admin" ).when( fresh ).currentUser( Mockito.any() );
        Mockito.doReturn( db ).when( fresh ).users();

        Mockito.when( pageOwners.bulkReassign( "alice", "real", "admin" ) ).thenReturn( 1 );
        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        body.addProperty( "toOwner", "real" );
        final JsonObject res = invokeOn( fresh, "POST", "/reassign-by-user", body, null );
        assertEquals( 1, res.get( "updated" ).getAsInt() );
    }

    @Test
    void production_userExists_returns_false_when_login_missing() throws Exception {
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( db.findByLoginName( "ghost" ) )
                .thenThrow( new NoSuchPrincipalException( "no such" ) );

        final AdminPageOwnershipResource fresh = Mockito.spy( new AdminPageOwnershipResource() );
        Mockito.doReturn( pageOwners ).when( fresh ).pageOwners();
        Mockito.doReturn( "admin" ).when( fresh ).currentUser( Mockito.any() );
        Mockito.doReturn( db ).when( fresh ).users();

        final JsonObject body = new JsonObject();
        body.addProperty( "fromOwner", "alice" );
        body.addProperty( "toOwner", "ghost" );
        final JsonObject res = invokeOn( fresh, "POST", "/reassign-by-user", body, null );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void production_userExists_returns_false_for_blank_login() throws Exception {
        // login==null branch: a fromOwner="alice", toOwner=" " (blank) hits the
        // "missing required fields" 400 before userExists, so we exercise the
        // explicit blank check via the validation 400.
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        final AdminPageOwnershipResource fresh = Mockito.spy( new AdminPageOwnershipResource() );
        Mockito.doReturn( pageOwners ).when( fresh ).pageOwners();
        Mockito.doReturn( "admin" ).when( fresh ).currentUser( Mockito.any() );
        Mockito.doReturn( db ).when( fresh ).users();

        // Direct call to the protected method: blank → false (no DB call).
        assertFalse( fresh.userExists( "" ) );
        assertFalse( fresh.userExists( null ) );
        assertFalse( fresh.userExists( "   " ) );
        Mockito.verifyNoInteractions( db );
    }

    private JsonObject invokeOn( final AdminPageOwnershipResource s,
                                 final String method, final String pathInfo,
                                 final JsonObject jsonBody, final String query ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/page-ownership" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( method ).when( req ).getMethod();
        if ( jsonBody != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( jsonBody.toString() ) ) ).when( req ).getReader();
        }
        if ( query != null ) {
            for ( final String key : new String[] { "filter", "owner", "limit", "offset" } ) {
                Mockito.doReturn( queryParam( query, key ) ).when( req ).getParameter( key );
            }
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        s.service( req, resp );
        return gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
    }

    /* ---- request helpers ---- */

    private JsonObject get( final String query ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/page-ownership" );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        Mockito.doReturn( queryParam( query, "filter" ) ).when( req ).getParameter( "filter" );
        Mockito.doReturn( queryParam( query, "owner" ) ).when( req ).getParameter( "owner" );
        Mockito.doReturn( queryParam( query, "limit" ) ).when( req ).getParameter( "limit" );
        Mockito.doReturn( queryParam( query, "offset" ) ).when( req ).getParameter( "offset" );
        Mockito.doReturn( "GET" ).when( req ).getMethod();
        return invoke( req, null );
    }

    private JsonObject post( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/page-ownership" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( "POST" ).when( req ).getMethod();
        return invoke( req, body );
    }

    private JsonObject invoke( final HttpServletRequest req, final JsonObject body ) throws Exception {
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( req ).getReader();
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        return gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
    }

    private static String queryParam( final String query, final String key ) {
        if ( query == null || query.isEmpty() ) return null;
        final String q = query.startsWith( "?" ) ? query.substring( 1 ) : query;
        for ( final String pair : q.split( "&" ) ) {
            final String[] kv = pair.split( "=", 2 );
            if ( kv.length == 2 && kv[ 0 ].equals( key ) ) {
                return java.net.URLDecoder.decode( kv[ 1 ], java.nio.charset.StandardCharsets.UTF_8 );
            }
        }
        return null;
    }
}

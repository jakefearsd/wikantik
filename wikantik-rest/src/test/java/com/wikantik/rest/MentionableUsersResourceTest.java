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
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionableUsersResourceTest {

    private final Gson gson = new Gson();
    private UserDatabase db;
    private MentionableUsersResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        db = Mockito.mock( UserDatabase.class );
        final Map< String, UserProfile > byWikiName = new HashMap<>();
        byWikiName.put( "alice",  profile( "alice",  "Alice Anderson",   false ) );
        byWikiName.put( "alicia", profile( "alicia", "Alicia Aces",      false ) );
        byWikiName.put( "bob",    profile( "bob",    "Bob Builder",      false ) );
        byWikiName.put( "carol",  profile( "carol",  "Carol Carmichael", false ) );
        byWikiName.put( "locked", profile( "locked", "Locked Account",   true  ) );

        final Principal[] wikiNames = new Principal[] {
                new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "alicia", WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "bob",    WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "carol",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "locked", WikiPrincipal.WIKI_NAME )
        };
        Mockito.when( db.getWikiNames() ).thenReturn( wikiNames );
        for ( final Map.Entry< String, UserProfile > e : byWikiName.entrySet() ) {
            Mockito.when( db.findByWikiName( e.getKey() ) ).thenReturn( e.getValue() );
        }

        servlet = Mockito.spy( new MentionableUsersResource() );
        Mockito.doReturn( db ).when( servlet ).users();
    }

    private static UserProfile profile( final String login, final String full, final boolean locked ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getLoginName() ).thenReturn( login );
        Mockito.when( p.getFullname() ).thenReturn( full );
        Mockito.when( p.isLocked() ).thenReturn( locked );
        return p;
    }

    @Test
    void anonymous_access_returns_users() throws Exception {
        // No isAuthenticated stub — the endpoint is open by design.
        final JsonObject res = invoke( "?q=ali" );
        assertFalse( res.has( "error" ), "anonymous access should succeed: " + res );
        assertTrue( res.has( "users" ) );
        assertTrue( res.getAsJsonArray( "users" ).size() >= 1 );
    }

    @Test
    void prefix_matches_login() throws Exception {
        final JsonObject res = invoke( "?q=ali" );
        final JsonArray users = res.getAsJsonArray( "users" );
        // alice + alicia match by login prefix
        assertTrue( users.size() >= 2 );
        users.forEach( u -> {
            final String login = u.getAsJsonObject().get( "loginName" ).getAsString();
            assertTrue( login.startsWith( "ali" ) );
        } );
    }

    @Test
    void substring_matches_full_name_case_insensitive() throws Exception {
        final JsonObject res = invoke( "?q=builder" );
        final JsonArray users = res.getAsJsonArray( "users" );
        assertEquals( 1, users.size() );
        assertEquals( "bob", users.get( 0 ).getAsJsonObject().get( "loginName" ).getAsString() );
    }

    @Test
    void locked_users_are_excluded() throws Exception {
        final JsonObject res = invoke( "?q=locked" );
        assertEquals( 0, res.getAsJsonArray( "users" ).size() );
    }

    @Test
    void limit_default_is_8_and_clamped_to_max_10() throws Exception {
        // Empty q returns everything up to limit. Default 8; cap 10.
        final JsonObject defaultRes = invoke( "" );
        assertTrue( defaultRes.getAsJsonArray( "users" ).size() <= 8 );
        final JsonObject hugeRes = invoke( "?limit=999" );
        assertTrue( hugeRes.getAsJsonArray( "users" ).size() <= 10 );
        final JsonObject specificRes = invoke( "?limit=2" );
        assertEquals( 2, specificRes.getAsJsonArray( "users" ).size() );
    }

    @Test
    void invalid_limit_parameter_falls_back_to_default() throws Exception {
        // NumberFormatException branch in clampLimit: garbage input → DEFAULT_LIMIT (8).
        final JsonObject res = invoke( "?limit=not-a-number" );
        assertTrue( res.getAsJsonArray( "users" ).size() <= 8 );
    }

    @Test
    void vanished_profile_is_skipped_when_findByWikiName_throws_NoSuchPrincipalException() throws Exception {
        // Race condition: enumeration sees the wikiName but the profile is deleted
        // before findByWikiName() runs. Endpoint must skip silently, not 500.
        Mockito.when( db.findByWikiName( "alice" ) )
                .thenThrow( new NoSuchPrincipalException( "vanished" ) );
        final JsonObject res = invoke( "?q=ali" );
        assertFalse( res.has( "error" ), "vanished profile must be skipped, not propagated: " + res );
        // alicia still matches; alice does not.
        final JsonArray users = res.getAsJsonArray( "users" );
        users.forEach( u -> assertFalse(
                "alice".equals( u.getAsJsonObject().get( "loginName" ).getAsString() ),
                "alice's vanished profile must not appear in results" ) );
    }

    @Test
    void enumeration_failure_returns_500() throws Exception {
        // WikiSecurityException from getWikiNames() must surface as 500, not blow up.
        Mockito.when( db.getWikiNames() ).thenThrow( new WikiSecurityException( "ldap down" ) );
        final JsonObject res = invoke( "?q=any" );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 500, res.get( "status" ).getAsInt() );
    }

    @Test
    void profile_with_null_login_or_full_name_is_handled() throws Exception {
        // Cover the `login == null ? ""` and `full == null ? ""` defensive branches.
        // An empty-string match always succeeds when q is empty, so the row appears.
        final UserProfile sparse = Mockito.mock( UserProfile.class );
        Mockito.when( sparse.getLoginName() ).thenReturn( null );
        Mockito.when( sparse.getFullname() ).thenReturn( null );
        Mockito.when( sparse.isLocked() ).thenReturn( false );
        Mockito.when( db.findByWikiName( "sparse" ) ).thenReturn( sparse );
        // Add the wikiName to the enumeration set.
        final Principal[] expanded = new Principal[] {
                new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "sparse", WikiPrincipal.WIKI_NAME )
        };
        Mockito.when( db.getWikiNames() ).thenReturn( expanded );

        final JsonObject res = invoke( "" );
        assertFalse( res.has( "error" ), "null login/full name must not blow up: " + res );
        // Both rows present (q is empty so everything matches).
        assertTrue( res.getAsJsonArray( "users" ).size() >= 2 );
    }

    // ---- helpers ----

    private JsonObject invoke( final String query ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/users/mentionable" + query );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        Mockito.doReturn( paramOf( query, "q" ) ).when( req ).getParameter( "q" );
        Mockito.doReturn( paramOf( query, "limit" ) ).when( req ).getParameter( "limit" );
        Mockito.doReturn( "GET" ).when( req ).getMethod();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        return gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
    }

    private static String paramOf( final String query, final String key ) {
        if ( query == null || query.isEmpty() ) return null;
        for ( final String pair : query.replaceFirst( "^\\?", "" ).split( "&" ) ) {
            final String[] kv = pair.split( "=", 2 );
            if ( kv.length == 2 && kv[ 0 ].equals( key ) ) return kv[ 1 ];
        }
        return null;
    }
}

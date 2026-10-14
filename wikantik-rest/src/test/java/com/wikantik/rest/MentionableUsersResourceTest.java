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
        // uids deliberately do not contain the login, so a leaked login is detectable in the JSON.
        byWikiName.put( "alice",  profile( "uid-0001", "alice",  "Alice Anderson",   false ) );
        byWikiName.put( "alicia", profile( "uid-0002", "alicia", "Alicia Aces",      false ) );
        byWikiName.put( "bob",    profile( "uid-0003", "bob",    "Bob Builder",      false ) );
        byWikiName.put( "carol",  profile( "uid-0004", "carol",  "Carol Carmichael", false ) );
        byWikiName.put( "locked", profile( "uid-0005", "locked", "Locked Account",   true  ) );
        // A login that is an email address, with an unrelated display name.
        byWikiName.put( "zed",    profile( "uid-0006", "zed@example.test", "Quentin Quill", false ) );

        final Principal[] wikiNames = new Principal[] {
                new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "alicia", WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "bob",    WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "carol",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "locked", WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "zed",    WikiPrincipal.WIKI_NAME )
        };
        Mockito.when( db.getWikiNames() ).thenReturn( wikiNames );
        for ( final Map.Entry< String, UserProfile > e : byWikiName.entrySet() ) {
            Mockito.when( db.findByWikiName( e.getKey() ) ).thenReturn( e.getValue() );
        }

        servlet = Mockito.spy( new MentionableUsersResource() );
        Mockito.doReturn( db ).when( servlet ).users();
        // Signed-in by default; the anonymous/asserted cases override this explicitly.
        Mockito.doReturn( true ).when( servlet ).isAuthenticated( Mockito.any() );
    }

    private static UserProfile profile( final String uid, final String login, final String full,
                                        final boolean locked ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getUid() ).thenReturn( uid );
        Mockito.when( p.getLoginName() ).thenReturn( login );
        Mockito.when( p.getFullname() ).thenReturn( full );
        Mockito.when( p.isLocked() ).thenReturn( locked );
        return p;
    }

    @Test
    void authenticated_access_returns_id_and_display_name() throws Exception {
        final JsonObject res = invoke( "?q=ali" );
        assertFalse( res.has( "error" ), "signed-in access should succeed: " + res );
        assertTrue( res.has( "users" ) );
        assertTrue( res.getAsJsonArray( "users" ).size() >= 1 );
        final JsonObject first = res.getAsJsonArray( "users" ).get( 0 ).getAsJsonObject();
        assertEquals( "uid-0001", first.get( "id" ).getAsString() );
        assertEquals( "Alice Anderson", first.get( "displayName" ).getAsString() );
    }

    @Test
    void a_row_carries_exactly_id_and_displayName_no_login_and_no_full_name() throws Exception {
        final JsonObject res = invoke( "" );
        res.getAsJsonArray( "users" ).forEach( u ->
                assertEquals( java.util.Set.of( "id", "displayName" ), u.getAsJsonObject().keySet() ) );
    }

    @Test
    void no_login_name_appears_anywhere_in_the_response() throws Exception {
        final String body = invoke( "" ).toString();
        for ( final String login : new String[] { "\"alice\"", "\"alicia\"", "\"bob\"", "\"carol\"",
                "zed@example.test", "example.test" } ) {
            assertFalse( body.contains( login ), "login leaked (" + login + "): " + body );
        }
    }

    @Test
    void a_login_is_never_matched_so_a_member_cannot_probe_which_logins_exist() throws Exception {
        // zed@example.test is a real login; its display name is "Quentin Quill".
        assertEquals( 0, invoke( "?q=zed" ).getAsJsonArray( "users" ).size(), "login prefix must not match" );
        assertEquals( 0, invoke( "?q=zed@example.test" ).getAsJsonArray( "users" ).size() );
        assertEquals( 0, invoke( "?q=example" ).getAsJsonArray( "users" ).size() );
        // ...while the display name finds the same member.
        final JsonArray hit = invoke( "?q=quill" ).getAsJsonArray( "users" );
        assertEquals( 1, hit.size() );
        assertEquals( "uid-0006", hit.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void a_login_fragment_that_is_in_no_display_name_finds_nobody() throws Exception {
        // This member's login "mxyz" shares nothing with the display name "Dana Doe".
        final UserProfile loginOnly = profile( "uid-0007", "mxyz", "Dana Doe", false );
        Mockito.when( db.findByWikiName( "mxyz" ) ).thenReturn( loginOnly );
        Mockito.when( db.getWikiNames() ).thenReturn( new Principal[] {
                new WikiPrincipal( "mxyz", WikiPrincipal.WIKI_NAME ) } );
        assertEquals( 0, invoke( "?q=mxy" ).getAsJsonArray( "users" ).size() );
        assertEquals( 1, invoke( "?q=dana" ).getAsJsonArray( "users" ).size() );
    }

    @Test
    void anonymous_access_is_rejected_with_401_and_never_touches_the_user_database() throws Exception {
        // Login names are email addresses under SSO (login-name claim = email), so the
        // directory must not be enumerable by callers who are not signed in. This also
        // covers cookie-"asserted" sessions: Session.isAuthenticated() is false for them.
        Mockito.doReturn( false ).when( servlet ).isAuthenticated( Mockito.any() );
        final JsonObject res = invoke( "?q=ali" );
        assertFalse( res.has( "users" ), "no user rows may leak to an anonymous caller: " + res );
        assertTrue( res.has( "error" ) && res.get( "error" ).getAsBoolean(),
                "anonymous caller must get the JSON error envelope: " + res );
        assertEquals( 401, res.get( "status" ).getAsInt() );
        Mockito.verify( servlet, Mockito.never() ).users();
        Mockito.verifyNoInteractions( db );
    }

    @Test
    void substring_matches_display_name_case_insensitive() throws Exception {
        final JsonObject res = invoke( "?q=BUILDER" );
        final JsonArray users = res.getAsJsonArray( "users" );
        assertEquals( 1, users.size() );
        assertEquals( "uid-0003", users.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
        assertEquals( "Bob Builder", users.get( 0 ).getAsJsonObject().get( "displayName" ).getAsString() );
    }

    @Test
    void a_display_name_prefix_finds_every_member_whose_name_contains_it() throws Exception {
        final JsonArray users = invoke( "?q=ali" ).getAsJsonArray( "users" );
        assertEquals( 2, users.size(), "Alice Anderson and Alicia Aces" );
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
                "uid-0001".equals( u.getAsJsonObject().get( "id" ).getAsString() ),
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
    void a_member_with_no_display_name_or_no_uid_is_skipped_and_never_shown_by_login() throws Exception {
        // Nothing safe to show (no display name) or to key a mention on (no uid): skipped, not a 500,
        // and the login is not used as a fallback label.
        final UserProfile blankName = profile( "uid-8", "blankname@example.test", "   ", false );
        final UserProfile noUid = profile( null, "nouid@example.test", "No Uid", false );
        final UserProfile sparse = Mockito.mock( UserProfile.class );
        Mockito.when( sparse.getLoginName() ).thenReturn( null );
        Mockito.when( sparse.getFullname() ).thenReturn( null );
        Mockito.when( sparse.getUid() ).thenReturn( null );
        Mockito.when( sparse.isLocked() ).thenReturn( false );
        Mockito.when( db.findByWikiName( "blankname" ) ).thenReturn( blankName );
        Mockito.when( db.findByWikiName( "nouid" ) ).thenReturn( noUid );
        Mockito.when( db.findByWikiName( "sparse" ) ).thenReturn( sparse );
        Mockito.when( db.getWikiNames() ).thenReturn( new Principal[] {
                new WikiPrincipal( "alice",     WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "blankname", WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "nouid",     WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "sparse",    WikiPrincipal.WIKI_NAME ) } );

        final JsonObject res = invoke( "" );
        assertFalse( res.has( "error" ), "unusable profiles must not blow up: " + res );
        final JsonArray users = res.getAsJsonArray( "users" );
        assertEquals( 1, users.size(), "only alice is usable: " + res );
        assertEquals( "uid-0001", users.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
        assertFalse( res.toString().contains( "example.test" ), "no login fallback: " + res );
    }

    @Test
    void an_email_shaped_display_name_is_skipped_so_an_address_is_never_published() throws Exception {
        // A legacy account whose full name is its address; PublicIdentity masks such a name, so this
        // endpoint must not publish it as a "display name" either.
        final UserProfile emailName = profile( "uid-9", "legacy", "legacy@example.test", false );
        Mockito.when( db.findByWikiName( "legacy" ) ).thenReturn( emailName );
        Mockito.when( db.getWikiNames() ).thenReturn( new Principal[] {
                new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
                new WikiPrincipal( "legacy", WikiPrincipal.WIKI_NAME ) } );

        final JsonObject res = invoke( "" );
        assertEquals( 1, res.getAsJsonArray( "users" ).size(), res.toString() );
        assertFalse( res.toString().contains( "example.test" ), "no address in the body: " + res );
        assertEquals( 0, invoke( "?q=legacy" ).getAsJsonArray( "users" ).size() );
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

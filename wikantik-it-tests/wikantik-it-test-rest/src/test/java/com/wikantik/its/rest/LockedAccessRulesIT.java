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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #104 against a deployed wiki: a save that adds an access rule the wiki cannot read is a 422 naming it; a page locked by
 * such a rule (the {@code LockedAccessRulesFixture} startup page — no save can create one any more) is closed to
 * everyone but administrators exactly as any restricted page is, an administrator is told why it is locked, and may
 * still save other edits to it while the rule stays.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class LockedAccessRulesIT {

    private static final Gson GSON = new Gson();
    private static final String SUFFIX = UUID.randomUUID().toString().substring( 0, 8 );
    private static final String LOCKED = "LockedAccessRulesFixture";
    private static final String RULE = "[{DENY view Bob}]";
    private static final String RESTRICTED = "LockedRulesRestricted" + SUFFIX;
    private static final String NEW_PAGE = "LockedRulesNew" + SUFFIX;

    private static String baseUrl;
    private static HttpClient admin;

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        admin = loggedIn( "janne", "myP@5sw0rd" );
    }

    private static HttpClient client() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        // The session cookie is Secure; the store would not send it over plain http without this.
        final CookieHandler secureOverHttp = new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > headers ) throws IOException {
                return cm.get( https( uri ), headers );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > headers ) throws IOException {
                cm.put( uri, headers );
            }

            private URI https( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
        return HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).cookieHandler( secureOverHttp ).build();
    }

    private static HttpClient loggedIn( final String login, final String password ) throws Exception {
        final HttpClient c = client();
        final HttpResponse< String > resp = send( c, "POST", "/api/auth/login",
                GSON.toJson( Map.of( "username", login, "password", password ) ) );
        assertEquals( 200, resp.statusCode(), login + " login: " + resp.body() );
        return c;
    }

    private static HttpResponse< String > send( final HttpClient c, final String method, final String path, final String json )
            throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).header( "Accept", "application/json" );
        if ( json == null ) {
            b.GET();
        } else {
            b.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return c.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > get( final HttpClient c, final String page ) throws Exception {
        return send( c, "GET", "/api/pages/" + page, null );
    }

    private static HttpResponse< String > putContent( final String page, final String content ) throws Exception {
        return send( admin, "PUT", "/api/pages/" + page, GSON.toJson( Map.of( "content", content ) ) );
    }

    private static JsonObject json( final HttpResponse< String > resp ) {
        return GSON.fromJson( resp.body(), JsonObject.class );
    }

    @Test
    @Order( 1 )
    void aSaveThatAddsADenyRuleIs422AndWritesNothing() throws Exception {
        final HttpResponse< String > resp = putContent( NEW_PAGE, "Intro.\n\n" + RULE + "\n\nBody.\n" );

        assertEquals( 422, resp.statusCode(), resp.body() );
        final JsonObject body = json( resp );
        assertEquals( "access_rule_shape", body.get( "error" ).getAsString(), resp.body() );
        final JsonObject violation = body.getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject();
        assertEquals( "ACCESS_RULE_SHAPE", violation.get( "code" ).getAsString() );
        assertEquals( "DENY", violation.get( "shape" ).getAsString() );
        assertTrue( violation.get( "line" ).getAsInt() >= 3, "body line 3, or below a frontmatter block the save adds: " + resp.body() );
        assertTrue( violation.get( "message" ).getAsString().contains( "put it in `code`" ), resp.body() );
        assertEquals( 404, get( admin, NEW_PAGE ).statusCode(), "the refused page was not created" );
    }

    @Test
    @Order( 2 )
    void anAdministratorIsToldWhyThePageIsLocked() throws Exception {
        final HttpResponse< String > resp = get( admin, LOCKED );

        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject accessRules = json( resp ).getAsJsonObject( "accessRules" );
        assertTrue( accessRules.get( "locked" ).getAsBoolean(), resp.body() );
        final JsonArray reasons = accessRules.getAsJsonArray( "reasons" );
        assertEquals( 1, reasons.size(), resp.body() );
        final JsonObject reason = reasons.get( 0 ).getAsJsonObject();
        assertEquals( "DENY", reason.get( "shape" ).getAsString() );
        assertEquals( 6, reason.get( "line" ).getAsInt() );
        assertEquals( RULE, reason.get( "text" ).getAsString() );
    }

    @Test
    @Order( 3 )
    void everyoneElseIsRefusedTheLockedPageAsAnyRestrictedPage() throws Exception {
        assertEquals( 200, putContent( RESTRICTED, "[{ALLOW view Admin}]\n\nAdmins only.\n" ).statusCode() );
        final HttpClient alice = loggedIn( "Alice", "password" );
        final HttpClient anonymous = client();

        for ( final HttpClient c : List.of( alice, anonymous ) ) {
            final HttpResponse< String > restricted = get( c, RESTRICTED );
            final HttpResponse< String > locked = get( c, LOCKED );
            assertTrue( restricted.statusCode() == 403 || restricted.statusCode() == 404, "control: " + restricted.body() );
            assertEquals( restricted.statusCode(), locked.statusCode(), "a locked page is refused like a restricted one" );
            assertFalse( locked.body().contains( "accessRules" ) || locked.body().contains( "DENY" ), locked.body() );
        }
    }

    @Test
    @Order( 4 )
    void anAdministratorSavesOtherEditsAndTheLockStays() throws Exception {
        final String edited = "# Locked Access Rules Fixture\n\nEdited by LockedAccessRulesIT " + SUFFIX + ".\n\n" + RULE
                + "\n\nThe body after the rule.\n";

        final HttpResponse< String > resp = putContent( LOCKED, edited );

        assertEquals( 200, resp.statusCode(), "an unchanged disputed span does not block other edits: " + resp.body() );
        final JsonObject page = json( get( admin, LOCKED ) );
        assertTrue( page.get( "content" ).getAsString().contains( "Edited by LockedAccessRulesIT " + SUFFIX ), page.toString() );
        assertTrue( page.getAsJsonObject( "accessRules" ).get( "locked" ).getAsBoolean(), page.toString() );
    }

    @Test
    @Order( 5 )
    void theLockedPageIsListedForAdministrators() throws Exception {
        final HttpResponse< String > resp = send( admin, "GET", "/admin/content/locked-pages", null );

        assertEquals( 200, resp.statusCode(), resp.body() );
        final boolean listed = json( resp ).getAsJsonArray( "pages" ).asList().stream()
                .anyMatch( p -> LOCKED.equals( p.getAsJsonObject().get( "name" ).getAsString() ) );
        assertTrue( listed, resp.body() );
    }
}

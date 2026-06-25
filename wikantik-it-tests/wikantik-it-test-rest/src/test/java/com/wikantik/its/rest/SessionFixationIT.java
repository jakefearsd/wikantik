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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end session-fixation defense: a successful form login must rotate the {@code JSESSIONID}
 * (so a pre-auth ID an attacker fixed on the victim cannot be replayed), and the rotated session
 * must stay authenticated — proving the {@code SessionMonitor} entry is remapped to the new ID.
 * Cookies are managed manually (no auto cookie store) so the test controls exactly which
 * {@code JSESSIONID} it sends and can observe the rotation. Unit counterpart:
 * {@code SessionMonitorTest#updateSessionId_remapsAuthenticatedSessionToTheNewId}.
 */
public class SessionFixationIT {

    private static final Gson GSON = new Gson();
    /** Seeded admin in the IT war (same as AuditLogIT). */
    private static final String ADMIN = "janne";
    private static final String ADMIN_PW = "myP@5sw0rd";

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();
    }

    @Test
    void successfulLogin_rotatesJsessionId_andRotatedSessionStaysAuthenticated() throws Exception {
        // 1. A failed login still establishes a server session (AuthResource calls getSession(true)
        //    before authenticating) — this is the ID an attacker would try to fixate.
        final HttpResponse< String > failed = login( null, ADMIN, "definitely-the-wrong-password" );
        assertNotEquals( 200, failed.statusCode(), "wrong password must not authenticate" );
        final String preId = jsessionId( failed );
        assertNotNull( preId, "the login attempt should establish a JSESSIONID to fixate on" );

        // 2. A successful login on that very session must rotate the ID.
        final HttpResponse< String > ok = login( preId, ADMIN, ADMIN_PW );
        assertEquals( 200, ok.statusCode(), "admin login should succeed: " + ok.body() );
        final String postId = jsessionId( ok );
        assertNotNull( postId, "a successful login must set the rotated JSESSIONID" );
        assertNotEquals( preId, postId,
                "JSESSIONID must change after a successful login (session-fixation defense)" );

        // 3. The rotated session must be authenticated as admin — proves the SessionMonitor remap
        //    (an admin-only endpoint returns 200 when authenticated, 403/redirect otherwise).
        assertEquals( 200, adminProbe( postId ).statusCode(),
                "the rotated session must remain authenticated as admin (SessionMonitor remap)" );

        // 4. The pre-rotation ID must no longer be an authenticated session.
        assertNotEquals( 200, adminProbe( preId ).statusCode(),
                "the pre-rotation JSESSIONID must not resolve to an authenticated session" );
    }

    private HttpResponse< String > login( final String jsessionId, final String user, final String pw )
            throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of( "username", user, "password", pw ) );
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" );
        if ( jsessionId != null ) {
            b = b.header( "Cookie", "JSESSIONID=" + jsessionId );
        }
        return client.send( b.POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** Hits an admin-only endpoint with the given session; 200 iff authenticated as admin. */
    private HttpResponse< String > adminProbe( final String jsessionId )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/admin/audit?limit=1" ) )
                .header( "Cookie", "JSESSIONID=" + jsessionId )
                .header( "Accept", "application/json" ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static String jsessionId( final HttpResponse< ? > resp ) {
        for ( final String setCookie : resp.headers().allValues( "set-cookie" ) ) {
            for ( final String part : setCookie.split( ";" ) ) {
                final String p = part.trim();
                if ( p.startsWith( "JSESSIONID=" ) ) {
                    return p.substring( "JSESSIONID=".length() );
                }
            }
        }
        return null;
    }
}

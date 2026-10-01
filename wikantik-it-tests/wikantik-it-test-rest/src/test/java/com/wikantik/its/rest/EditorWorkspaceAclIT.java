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
import java.net.CookieHandler;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACL IT for the editor-workspace read surfaces: {@code GET /api/pages/{name}/preview} and
 * {@code POST /api/mentions/scan} must not disclose restricted pages to anonymous callers.
 */
public class EditorWorkspaceAclIT {

    private static final Gson GSON = new Gson();
    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > requestHeaders ) throws IOException {
                return cm.get( asHttps( uri ), requestHeaders );
            }

            @Override
            public void put( final URI uri,
                    final Map< String, List< String > > responseHeaders ) throws IOException {
                cm.put( uri, responseHeaders );
            }

            private URI asHttps( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
    }

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > post( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > getAnonymous( final String path ) throws IOException, InterruptedException {
        return getAnonymous( path, "text/markdown" );
    }

    private HttpResponse< String > getAnonymous( final String path, final String accept )
            throws IOException, InterruptedException {
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        return anon.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", accept ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private void loginAsAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/login",
                GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        post( "/api/auth/logout", "{}" );
    }

    private static HttpResponse< String > postAnonymous( final String path, final String json ) throws Exception {
        final HttpClient anon = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
        return anon.send( HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    void restrictedPagesStayInvisibleToPreviewAndMentionScan() throws Exception {
        loginAsAdmin();
        assertEquals( 200, put( "/api/pages/EwAclSecretTopic",
                "{\"content\":\"[{ALLOW view Admin}]\\n\\nSecret body EWSECRET.\",\"changeNote\":\"it\"}" ).statusCode() );
        assertEquals( 200, put( "/api/pages/EwAclPublicTopic",
                "{\"content\":\"Public body.\",\"changeNote\":\"it\"}" ).statusCode() );
        logoutAdmin();

        final HttpResponse< String > secret = getAnonymous( "/api/pages/EwAclSecretTopic/preview", "application/json" );
        final HttpResponse< String > missing = getAnonymous( "/api/pages/EwAclNoSuchTopic/preview", "application/json" );
        assertEquals( 404, secret.statusCode() );
        assertEquals( 404, missing.statusCode() );
        assertEquals( missing.body(), secret.body() );
        assertFalse( secret.body().contains( "EWSECRET" ) );
        assertEquals( 200, getAnonymous( "/api/pages/EwAclPublicTopic/preview", "application/json" ).statusCode() );

        final String draft = "{\"page\":\"Scratch\",\"text\":\"About the ew acl secret topic and the ew acl public topic.\"}";
        HttpResponse< String > scan = postAnonymous( "/api/mentions/scan", draft );
        for ( int i = 0; i < 30 && scan.statusCode() == 503; i++ ) {   // structural index still warming
            Thread.sleep( 1000 );
            scan = postAnonymous( "/api/mentions/scan", draft );
        }
        assertEquals( 200, scan.statusCode(), scan.body() );
        assertTrue( scan.body().contains( "EwAclPublicTopic" ), scan.body() );
        assertFalse( scan.body().contains( "EwAclSecretTopic" ), scan.body() );
    }
}

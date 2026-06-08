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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression IT for the {@code /wiki/{slug}?format=md|json} raw-content endpoint
 * (WikiPageFormatFilter): an ACL-restricted page must NOT be served to anonymous
 * callers. Previously the filter performed no permission check and leaked any page.
 */
public class WikiPageFormatAclIT {

    private static final Gson GSON = new Gson();
    private static final String PUBLIC_PAGE = "FormatAclPublicPg";
    private static final String SECRET_PAGE = "FormatAclSecretPg";

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
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        return anon.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "text/markdown" ).GET().build(),
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

    @Test
    void restrictedPageIsNotServedToAnonymousViaFormatMd() throws Exception {
        loginAsAdmin();
        try {
            put( "/api/pages/" + PUBLIC_PAGE, GSON.toJson(
                    Map.of( "content", "Public body content for the format ACL IT.",
                            "changeNote", "WikiPageFormatAclIT" ) ) );
            put( "/api/pages/" + SECRET_PAGE, GSON.toJson(
                    Map.of( "content", "[{ALLOW view Admin}]\n\nSecret body content for the format ACL IT.",
                            "changeNote", "WikiPageFormatAclIT" ) ) );

            // Admin (logged in) may read the restricted page raw.
            final HttpResponse< String > adminSecret = get( "/wiki/" + SECRET_PAGE + "?format=md" );
            assertEquals( 200, adminSecret.statusCode(),
                    "admin should read the restricted page raw: " + adminSecret.statusCode() );
        } finally {
            logoutAdmin();
        }

        // Anonymous: the public page is served...
        final HttpResponse< String > anonPublic = getAnonymous( "/wiki/" + PUBLIC_PAGE + "?format=md" );
        assertEquals( 200, anonPublic.statusCode(),
                "public page must be served to anonymous: " + anonPublic.statusCode() );

        // ...but the ACL-restricted page must NOT be (no body leak). 404 hides its existence.
        final HttpResponse< String > anonSecret = getAnonymous( "/wiki/" + SECRET_PAGE + "?format=md" );
        assertEquals( 404, anonSecret.statusCode(),
                "restricted page must NOT be served to anonymous via format=md (got "
                        + anonSecret.statusCode() + ")" );
        assertTrue( !anonSecret.body().contains( "Secret body content" ),
                "restricted page body must not leak: " + anonSecret.body() );
    }
}

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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level proof that the {@code CSPFilter} and {@code ClickJackFilter} security headers are
 * actually emitted on live responses — the runtime counterpart to the static
 * {@code SecurityHeaderRegistrationTest} (web.xml registration). These filters are mapped to
 * {@code /*} and add their header unconditionally before the chain runs, so the headers appear on
 * every response regardless of status; the test deliberately uses redirect-following OFF and
 * asserts headers on both an HTML route and an API route to prove {@code /*} coverage.
 */
public class SecurityHeadersIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NEVER )
                .build();
    }

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static void assertSecurityHeaders( final HttpResponse< String > resp, final String path ) {
        final Optional< String > csp = resp.headers().firstValue( "Content-Security-Policy" );
        assertTrue( csp.isPresent(), "Content-Security-Policy header must be present on " + path );
        final String policy = csp.get();
        assertTrue( policy.contains( "default-src 'self'" ),
                "CSP must restrict default-src to 'self' on " + path + "; was: " + policy );
        assertTrue( policy.contains( "frame-ancestors 'none'" ),
                "CSP must set frame-ancestors 'none' (clickjacking) on " + path + "; was: " + policy );
        assertTrue( policy.contains( "object-src 'none'" ),
                "CSP must set object-src 'none' on " + path + "; was: " + policy );

        final Optional< String > xfo = resp.headers().firstValue( "X-Frame-Options" );
        assertTrue( xfo.isPresent(), "X-Frame-Options header must be present on " + path );
        assertEquals( "DENY", xfo.get().toUpperCase(),
                "X-Frame-Options must be DENY on " + path );
    }

    @Test
    void htmlRoute_carriesCspAndFrameOptions() throws Exception {
        // The SPA root (an HTML response) must carry the security headers.
        assertSecurityHeaders( get( "/" ), "/" );
    }

    @Test
    void apiRoute_carriesCspAndFrameOptions() throws Exception {
        // A public API route — proves the /* mapping covers non-HTML responses too.
        assertSecurityHeaders( get( "/api/changes?since=2020-01-01T00:00:00Z" ), "/api/changes" );
    }

    @Test
    void ssrWikiPage_carriesCspAndFrameOptions() throws Exception {
        // The real wiki content surface: /wiki/* is served by SpaRoutingFilter, which writes the
        // server-rendered index.html and returns WITHOUT calling chain.doFilter. The security-header
        // filters must therefore be ordered ahead of it; otherwise no rendered page carries any
        // security header. Regression guard for the filter-ordering fix — the original /-only and
        // /api-only cases above both miss this short-circuiting SSR path.
        final HttpResponse< String > resp = get( "/wiki/Main" );
        assertEquals( 200, resp.statusCode(), "/wiki/Main must render the SPA shell (HTTP 200)" );
        assertSecurityHeaders( resp, "/wiki/Main" );
    }
}

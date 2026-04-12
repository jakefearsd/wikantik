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
package com.wikantik.its;

import com.wikantik.its.environment.Env;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Minimal HTTP seed helper used by {@code HubDiscoveryAdminIT} to prepare backend
 * state without going through the browser. Uses basic auth against Janne's test
 * credentials, so the wiki must be configured to accept basic auth on the admin
 * REST endpoints during integration-test runs.
 *
 * <p>The helper is deliberately low-level. A higher-level seeder would couple
 * the tests to the REST surface in ways that make breakages slower to diagnose.
 */
public final class RestSeedHelper {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private RestSeedHelper() {}

    private static String authHeader() {
        final String userPass = Env.LOGIN_JANNE_USERNAME + ":" + Env.LOGIN_JANNE_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(
            userPass.getBytes( StandardCharsets.UTF_8 ) );
    }

    /** Write a wiki page via the page REST API. Uses raw markdown content. */
    public static void writePage( final String name, final String markdown ) throws Exception {
        final String url = Env.TESTS_BASE_URL + "/api/pages/" + name;
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", authHeader() )
            .header( "Content-Type", "application/json" )
            .PUT( HttpRequest.BodyPublishers.ofString(
                "{\"content\":" + jsonString( markdown ) + "}" ) )
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "writePage failed: " + resp.statusCode() + " " + resp.body() );
        }
    }

    /**
     * Writes a page whose content already includes a YAML frontmatter block.
     * Used by hub-overview tests to seed hub pages without going through the
     * normal create-then-add-frontmatter flow. {@link #writePage} passes the
     * raw markdown body through unchanged, so this is a thin alias that
     * documents intent at the call site.
     */
    public static void writePageWithFrontmatter( final String name, final String content ) throws Exception {
        writePage( name, content );
    }

    /** POST /admin/knowledge/hub-discovery/run; returns the raw JSON body. */
    public static String runDiscovery() throws Exception {
        return post( "/admin/knowledge/hub-discovery/run", "" );
    }

    /**
     * Forces a synchronous content-model retrain against the live admin REST
     * endpoint, driven from inside the currently-open browser session so the
     * request carries the UI-login cookie (admin REST endpoints reject the
     * basic-auth header because there is no BASIC login-config in web.xml).
     *
     * <p>The discovery pipeline's candidate pool comes from
     * {@code TfidfModel.getEntityNames()}, which only updates when the model is
     * retrained — the {@code kge-retrain} scheduler runs every N minutes by
     * default, far too slow for an IT. Call this after seeding pages and
     * before {@link #runDiscovery()} so freshly-written pages appear in the
     * candidate pool.
     *
     * <p>Requires the test to have already performed a browser UI login, so a
     * session cookie exists in the driver.
     */
    public static void retrainContentModelViaBrowser() {
        // fetch() from the browser session so the request carries the UI-login
        // cookie. The URL is prefixed with window.__WIKANTIK_BASE__ so it
        // resolves against the correct context path (e.g. /wikantik-it-test-custom).
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/admin/knowledge/embeddings/retrain-content', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin'
            })
            .then(r => r.text().then(body => ({ status: r.status, body })))
            .then(res => cb(res))
            .catch(err => cb({ status: -1, body: String(err) }));
            """;
        final Object result = com.codeborne.selenide.Selenide.executeAsyncJavaScript( script );
        if ( result instanceof java.util.Map< ?, ? > m ) {
            final Object status = m.get( "status" );
            if ( status instanceof Number n && n.intValue() >= 200 && n.intValue() < 300 ) {
                return;
            }
            throw new IllegalStateException( "retrainContentModelViaBrowser failed: "
                + status + " " + m.get( "body" ) );
        }
        throw new IllegalStateException( "retrainContentModelViaBrowser: unexpected result "
            + ( result == null ? "null" : result.getClass().getName() ) );
    }

    /** GET /admin/knowledge/hub-discovery/proposals; returns the raw JSON body. */
    public static String listProposals() throws Exception {
        return get( "/admin/knowledge/hub-discovery/proposals?limit=50" );
    }

    /** Directly insert a proposal via the REST admin test seam, if one exists; otherwise use runDiscovery. */
    public static String post( final String path, final String jsonBody ) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( Env.TESTS_BASE_URL + path ) )
            .header( "Authorization", authHeader() )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "POST " + path + " failed: "
                + resp.statusCode() + " " + resp.body() );
        }
        return resp.body();
    }

    public static String get( final String path ) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( Env.TESTS_BASE_URL + path ) )
            .header( "Authorization", authHeader() )
            .GET()
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "GET " + path + " failed: "
                + resp.statusCode() + " " + resp.body() );
        }
        return resp.body();
    }

    private static String jsonString( final String s ) {
        final StringBuilder sb = new StringBuilder( "\"" );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch ( c ) {
                case '"' -> sb.append( "\\\"" );
                case '\\' -> sb.append( "\\\\" );
                case '\n' -> sb.append( "\\n" );
                case '\r' -> sb.append( "\\r" );
                case '\t' -> sb.append( "\\t" );
                default -> sb.append( c );
            }
        }
        return sb.append( '"' ).toString();
    }
}

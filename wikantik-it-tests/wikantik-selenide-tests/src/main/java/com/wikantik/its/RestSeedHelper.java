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

    /** POST /admin/knowledge-graph/hub-discovery/run; returns the raw JSON body. */
    public static String runDiscovery() throws Exception {
        return post( "/admin/knowledge-graph/hub-discovery/run", "" );
    }

    /**
     * Polls {@code GET /admin/users} (a cheap, always-wired admin endpoint that
     * passes through {@code AdminAuthFilter}) until it returns 200, with a
     * 3-second budget and 50 ms intervals.
     *
     * <p>Eliminates a JDBC-IT-profile race where the post-login session principal
     * binding occasionally lags behind {@code LoginPage.performLogin}'s
     * {@code data-authenticated=true} check — {@code performLogin} returns as
     * soon as React's auth state flips, but {@code AdminAuthFilter}'s view of
     * the session principal (which goes through {@code GroupManager.isUserInRole}
     * on a DB-backed authorization manager) can briefly trail by a few ms. The
     * symptom is an isolated 403 on the FIRST admin call right after login;
     * subsequent calls in the same test session always succeed.
     *
     * <p>Idempotent and fast on the happy path: one HTTP round-trip when warm.
     * Throws {@link IllegalStateException} if the budget expires without a 200,
     * which signals a real auth misconfiguration rather than a propagation race.
     */
    public static void awaitAdminReady() {
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            const deadline = Date.now() + 3000;
            const poll = () => {
                fetch(base + '/admin/users', {
                    method: 'GET',
                    headers: { 'Accept': 'application/json' },
                    credentials: 'same-origin'
                })
                .then(r => {
                    if (r.status === 200) { cb({ status: 200, body: '' }); return; }
                    if (Date.now() > deadline) {
                        r.text().then(b => cb({ status: r.status, body: b }));
                        return;
                    }
                    setTimeout(poll, 50);
                })
                .catch(err => {
                    if (Date.now() > deadline) { cb({ status: -1, body: String(err) }); return; }
                    setTimeout(poll, 50);
                });
            };
            poll();
            """;
        final Object result = com.codeborne.selenide.Selenide.executeAsyncJavaScript( script );
        if ( result instanceof java.util.Map< ?, ? > m ) {
            final Object status = m.get( "status" );
            if ( status instanceof Number n && n.intValue() == 200 ) return;
            throw new IllegalStateException(
                "awaitAdminReady: 3s budget expired without 200 (last status="
                + status + " body=" + m.get( "body" ) + ")" );
        }
        throw new IllegalStateException( "awaitAdminReady: unexpected JS result "
            + ( result == null ? "null" : result.getClass().getName() ) );
    }

    /**
     * Seeds a synthetic hub-discovery proposal via the test-only fixture seam
     * at {@code /admin/knowledge-graph/hub-discovery/proposals/seed}. Driven from
     * the browser so the UI session cookie authorises the admin endpoint.
     * Caller must already be logged in. Returns the generated proposal id.
     *
     * <p>The fixture seam is gated by the {@code wikantik.test.fixture-seam.enabled}
     * system property on the server JVM (set in the integration-tests Maven profile).</p>
     */
    public static int seedHubDiscoveryProposal( final String suggestedName,
                                                 final String exemplarPage,
                                                 final java.util.List< String > members ) {
        awaitAdminReady();
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            const body = JSON.stringify({
                suggested_name: arguments[0],
                exemplar_page:  arguments[1],
                members:        arguments[2],
                coherence:      0.9
            });
            fetch(base + '/admin/knowledge-graph/hub-discovery/proposals/seed', {
                method: 'POST',
                headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body
            })
            .then(r => r.text().then(b => ({ status: r.status, body: b })))
            .then(res => cb(res))
            .catch(err => cb({ status: -1, body: String(err) }));
            """;
        final Object result = com.codeborne.selenide.Selenide.executeAsyncJavaScript(
            script, suggestedName, exemplarPage, members );
        if ( result instanceof java.util.Map< ?, ? > m ) {
            final Object status = m.get( "status" );
            if ( status instanceof Number n && n.intValue() >= 200 && n.intValue() < 300 ) {
                final String responseBody = String.valueOf( m.get( "body" ) );
                final com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString( responseBody ).getAsJsonObject();
                return json.get( "id" ).getAsInt();
            }
            throw new IllegalStateException( "seedHubDiscoveryProposal failed: "
                + status + " " + m.get( "body" ) );
        }
        throw new IllegalStateException( "seedHubDiscoveryProposal: unexpected result "
            + ( result == null ? "null" : result.getClass().getName() ) );
    }

    /**
     * Seed a Knowledge Graph node via {@code POST /admin/knowledge-graph/nodes},
     * driven from the browser so the UI session cookie authorises the admin
     * endpoint (basic auth is rejected by {@code AdminAuthFilter}).
     * Caller must already be logged in. Returns the raw JSON body.
     */
    public static String seedKgNode( final String name, final String nodeType,
                                      final String sourcePage ) {
        awaitAdminReady();
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            const body = JSON.stringify({
                name: arguments[0], node_type: arguments[1], source_page: arguments[2]
            });
            fetch(base + '/admin/knowledge-graph/nodes', {
                method: 'POST',
                headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body
            })
            .then(r => r.text().then(b => ({ status: r.status, body: b })))
            .then(res => cb(res))
            .catch(err => cb({ status: -1, body: String(err) }));
            """;
        final Object result = com.codeborne.selenide.Selenide.executeAsyncJavaScript(
            script, name, nodeType, sourcePage );
        if ( result instanceof java.util.Map< ?, ? > m ) {
            final Object status = m.get( "status" );
            if ( status instanceof Number n && n.intValue() >= 200 && n.intValue() < 300 ) {
                return String.valueOf( m.get( "body" ) );
            }
            throw new IllegalStateException( "seedKgNode failed: "
                + status + " " + m.get( "body" ) );
        }
        throw new IllegalStateException( "seedKgNode: unexpected result "
            + ( result == null ? "null" : result.getClass().getName() ) );
    }

    /**
     * GET /admin/knowledge-graph/hub-discovery/proposals driven from the browser session
     * so the UI-login cookie authorises the admin endpoint (basic auth is rejected
     * by {@code AdminAuthFilter}). Returns the raw JSON body.
     */
    public static String listProposals() {
        awaitAdminReady();
        final String script = """
            const cb = arguments[arguments.length - 1];
            const base = window.__WIKANTIK_BASE__ || '';
            fetch(base + '/admin/knowledge-graph/hub-discovery/proposals?limit=50', {
                method: 'GET',
                headers: { 'Accept': 'application/json' },
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
                return String.valueOf( m.get( "body" ) );
            }
            throw new IllegalStateException( "listProposals failed: "
                + status + " " + m.get( "body" ) );
        }
        throw new IllegalStateException( "listProposals: unexpected result "
            + ( result == null ? "null" : result.getClass().getName() ) );
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

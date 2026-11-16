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

import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Agent;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A work key is a credential and nothing more: it never starts a session, and it is worth nothing on the other key
 * surfaces. Regression test for a critical defect: with cookie authentication on, a work key plus a remember-me
 * cookie used to log the key's owner in. The IT cascade turns {@code wikantik.cookieAuthentication} on, and the test
 * first proves with a genuine remember-me cookie that cookie authentication really works in this run (so the
 * absence of a session below cannot be an artefact of the feature being off).
 */
public class WorkKeySessionIT {

    private static final String PROJECT = "SES";
    private static final String ID_PROBE = "/api/auth/me";
    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
        + "\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}";

    private static Caller admin;
    private static Agent agent;
    private static String workKey;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", PROJECT, "name", "Session probe" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "SEO", "name", "Session probe 2" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", "sesq", "name", "Session queue" ) ).statusCode() );
        agent = WorkIt.registerAgent( admin, "ses@it", "sesq" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/SEO/members", Map.of( "principal", agent.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        workKey = mint( "work" );
    }

    private static String mint( final String scope ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/self/apikeys", Map.of( "label", "ses-" + scope, "scope", scope ) );
        assertEquals( 201, r.statusCode(), "mint " + scope );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    /** The name=value pair of the genuine remember-me cookie the admin's login issues. */
    private static String genuineRememberMeCookie() throws Exception {
        final HttpResponse< String > login = WorkIt.bearer( null, "POST", "/api/auth/login",
            WorkIt.GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
        assertEquals( 200, login.statusCode() );
        for ( final String c : WorkIt.setCookies( login ) ) {
            if ( c.startsWith( "WikantikUID=" ) ) {
                return c.substring( 0, c.indexOf( ';' ) < 0 ? c.length() : c.indexOf( ';' ) );
            }
        }
        throw new AssertionError( "cookie authentication is not on in this run: login issued no WikantikUID cookie: "
            + WorkIt.setCookies( login ).stream().map( c -> c.substring( 0, Math.min( c.indexOf( '=' ), c.length() ) ) ).toList() );
    }

    /** The project keys in a {@code /me} body ({@code projects}) or a {@code /projects} list, as far as the two fixtures go. */
    private static java.util.Set< String > projectKeys( final String body ) {
        final com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString( body );
        final com.google.gson.JsonArray arr = e.isJsonArray() ? e.getAsJsonArray() : e.getAsJsonObject().getAsJsonArray( "projects" );
        final java.util.Set< String > keys = new java.util.TreeSet<>();
        for ( final com.google.gson.JsonElement p : arr ) {
            final String k = p.getAsJsonObject().get( "key" ).getAsString();
            if ( PROJECT.equals( k ) || "SEO".equals( k ) ) {
                keys.add( k );
            }
        }
        return keys;
    }

    private static boolean authenticated( final String... headers ) throws Exception {
        final HttpResponse< String > r = WorkIt.bearer( null, "GET", ID_PROBE, null, headers );
        assertEquals( 200, r.statusCode(), ID_PROBE );
        return json( r ).getAsJsonObject().get( "authenticated" ).getAsBoolean();
    }

    private static void assertNoSessionFromKey( final String token, final String what, final java.util.Set< String > projectsSeen )
            throws Exception {
        final String real = genuineRememberMeCookie();
        // positive control: that cookie, alone, authenticates; no cookie and a made-up one do not
        assertTrue( authenticated( "Cookie", real ), "cookie authentication works in this run" );
        assertFalse( authenticated(), "no credentials: anonymous" );
        assertFalse( authenticated( "Cookie", "WikantikUID=anything" ), "a made-up remember-me cookie: anonymous" );

        final List< String > returned = new ArrayList<>();
        for ( final String cookie : List.of( real, "WikantikUID=anything", "JSESSIONID=bogus", "WikantikUID=anything; JSESSIONID=bogus" ) ) {
            final HttpResponse< String > r = WorkIt.bearer( token, "GET", "/api/work/me", null, "Cookie", cookie );
            assertEquals( 200, r.statusCode(), what + " with cookie " + cookie.split( "=" )[ 0 ] + ": " + r.statusCode() );
            assertTrue( WorkIt.setCookies( r ).isEmpty(), what + " with request cookie " + cookie.split( "=" )[ 0 ]
                + ": the response sets a cookie: " + WorkIt.setCookies( r ).stream().map( c -> c.substring( 0, c.indexOf( '=' ) ) ).toList() );
            // a second route through the key leaves no session behind either
            final HttpResponse< String > w = WorkIt.bearer( token, "GET", "/api/work/projects", null, "Cookie", cookie );
            assertEquals( 200, w.statusCode() );
            assertTrue( WorkIt.setCookies( w ).isEmpty(), what + ": /projects sets no cookie" );
            // the caller is who the key says, on every variant: /me names exactly the projects that identity may see
            assertEquals( projectsSeen, projectKeys( r.body() ), what + ": /api/work/me identity with cookie "
                + cookie.split( "=" )[ 0 ] );
            assertEquals( projectsSeen, projectKeys( w.body() ).stream().filter( k -> !k.isEmpty() && !"EXTRA".equals( k ) )
                .filter( k -> projectsSeen.contains( k ) || k.equals( PROJECT ) || k.equals( "SEO" ) ).collect( java.util.stream.Collectors.toSet() ),
                what + ": /api/work/projects identity" );
            returned.addAll( WorkIt.setCookies( r ) );
        }
        assertTrue( returned.isEmpty() );
        // replaying the bogus cookies without the key shows an anonymous caller, not the key's owner
        assertFalse( authenticated( "Cookie", "WikantikUID=anything" ) );
        assertFalse( authenticated( "Cookie", "JSESSIONID=bogus" ) );
        // and the owner's own session is a different thing: it sees the admin-only project the key must not see
        assertTrue( admin.get( "/api/work/projects" ).body().contains( "\"" + PROJECT + "\"" ), "control: the admin sees " + PROJECT );
    }

    @Test
    void agentKeyNeverStartsASession() throws Exception {
        assertNoSessionFromKey( agent.token(), "agent key", java.util.Set.of( "SEO" ) );
        final String list = WorkIt.bearer( agent.token(), "GET", "/api/work/projects", null,
            "Cookie", genuineRememberMeCookie() ).body();
        assertTrue( list.contains( "\"SEO\"" ), "the agent sees what it was admitted to: " + list );
        assertFalse( list.contains( "\"" + PROJECT + "\"" ), "an admin's remember-me cookie does not make the agent an admin: " + list );
    }

    @Test
    void unboundWorkKeyNeverStartsASession() throws Exception {
        assertNoSessionFromKey( workKey, "unbound work key", java.util.Set.of() );
        final String list = WorkIt.bearer( workKey, "GET", "/api/work/projects", null,
            "Cookie", genuineRememberMeCookie() ).body();
        assertFalse( list.contains( "\"" + PROJECT + "\"" ), "the key acts as a person with no admin rights: " + list );
        assertFalse( list.contains( "\"SEO\"" ), list );
    }

    // ---- a work key is useless on the other key surfaces

    private static HttpResponse< String > mcpInitialize( final String path, final String token ) throws Exception {
        return WorkIt.bearer( token, "POST", path, INIT, "Accept", "application/json, text/event-stream" );
    }

    /**
     * The IT harness runs {@code /wikantik-admin-mcp} and {@code /knowledge-mcp} with
     * {@code mcp.access.allowUnrestricted=true}, which admits any caller, so a scope refusal there cannot be asserted
     * end to end; what is asserted on them is that a work key gets no more than a keyless caller. Scope refusal is
     * owned by the unit tests {@code ApiKeyScopeTest}, {@code AbstractApiAccessFilterTest}, {@code McpAccessFilterTest},
     * {@code ToolsAccessFilterTest} and {@code McpEndpointBootstrapperKeyOnlyTest}. {@code /tools/*} is gated in this
     * module, so its refusal is asserted for real.
     */
    @Test
    void workKeyIsRefusedOnTheOtherKeyedSurfaces() throws Exception {
        final String all = mint( "all" );
        final String mcpAdmin = mint( "mcp_admin" );
        final String tools = mint( "tools" );
        // /tools/* needs a key in this module (anonymous 403), so a work key's refusal there is meaningful
        assertEquals( 403, WorkIt.bearer( null, "GET", "/tools/openapi.json", null ).statusCode(), "tools is not open here" );
        assertEquals( 200, WorkIt.bearer( tools, "GET", "/tools/openapi.json", null ).statusCode(), "control: a tools key" );
        final int toolsWork = WorkIt.bearer( workKey, "GET", "/tools/openapi.json", null ).statusCode();
        assertTrue( toolsWork == 401 || toolsWork == 403, "/tools with a work key: " + toolsWork );
        final int toolsAgent = WorkIt.bearer( agent.token(), "GET", "/tools/openapi.json", null ).statusCode();
        assertTrue( toolsAgent == 401 || toolsAgent == 403, "/tools with an agent key: " + toolsAgent );

        // the two MCP endpoints are open to keyless callers here (allowUnrestricted), so what matters is that a
        // work key confers nothing: record how each answers it next to a properly scoped key
        final int adminKeyless = mcpInitialize( "/wikantik-admin-mcp", null ).statusCode();
        final int knowledgeKeyless = mcpInitialize( "/knowledge-mcp", null ).statusCode();
        assertEquals( 200, mcpInitialize( "/wikantik-admin-mcp", mcpAdmin ).statusCode(), "control: an admin-scope key" );
        assertEquals( 200, mcpInitialize( "/knowledge-mcp", all ).statusCode(), "control: an all-scope key" );
        // In unrestricted mode these endpoints admit anybody and ignore the scope of a key they cannot match, so a work
        // key cannot be "refused" there: assert that it gets exactly what a keyless caller gets (nothing more) and that
        // its owner's identity does not ride along (anAgentKeyConfersNoIdentityOnSessionRoutes).
        for ( final String token : List.of( workKey, agent.token() ) ) {
            assertEquals( adminKeyless, mcpInitialize( "/wikantik-admin-mcp", token ).statusCode(), "admin MCP: same as keyless" );
            assertEquals( knowledgeKeyless, mcpInitialize( "/knowledge-mcp", token ).statusCode(), "knowledge MCP: same as keyless" );
        }
    }

    @Test
    void anAgentKeyConfersNoIdentityOnSessionRoutes() throws Exception {
        final HttpResponse< String > r = WorkIt.bearer( agent.token(), "GET", ID_PROBE, null );
        assertEquals( 200, r.statusCode() );
        assertFalse( ( ( JsonObject ) json( r ) ).get( "authenticated" ).getAsBoolean(), "a work key is not a login" );
        final int adminStatus = WorkIt.bearer( agent.token(), "GET", "/admin/users", null ).statusCode();
        assertTrue( adminStatus == 401 || adminStatus == 403, "no admin access through a work key: " + adminStatus );
        assertEquals( 200, admin.get( "/admin/users" ).statusCode(), "control: the admin session reaches /admin/users" );
    }
}

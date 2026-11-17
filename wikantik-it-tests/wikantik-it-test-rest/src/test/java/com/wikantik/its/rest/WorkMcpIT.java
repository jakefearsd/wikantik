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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.its.rest.WorkIt.Agent;
import com.wikantik.its.rest.WorkIt.Caller;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /work-mcp} over the wire. The Java MCP client drives the flows (tool list, instructions, a full claim loop);
 * raw JSON-RPC over HTTP drives the properties a client library hides: no key means 403 (although this module runs
 * the other MCP endpoints open), per-call identity on one session, and what a rotated or retired key can do.
 */
public class WorkMcpIT {

    static final Set< String > EXPECTED_TOOLS = Set.of( "get_work_context", "list_claimable", "get_item", "claim_item",
        "update_item", "comment_on_item", "handoff_item", "release_item", "create_item", "open_change", "update_change",
        "review_change", "merge_change", "request_deployment", "get_deployment", "update_deployment" );

    private static final String NOT_FOUND = "Not found.";
    private static final String NEEDS_KEY = "This endpoint needs a work API key.";
    private static final String PERSON_LOGIN = "wmcpperson";
    private static final String PERSON_NAME = "Wmcp Person";
    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
        + "\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}";
    private static final String[] MCP_HEADERS = { "Accept", "application/json, text/event-stream" };

    private static Caller admin;
    private static Caller person;
    private static Agent agentA;
    private static Agent agentB;
    private static McpSyncClient clientA;
    private static McpSyncClient clientB;
    private static final List< String > TOOL_OUTPUT = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        person = WorkIt.account( admin, PERSON_LOGIN, PERSON_NAME );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "MCP", "name", "Mcp project" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "MHD", "name", "Mcp hidden" ) ).statusCode() );
        for ( final String q : List.of( "mcpbuild", "mcpreview" ) ) {
            assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", q, "name", "Queue " + q ) ).statusCode() );
        }
        agentA = WorkIt.registerAgent( admin, "mcpa@it", "mcpbuild" );
        agentB = WorkIt.registerAgent( admin, "mcpb@it", "mcpreview" );
        for ( final Agent a : List.of( agentA, agentB ) ) {
            assertEquals( 204, admin.send( "PUT", "/api/work/projects/MCP/members", Map.of( "principal", a.name(),
                "principalKind", "agent", "role", "member" ) ).statusCode() );
        }
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/MCP/members", Map.of( "principal", PERSON_LOGIN,
            "principalKind", "user", "role", "member" ) ).statusCode() );
        clientA = client( agentA.token() );
        clientB = client( agentB.token() );
    }

    @AfterAll
    static void tearDown() {
        for ( final McpSyncClient c : new McpSyncClient[] { clientA, clientB } ) {
            if ( c != null ) {
                c.close();
            }
        }
    }

    // ---- helpers

    private static McpSyncClient client( final String token ) {
        final String base = WorkIt.baseUrl();
        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder( base.endsWith( "/" ) ? base : base + "/" ).endpoint( "work-mcp" )
            .connectTimeout( Duration.ofSeconds( 15 ) )
            .addHttpRequestCustomizer( ( rb, method, uri, body, context ) -> rb.header( "Authorization", "Bearer " + token ) )
            .build();
        final McpSyncClient c = McpClient.sync( transport )
            .clientInfo( new McpSchema.Implementation( "wikantik-it-work-mcp", "1.0.0" ) )
            .requestTimeout( Duration.ofSeconds( 30 ) ).initializationTimeout( Duration.ofSeconds( 30 ) ).build();
        c.initialize();
        return c;
    }

    private static String text( final McpSchema.CallToolResult r ) {
        final String t = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        TOOL_OUTPUT.add( t );
        return t;
    }

    private static JsonObject ok( final McpSyncClient c, final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult r = c.callTool( new McpSchema.CallToolRequest( tool, args ) );
        final String t = text( r );
        assertFalse( Boolean.TRUE.equals( r.isError() ), tool + " failed: " + t );
        return JsonParser.parseString( t ).getAsJsonObject();
    }

    private static String refused( final McpSyncClient c, final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult r = c.callTool( new McpSchema.CallToolRequest( tool, args ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ), tool + " should have been refused: " + text( r ) );
        return text( r );
    }

    /** A project item made by the person (a member) and handed to a queue, ready to claim. */
    private static String queuedItem( final String queue ) throws Exception {
        final HttpResponse< String > created = person.send( "POST", "/api/work/items", Map.of( "project", "MCP",
            "level", "task", "title", "Wire item " + queue ) );
        assertEquals( 201, created.statusCode(), created.body() );
        final JsonObject item = json( created ).getAsJsonObject().getAsJsonObject( "item" );
        final HttpResponse< String > handed = person.send( "POST", "/api/work/items/" + item.get( "key" ).getAsString() + "/handoff",
            Map.of( "expectedVersion", item.get( "version" ).getAsInt(), "status", "ready", "queue", queue, "note", "go" ) );
        assertEquals( 200, handed.statusCode(), handed.body() );
        return item.get( "key" ).getAsString();
    }

    private static JsonObject itemNow( final String key ) throws Exception {
        return json( admin.get( "/api/work/items/" + key ) ).getAsJsonObject().getAsJsonObject( "item" );
    }

    /** One raw JSON-RPC exchange; the reply is JSON or an SSE stream whose {@code data:} line is the JSON. */
    private record Reply( int status, String sessionId, JsonObject json, String raw ) {
        static Reply of( final HttpResponse< String > r ) {
            String payload = r.body();
            for ( final String line : r.body().split( "\n" ) ) {
                if ( line.startsWith( "data:" ) ) {
                    payload = line.substring( 5 ).trim();
                }
            }
            JsonObject parsed = null;
            try {
                final JsonElement e = JsonParser.parseString( payload );
                parsed = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch ( final RuntimeException e ) {
                parsed = null;   // a refusal need not be JSON; status and raw text are what is asserted on
            }
            return new Reply( r.statusCode(), r.headers().firstValue( "Mcp-Session-Id" ).orElse( null ), parsed, r.body() );
        }

        /** The text of a tool result, or null when the reply carries none. */
        String toolText() {
            if ( json == null || !json.has( "result" ) ) {
                return null;
            }
            final JsonArray content = json.getAsJsonObject( "result" ).getAsJsonArray( "content" );
            return content == null || content.isEmpty() ? null : content.get( 0 ).getAsJsonObject().get( "text" ).getAsString();
        }

        boolean toolError() {
            return json != null && json.has( "result" ) && json.getAsJsonObject( "result" ).has( "isError" )
                && json.getAsJsonObject( "result" ).get( "isError" ).getAsBoolean();
        }
    }

    private static String openSession( final String token ) throws Exception {
        final Reply init = Reply.of( WorkIt.bearer( token, "POST", "/work-mcp", INIT, MCP_HEADERS ) );
        assertEquals( 200, init.status(), "initialize: " + init.raw() );
        assertTrue( init.sessionId() != null, "no session id" );
        final HttpResponse< String > done = WorkIt.bearer( token, "POST", "/work-mcp",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "Accept", "application/json, text/event-stream",
            "Mcp-Session-Id", init.sessionId() );
        assertTrue( done.statusCode() == 202 || done.statusCode() == 200, "initialized: " + done.statusCode() );
        return init.sessionId();
    }

    private static Reply rawCall( final String token, final String session, final String tool, final Map< String, Object > args )
            throws Exception {
        final String body = WorkIt.GSON.toJson( Map.of( "jsonrpc", "2.0", "id", 2, "method", "tools/call",
            "params", Map.of( "name", tool, "arguments", args ) ) );
        return Reply.of( WorkIt.bearer( token, "POST", "/work-mcp", body, "Accept", "application/json, text/event-stream",
            "Mcp-Session-Id", session ) );
    }

    // ---- tool surface

    @Test
    void listToolsIsExactlyTheExpectedSet() {
        final Set< String > names = clientA.listTools().tools().stream().map( McpSchema.Tool::name ).collect( Collectors.toSet() );
        assertEquals( EXPECTED_TOOLS, names );
        assertEquals( EXPECTED_TOOLS.size(), clientA.listTools().tools().size() );
    }

    @Test
    void instructionsMentionEveryTool() {
        final String instructions = clientA.getServerInstructions();
        assertTrue( instructions != null && !instructions.isBlank() );
        for ( final String tool : EXPECTED_TOOLS ) {
            assertTrue( instructions.contains( tool ), "instructions omit " + tool );
        }
    }

    @Test
    void nobodyCanTriggerTheSweepOverMcp() throws Exception {
        // control: a real tool answers on this very session
        final String session = openSession( agentA.token() );
        final Reply real = rawCall( agentA.token(), session, "get_work_context", Map.of() );
        assertFalse( real.toolError(), real.raw() );
        for ( final String tool : List.of( "sweep_expired_claims", "sweep_claims", "expire_claims" ) ) {
            final Reply r = rawCall( agentA.token(), session, tool, Map.of() );
            final boolean rpcError = r.json() != null && r.json().has( "error" );
            assertTrue( rpcError || r.toolError() || r.status() >= 400, tool + " must not exist: " + r.raw() );
        }
    }

    // ---- no key, no access

    @Test
    void keylessRequestsToWorkMcpAreRefusedWhereTheOtherEndpointsAreOpen() throws Exception {
        // positive control: the same keyless initialize is accepted by the knowledge MCP in this very run
        final Reply open = Reply.of( WorkIt.bearer( null, "POST", "/knowledge-mcp", INIT, MCP_HEADERS ) );
        assertEquals( 200, open.status(), "mcp.access.allowUnrestricted is on in this module: " + open.raw() );
        assertEquals( 403, WorkIt.bearer( null, "POST", "/work-mcp", INIT, MCP_HEADERS ).statusCode() );
        assertEquals( 403, WorkIt.bearer( null, "GET", "/work-mcp", null, "Accept", "text/event-stream" ).statusCode() );
        assertEquals( 403, WorkIt.bearer( null, "DELETE", "/work-mcp", null, "Mcp-Session-Id", "whatever" ).statusCode() );
        final int junk = WorkIt.bearer( "wkk_not-a-key", "POST", "/work-mcp", INIT, MCP_HEADERS ).statusCode();
        assertTrue( junk == 401 || junk == 403, "a made-up key: " + junk );
        // control: with a key the same request is accepted
        assertEquals( 200, WorkIt.bearer( agentA.token(), "POST", "/work-mcp", INIT, MCP_HEADERS ).statusCode() );
    }

    // ---- the claim loop, and the log it leaves

    @Test
    void claimLoopOverTheWire() throws Exception {
        final String key = queuedItem( "mcpbuild" );

        final JsonObject context = ok( clientA, "get_work_context", Map.of() );
        assertEquals( "mcpa@it", context.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( 1, context.get( "claimExpiryMinutes" ).getAsInt(), "the IT cascade sets the shortest expiry" );
        final List< String > projects = new ArrayList<>();
        context.getAsJsonArray( "projects" ).forEach( p -> projects.add( p.getAsJsonObject().get( "key" ).getAsString() ) );
        assertEquals( List.of( "MCP" ), projects, "only the admitted project, not MHD" );

        // claimable for A (queue mcpbuild) and not for B (queue mcpreview)
        assertTrue( ok( clientA, "list_claimable", Map.of( "project", "MCP" ) ).toString().contains( key ) );
        assertFalse( ok( clientB, "list_claimable", Map.of( "project", "MCP" ) ).toString().contains( key ) );
        assertFalse( refused( clientB, "claim_item", Map.of( "key", key ) ).isBlank(), "B serves another queue" );

        final JsonObject claimed = ok( clientA, "claim_item", Map.of( "key", key ) );
        assertFalse( claimed.getAsJsonObject( "claim" ).get( "renewed" ).getAsBoolean() );
        final JsonObject item = claimed.getAsJsonObject( "item" );
        assertEquals( "in_progress", item.get( "status" ).getAsString() );
        assertEquals( "mcpa@it", item.getAsJsonObject( "claim" ).get( "agent" ).getAsString() );
        assertTrue( refused( clientB, "claim_item", Map.of( "key", key ) ).startsWith( "not_claimable:" ) );

        assertTrue( ok( clientA, "comment_on_item", Map.of( "key", key, "body", "started" ) ).has( "id" ) );
        // The IT cascade's claims last one minute: renew right before the step that needs the claim still held, so a
        // slow run cannot lapse it in between (and a lapse would show here as renewed=false, not as a late failure).
        assertTrue( ok( clientA, "claim_item", Map.of( "key", key ) ).getAsJsonObject( "claim" ).get( "renewed" ).getAsBoolean() );
        final JsonObject read = ok( clientA, "get_item", Map.of( "key", key ) );
        final int version = read.get( "version" ).getAsInt();
        final JsonObject handed = ok( clientA, "handoff_item", Map.of( "key", key, "expectedVersion", version,
            "status", "review", "queue", "mcpreview", "note", "done, please review" ) );
        assertEquals( "review", handed.get( "status" ).getAsString() );

        final JsonObject taken = ok( clientB, "claim_item", Map.of( "key", key ) );
        assertEquals( "mcpb@it", taken.getAsJsonObject( "item" ).getAsJsonObject( "claim" ).get( "agent" ).getAsString() );
        final JsonObject released = ok( clientB, "release_item", Map.of( "key", key, "note", "not mine after all" ) );
        assertEquals( "review", released.get( "status" ).getAsString() );
        assertTrue( refused( clientB, "release_item", Map.of( "key", key ) ).startsWith( "not_claimed:" ) );

        // the log: REST (admin) and what the agent itself reads
        final JsonArray log = json( admin.get( "/api/work/items/" + key + "/events" ) ).getAsJsonArray();
        final List< JsonObject > flow = new ArrayList<>();
        for ( final JsonElement e : log ) {
            if ( !"created".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ) {
                flow.add( e.getAsJsonObject() );
            }
        }
        assertEquals( List.of( "handoff", "claimed", "commented", "claim_renewed", "handoff", "released", "claimed", "released" ),
            flow.stream().map( e -> e.get( "type" ).getAsString() ).toList(), log.toString() );
        assertEquals( "human", flow.get( 0 ).get( "actorKind" ).getAsString() );
        assertEquals( "agent", flow.get( 1 ).get( "actorKind" ).getAsString() );
        assertEquals( "mcpa@it", flow.get( 1 ).get( "actorName" ).getAsString() );
        assertEquals( "mcpa@it", flow.get( 4 ).get( "actorName" ).getAsString() );
        assertEquals( "reassigned", flow.get( 5 ).get( "note" ).getAsString() );
        assertEquals( "mcpb@it", flow.get( 6 ).get( "actorName" ).getAsString() );
        assertEquals( "mcpb@it", flow.get( 7 ).get( "actorName" ).getAsString() );
        assertEquals( "not mine after all", flow.get( 7 ).get( "note" ).getAsString() );
        final JsonArray seen = ok( clientB, "get_item", Map.of( "key", key ) ).getAsJsonArray( "events" );
        assertTrue( seen.toString().contains( PERSON_NAME ), "people appear by display name: " + seen );
        final String activity = admin.get( "/api/work/activity?project=MCP&item=" + key + "&limit=100" ).body();
        assertTrue( activity.contains( "mcpa@it" ) && activity.contains( "mcpb@it" ) );

        // tool output never carries a login, a key id or a token
        final String all = String.join( "\n", TOOL_OUTPUT );
        assertFalse( all.contains( PERSON_LOGIN ), "a login in tool output" );
        assertFalse( all.toLowerCase( java.util.Locale.ROOT ).contains( "janne" ),
            "neither the IT admin's login nor the agents' owner (the same account) appears in tool output" );
        assertFalse( all.contains( "apiKeyId" ) );
        assertFalse( all.contains( agentA.token() ) || all.contains( agentB.token() ) );
        assertTrue( all.contains( PERSON_NAME ), "positive control: the display name is there" );
    }

    // ---- visibility

    @Test
    void hiddenItemErrorEqualsGhostError() throws Exception {
        final HttpResponse< String > made = admin.send( "POST", "/api/work/items", Map.of( "project", "MHD", "level", "task",
            "title", "Hidden by project" ) );
        assertEquals( 201, made.statusCode() );
        final String hidden = json( made ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
        final String ghost = "GHOST-" + hidden.substring( hidden.indexOf( '-' ) + 1 );
        final Map< String, Map< String, Object > > calls = new HashMap<>();
        calls.put( "get_item", Map.of( "key", "%K" ) );
        calls.put( "claim_item", Map.of( "key", "%K" ) );
        calls.put( "update_item", Map.of( "key", "%K", "expectedVersion", 1, "title", "x" ) );
        calls.put( "comment_on_item", Map.of( "key", "%K", "body", "x" ) );
        calls.put( "handoff_item", Map.of( "key", "%K", "expectedVersion", 1, "status", "review", "queue", "mcpreview", "note", "x" ) );
        calls.put( "release_item", Map.of( "key", "%K" ) );
        for ( final Map.Entry< String, Map< String, Object > > call : calls.entrySet() ) {
            for ( final McpSyncClient c : List.of( clientA, clientB ) ) {
                final Map< String, Object > h = new HashMap<>( call.getValue() );
                final Map< String, Object > g = new HashMap<>( call.getValue() );
                h.put( "key", hidden );
                g.put( "key", ghost );
                assertEquals( NOT_FOUND, refused( c, call.getKey(), h ), call.getKey() + " on a hidden item" );
                assertEquals( NOT_FOUND, refused( c, call.getKey(), g ), call.getKey() + " on a nonexistent item" );
            }
        }
        // a hidden project reads like a nonexistent one
        final String hiddenList = refusedOrEmpty( clientA, "MHD" );
        assertEquals( refusedOrEmpty( clientA, "GHOST" ), hiddenList );
        // positive controls: the real item exists (the admin reads it); an item in the admitted project reads fine
        assertEquals( 200, admin.get( "/api/work/items/" + hidden ).statusCode() );
        final String visible = queuedItem( "mcpbuild" );
        assertEquals( visible, ok( clientA, "get_item", Map.of( "key", visible ) ).get( "key" ).getAsString() );
        final JsonObject context = ok( clientA, "get_work_context", Map.of() );
        assertFalse( context.toString().contains( "MHD" ), context.toString() );
        assertTrue( context.toString().contains( "\"MCP\"" ) );
    }

    private static String refusedOrEmpty( final McpSyncClient c, final String project ) {
        final McpSchema.CallToolResult r = c.callTool( new McpSchema.CallToolRequest( "list_claimable", Map.of( "project", project ) ) );
        return Boolean.TRUE.equals( r.isError() ) ? "ERR " + text( r ) : "OK " + text( r );
    }

    // ---- losing access

    @Test
    void anAgentNobodyStandsBehindRunsNoTool() throws Exception {
        final Agent loose = WorkIt.registerAgent( admin, "mcploose@it", "mcpbuild" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/MCP/members", Map.of( "principal", loose.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        final String key = queuedItem( "mcpbuild" );
        final String session = openSession( loose.token() );
        assertFalse( rawCall( loose.token(), session, "get_work_context", Map.of() ).toolError(), "control: it works" );

        assertEquals( 204, admin.send( "DELETE", "/api/work/agents/" + loose.id() + "/sponsor", null ).statusCode() );
        final Reply refused = rawCall( loose.token(), session, "claim_item", Map.of( "key", key ) );
        assertTrue( refused.toolError(), "released by its sponsor: no actor on the very next call: " + refused.raw() );
        assertTrue( refused.raw().contains( "This endpoint needs a work API key." ), refused.raw() );
        assertTrue( itemNow( key ).get( "claimedByAgent" ).isJsonNull(), "no tool body ran" );

        assertEquals( 200, admin.send( "PUT", "/api/work/agents/" + loose.id() + "/sponsor", null ).statusCode() );
        assertFalse( rawCall( loose.token(), session, "get_work_context", Map.of() ).toolError(),
            "taken over again: the same token and session work" );
    }

    @Test
    void rotatedKeyCannotRunToolsAnymore() throws Exception {
        final Agent rot = WorkIt.registerAgent( admin, "mcprot@it", "mcpbuild" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/MCP/members", Map.of( "principal", rot.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        final String key = queuedItem( "mcpbuild" );
        final String session = openSession( rot.token() );
        assertFalse( rawCall( rot.token(), session, "get_work_context", Map.of() ).toolError(), "control: the key works" );

        final HttpResponse< String > rotated = admin.send( "POST", "/api/work/agents/" + rot.id() + "/rotate-key", null );
        assertEquals( 200, rotated.statusCode() );
        final String fresh = json( rotated ).getAsJsonObject().get( "token" ).getAsString();

        assertRefusedNoBody( rawCall( rot.token(), session, "claim_item", Map.of( "key", key ) ), "mcprot@it" );
        assertTrue( itemNow( key ).get( "claimedByAgent" ).isJsonNull(), "no tool body ran: the item is still unclaimed" );
        // control: the new key works on a fresh session
        final Reply again = rawCall( fresh, openSession( fresh ), "get_work_context", Map.of() );
        assertFalse( again.toolError(), again.raw() );
        assertTrue( again.toolText().contains( "mcprot@it" ) );
    }

    @Test
    void retiredAgentCannotRunToolsAnymore() throws Exception {
        final Agent ret = WorkIt.registerAgent( admin, "mcpret@it", "mcpbuild" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/MCP/members", Map.of( "principal", ret.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        final String key = queuedItem( "mcpbuild" );
        final String session = openSession( ret.token() );
        final Reply claimed = rawCall( ret.token(), session, "claim_item", Map.of( "key", key ) );
        assertFalse( claimed.toolError(), claimed.raw() );
        assertEquals( 204, admin.send( "POST", "/api/work/agents/" + ret.id() + "/retire", null ).statusCode() );
        assertRefusedNoBody( rawCall( ret.token(), session, "get_work_context", Map.of() ), "mcpret@it" );
        assertRefusedNoBody( rawCall( ret.token(), session, "comment_on_item", Map.of( "key", key, "body", "ghost" ) ), "ghost" );
        final JsonObject item = itemNow( key );
        assertEquals( "ready", item.get( "status" ).getAsString() );
        assertTrue( item.get( "claimedByAgent" ).isJsonNull() );
        assertFalse( json( admin.get( "/api/work/items/" + key ) ).getAsJsonObject().getAsJsonArray( "comments" ).toString().contains( "ghost" ) );
    }

    /** Refused at the transport (401/403) or by the tool with the needs-key text; either way nothing of the work shown. */
    private static void assertRefusedNoBody( final Reply r, final String secret ) {
        final boolean transport = r.status() == 401 || r.status() == 403;
        final boolean tool = r.status() == 200 && r.toolError() && NEEDS_KEY.equals( r.toolText() );
        assertTrue( transport || tool, "expected a refusal, got " + r.status() + " " + r.raw() );
        assertFalse( r.raw().contains( secret ), "the refusal leaks: " + r.raw() );
    }

    // ---- per-call identity

    @Test
    void oneSessionServesOneKey() throws Exception {
        final String session = openSession( agentA.token() );
        final Reply asA = rawCall( agentA.token(), session, "get_work_context", Map.of() );
        assertFalse( asA.toolError(), asA.raw() );
        assertEquals( "mcpa@it", agentName( asA ) );

        // the second agent's key on the FIRST agent's session id is refused before any tool runs
        final Reply asB = rawCall( agentB.token(), session, "get_work_context", Map.of() );
        assertEquals( 403, asB.status(), "B's call on A's session: " + asB.raw() );
        assertEquals( "{\"error\":\"This MCP session belongs to another API key\"}", asB.raw() );
        final String key = queuedItem( "mcpreview" );
        assertEquals( 403, rawCall( agentB.token(), session, "claim_item", Map.of( "key", key ) ).status() );
        assertTrue( itemNow( key ).get( "claimedByAgent" ).isJsonNull(), "nothing was claimed" );

        // A's next call on its own session still acts as A
        final Reply asAAgain = rawCall( agentA.token(), session, "get_work_context", Map.of() );
        assertEquals( "mcpa@it", agentName( asAAgain ) );
        // and B, on a session of its own, acts as B and can claim from its queue
        final String sessionB = openSession( agentB.token() );
        assertEquals( "mcpb@it", agentName( rawCall( agentB.token(), sessionB, "get_work_context", Map.of() ) ) );
        final Reply claim = rawCall( agentB.token(), sessionB, "claim_item", Map.of( "key", key ) );
        assertFalse( claim.toolError(), claim.raw() );
        assertEquals( "mcpb@it", itemClaimant( key ) );
    }

    private static String agentName( final Reply r ) {
        return JsonParser.parseString( r.toolText() ).getAsJsonObject().getAsJsonObject( "agent" ).get( "name" ).getAsString();
    }

    private static String itemClaimant( final String key ) throws Exception {
        final JsonObject item = itemNow( key );
        return item.has( "claimedByName" ) && !item.get( "claimedByName" ).isJsonNull() ? item.get( "claimedByName" ).getAsString() : null;
    }
}

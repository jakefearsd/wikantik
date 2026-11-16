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
package com.wikantik.work.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.mcp.KeyOnlyMcpAccessFilter;
import com.wikantik.mcp.SdkSessionSweepers;
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.WorkRole;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One MCP session, one key: the key-only access filter remembers the key that opened a session (from the
 * {@code Mcp-Session-Id} header the transport writes) and refuses any other key on that session with 403, while the
 * first key goes on acting as itself. The actor is still resolved from each request's own key on every call, so a
 * key retired mid-session loses access on its next call. This drives the real filter, the real transport, the real
 * extractor and the real tool dispatch over a real database.
 */
@RequiresPostgres
class WorkMcpSessionActorTest {

    private WorkMcpFixture f;
    private HttpServletStreamableServerTransportProvider transport;
    private McpSyncServer server;
    private KeyOnlyMcpAccessFilter filter;
    private final Map< String, ApiKeyService.Record > tokens = new HashMap<>();
    private String sessionId;
    private int rpcId = 10;

    /** What one HTTP request came back with. */
    private record Reply( int status, String body ) {
    }

    @BeforeEach
    void setUp() throws Exception {
        f = new WorkMcpFixture();
        SdkSessionSweepers.track();
        transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint( "/work-mcp" )
            .contextExtractor( WorkMcpActors::extract ).build();
        final McpServer.SyncSpecification< ? > spec = McpServer.sync( transport )
            .serverInfo( new McpSchema.Implementation( "wikantik-work", "Wikantik Work", "t" ) )
            .capabilities( McpSchema.ServerCapabilities.builder().tools( true ).build() );
        WorkMcpInitializer.registerTools( spec, WorkMcpInitializer.assembleTools( f.svc, f.directory() ), f.svc );
        server = spec.build();
        final ApiKeyService keys = mock( ApiKeyService.class );
        when( keys.verify( anyString() ) ).thenAnswer( inv -> Optional.ofNullable( tokens.get( inv.getArgument( 0 ) ) ) );
        filter = new KeyOnlyMcpAccessFilter( new SlidingWindowRateLimiter( 0, 0 ), keys, ApiKeyService.Scope.WORK );
        filter.agentTokens( com.wikantik.work.runtime.WikiWorkActors.agentVerifier( f.svc ) );
        sessionId = initialize( credential( f.builder ) );
    }

    @AfterEach
    void tearDown() {
        server.close();
        SdkSessionSweepers.disposeAll();
    }

    /** A bearer token the mocked key service verifies to {@code key}. */
    private String token( final ApiKeyService.Record key ) {
        final String t = "tok-" + key.id() + "-" + key.scope().wire();
        tokens.put( t, key );
        return t;
    }

    private static ServletInputStream stream( final String body ) {
        final ByteArrayInputStream in = new ByteArrayInputStream( body.getBytes( StandardCharsets.UTF_8 ) );
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener( final ReadListener l ) { }
            @Override public int read() { return in.read(); }
        };
    }

    /** One POST through the key-only access filter to the transport; {@code token} null sends no key. */
    private Reply post( final String session, final String token, final String body,
                        final AtomicReference< String > sessionOut ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final AsyncContext async = mock( AsyncContext.class );
        final Map< String, Object > attrs = new HashMap<>();
        doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) ).when( req )
            .setAttribute( anyString(), any() );
        when( req.getAttribute( anyString() ) ).thenAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) );
        when( req.getRequestURI() ).thenReturn( "/work-mcp" );
        when( req.getMethod() ).thenReturn( "POST" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.7" );
        when( req.getContentType() ).thenReturn( "application/json" );
        when( req.getHeader( "Authorization" ) ).thenReturn( token == null ? null : "Bearer " + token );
        when( req.getHeader( "Accept" ) ).thenReturn( "application/json, text/event-stream" );
        when( req.getHeader( "Mcp-Session-Id" ) ).thenReturn( session );
        when( req.getHeader( "MCP-Protocol-Version" ) ).thenReturn( "2025-06-18" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );
        when( req.getInputStream() ).thenReturn( stream( body ) );
        when( req.startAsync() ).thenReturn( async );
        final StringWriter out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );
        final AtomicInteger status = new AtomicInteger( 200 );
        doAnswer( inv -> {
            status.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setStatus( anyInt() );
        doAnswer( inv -> {
            if ( "Mcp-Session-Id".equalsIgnoreCase( inv.getArgument( 0 ) ) && sessionOut != null ) {
                sessionOut.set( inv.getArgument( 1 ) );
            }
            return null;
        } ).when( resp ).setHeader( anyString(), anyString() );

        filter.doFilter( req, resp, ( rq, rs ) -> ( ( jakarta.servlet.Servlet ) transport ).service( rq, rs ) );
        return new Reply( status.get(), out.toString() );
    }

    private String initialize( final String token ) throws Exception {
        final AtomicReference< String > id = new AtomicReference<>();
        post( null, token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}", id );
        assertNotNull( id.get(), "the transport opened a session" );
        post( id.get(), token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null );
        return id.get();
    }

    private Reply rawContext( final String token ) throws Exception {
        return post( sessionId, token, "{\"jsonrpc\":\"2.0\",\"id\":" + ++rpcId
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"get_work_context\",\"arguments\":{}}}", null );
    }

    /** The text of the tool result for {@code get_work_context} sent on the one session with this token. */
    private String callContext( final String token ) throws Exception {
        final Reply r = rawContext( token );
        for ( final String line : r.body().split( "\n" ) ) {
            if ( line.startsWith( "data:" ) ) {
                final JsonObject msg = JsonParser.parseString( line.substring( 5 ).trim() ).getAsJsonObject();
                if ( msg.has( "result" ) ) {
                    return msg.getAsJsonObject( "result" ).getAsJsonArray( "content" ).get( 0 ).getAsJsonObject()
                        .get( "text" ).getAsString();
                }
            }
        }
        throw new AssertionError( "no tool result (HTTP " + r.status() + ") in: " + r.body() );
    }

    private final Map< Long, String > credentials = new HashMap<>();

    /** The agent's own token: issued once per agent (issuing again would rotate it) straight from the store. */
    private String credential( final Agent a ) throws Exception {
        if ( !credentials.containsKey( a.id() ) ) {
            credentials.put( a.id(), new com.wikantik.work.JdbcWorkStore( f.ds ).credentials().issue( a.id(), "admin" )
                .token() );
        }
        return credentials.get( a.id() );
    }

    private static String agentName( final String contextText ) {
        return JsonParser.parseString( contextText ).getAsJsonObject().getAsJsonObject( "agent" ).get( "name" )
            .getAsString();
    }

    @Test
    void aSecondKeyOnTheSessionIsRefusedAndTheFirstGoesOnActingAsItself() throws Exception {
        final Agent other = f.svc.registry().registerAgent( f.admin, new AgentDraft( "reviewer-bot", "", "",
            List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "SECRET", "reviewer-bot", "agent", WorkRole.MEMBER );
        final String builder = credential( f.builder );

        final String asBuilder = callContext( builder );
        final Reply asOther = rawContext( credential( other ) );
        final String asBuilderAgain = callContext( builder );

        assertEquals( "builder", agentName( asBuilder ) );
        assertEquals( 403, asOther.status(), "one session, one key: " + asOther.body() );
        assertEquals( "{\"error\":\"This MCP session belongs to another API key\"}", asOther.body() );
        assertEquals( JsonParser.parseString( asBuilder ), JsonParser.parseString( asBuilderAgain ),
            "and the first key's identity comes back unchanged" );
    }

    @Test
    void aKeyRetiredMidSessionLosesAccessOnItsNextCall() throws Exception {
        final String key = credential( f.builder );
        assertEquals( "builder", agentName( callContext( key ) ) );

        f.svc.registry().retireAgent( f.admin, f.builder.id() );

        assertEquals( 403, rawContext( key ).status(), "retiring revokes the credential: refused at the door" );
    }

    @Test
    void aRequestWithoutAKeyOnTheSameSessionIsRefused() throws Exception {
        assertEquals( "builder", agentName( callContext( credential( f.builder ) ) ) );

        assertEquals( 403, rawContext( null ).status(),
            "no key on this request: never fall back to the key the session started with" );
    }

    @Test
    void aKeyOfAnotherScopeOnTheSameSessionIsRefused() throws Exception {
        assertEquals( 403, rawContext( token( WorkMcpFixture.record( 9_001, "lena",
            ApiKeyService.Scope.MCP_READ ) ) ).status() );
    }

    // ---- agent tokens (phase 3a): the same session rules, verified by the work module

    @Test
    void anAgentTokenRunsATool() throws Exception {
        final String token = credential( f.builder );
        sessionId = initialize( token );
        assertEquals( "builder", agentName( callContext( token ) ) );
    }

    @Test
    void anAgentNobodyStandsBehindRunsNothing() throws Exception {
        final String token = credential( f.builder );
        assertEquals( "builder", agentName( callContext( token ) ) );
        f.svc.registry().releaseSponsorship( f.admin, f.builder.id() );
        assertTrue( callContext( token ).contains( "This endpoint needs a work API key." ),
            "the token still verifies, but a sponsorless agent is no actor" );
        f.svc.registry().takeOver( f.admin, f.builder.id() );
        assertEquals( "builder", agentName( callContext( token ) ), "taken over: the same token works again" );
    }

    @Test
    void aRotatedAwayTokenIsRefusedAtTheDoor() throws Exception {
        final String token = credential( f.builder );
        sessionId = initialize( token );
        final String next = new com.wikantik.work.JdbcWorkStore( f.ds ).credentials().issue( f.builder.id(), "admin" )
            .token();
        assertEquals( 403, rawContext( token ).status(), "the old token stops on the very next request" );
        assertEquals( "builder", agentName( callContext( next ) ), "the new one continues the agent's session" );
    }

    @Test
    void aDirectoryOutageAnswersUnavailable() throws Exception {
        final String token = credential( f.builder );
        sessionId = initialize( token );
        f.directoryDown = true;
        assertTrue( callContext( token ).contains( "Work is unavailable right now." ) );
        f.directoryDown = false;
        assertEquals( "builder", agentName( callContext( token ) ), "and it recovers with nothing changed" );
    }
}

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
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.RequiresPostgres;
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
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SDK calls the context extractor once per HTTP request, and hands that request's context to that request's tool
 * call. So on one long-lived MCP session two requests carrying different keys act as their own actors, and a key
 * retired or rotated mid-session loses access on its very next call. This drives the real transport, the real
 * extractor and the real tool dispatch over a real database.
 */
@RequiresPostgres
class WorkMcpSessionActorTest {

    private WorkMcpFixture f;
    private HttpServletStreamableServerTransportProvider transport;
    private McpSyncServer server;
    private String sessionId;
    private int rpcId = 10;

    @BeforeEach
    void setUp() throws Exception {
        f = new WorkMcpFixture();
        SdkSessionSweepers.track();
        transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint( "/work-mcp" )
            .contextExtractor( WorkMcpActors::extract ).build();
        final McpServer.SyncSpecification< ? > spec = McpServer.sync( transport )
            .serverInfo( new McpSchema.Implementation( "wikantik-work", "Wikantik Work", "t" ) )
            .capabilities( McpSchema.ServerCapabilities.builder().tools( true ).build() );
        WorkMcpInitializer.registerTools( spec, WorkMcpInitializer.assembleTools( f.svc, f.directory(),
            Duration.ofMinutes( 30 ) ), f.svc );
        server = spec.build();
        sessionId = initialize( WorkMcpFixture.record( f.builder.apiKeyId(), "lena", ApiKeyService.Scope.WORK ) );
    }

    @AfterEach
    void tearDown() {
        server.close();
        SdkSessionSweepers.disposeAll();
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

    /** One POST as the access filter would deliver it: the verified key's record rides on the request. */
    private String post( final String session, final ApiKeyService.Record key, final String body,
                         final AtomicReference< String > sessionOut ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final AsyncContext async = mock( AsyncContext.class );
        when( req.getRequestURI() ).thenReturn( "/work-mcp" );
        when( req.getMethod() ).thenReturn( "POST" );
        when( req.getContentType() ).thenReturn( "application/json" );
        when( req.getHeader( "Accept" ) ).thenReturn( "application/json, text/event-stream" );
        when( req.getHeader( "Mcp-Session-Id" ) ).thenReturn( session );
        when( req.getHeader( "MCP-Protocol-Version" ) ).thenReturn( "2025-06-18" );
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );
        when( req.getInputStream() ).thenReturn( stream( body ) );
        when( req.startAsync() ).thenReturn( async );
        when( req.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) ).thenReturn( key );
        final StringWriter out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );
        doAnswer( inv -> {
            if ( "Mcp-Session-Id".equalsIgnoreCase( inv.getArgument( 0 ) ) && sessionOut != null ) {
                sessionOut.set( inv.getArgument( 1 ) );
            }
            return null;
        } ).when( resp ).setHeader( anyString(), anyString() );

        ( ( jakarta.servlet.Servlet ) transport ).service( req, resp );
        return out.toString();
    }

    private String initialize( final ApiKeyService.Record key ) throws Exception {
        final AtomicReference< String > id = new AtomicReference<>();
        post( null, key, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}", id );
        assertNotNull( id.get(), "the transport opened a session" );
        post( id.get(), key, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null );
        return id.get();
    }

    /** The text of the tool result for {@code get_work_context} sent on the one session with this request's key. */
    private String callContext( final ApiKeyService.Record key ) throws Exception {
        final String body = post( sessionId, key, "{\"jsonrpc\":\"2.0\",\"id\":" + ++rpcId
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"get_work_context\",\"arguments\":{}}}", null );
        for ( final String line : body.split( "\n" ) ) {
            if ( line.startsWith( "data:" ) ) {
                final JsonObject msg = JsonParser.parseString( line.substring( 5 ).trim() ).getAsJsonObject();
                if ( msg.has( "result" ) ) {
                    return msg.getAsJsonObject( "result" ).getAsJsonArray( "content" ).get( 0 ).getAsJsonObject()
                        .get( "text" ).getAsString();
                }
            }
        }
        throw new AssertionError( "no tool result in: " + body );
    }

    private ApiKeyService.Record keyOf( final Agent a ) {
        return WorkMcpFixture.record( a.apiKeyId(), "lena", ApiKeyService.Scope.WORK );
    }

    @Test
    void twoRequestsOnOneSessionActAsTheirOwnKeysActors() throws Exception {
        final Agent other = f.svc.registry().registerAgent( f.admin, new AgentDraft( "reviewer-bot", "", "",
            List.of( "reviewer" ), "lena" ) ).agent();
        f.svc.putMember( f.admin, "SECRET", "reviewer-bot", "agent", WorkRole.MEMBER );

        final JsonObject asBuilder = JsonParser.parseString( callContext( keyOf( f.builder ) ) ).getAsJsonObject();
        final JsonObject asOther = JsonParser.parseString( callContext( keyOf( other ) ) ).getAsJsonObject();
        final JsonObject asBuilderAgain = JsonParser.parseString( callContext( keyOf( f.builder ) ) ).getAsJsonObject();

        assertEquals( "builder", asBuilder.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( "PLAT", asBuilder.getAsJsonArray( "projects" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "reviewer-bot", asOther.getAsJsonObject( "agent" ).get( "name" ).getAsString(),
            "the second request is its own key's actor, not the session's first" );
        assertEquals( "SECRET", asOther.getAsJsonArray( "projects" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( asBuilder, asBuilderAgain, "and the first key's identity comes back unchanged" );
    }

    @Test
    void aKeyRetiredMidSessionLosesAccessOnItsNextCall() throws Exception {
        final ApiKeyService.Record key = keyOf( f.builder );
        assertEquals( "builder", JsonParser.parseString( callContext( key ) ).getAsJsonObject()
            .getAsJsonObject( "agent" ).get( "name" ).getAsString() );

        f.svc.registry().retireAgent( f.admin, f.builder.id() );

        assertEquals( "This endpoint needs a work API key.", callContext( key ) );
    }

    @Test
    void aRequestWithoutAKeyRecordOnTheSameSessionIsRefused() throws Exception {
        assertEquals( "builder", JsonParser.parseString( callContext( keyOf( f.builder ) ) ).getAsJsonObject()
            .getAsJsonObject( "agent" ).get( "name" ).getAsString() );

        assertEquals( "This endpoint needs a work API key.", callContext( null ),
            "no key on this request: never fall back to the key the session started with" );
    }

    @Test
    void aKeyOfAnotherScopeOnTheSameSessionIsRefused() throws Exception {
        assertEquals( "This endpoint needs a work API key.", callContext(
            WorkMcpFixture.record( f.builder.apiKeyId(), "lena", ApiKeyService.Scope.MCP_READ ) ) );
    }
}

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
package com.wikantik.mcp;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.apikeys.ApiKeyService;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cheap unit coverage for {@link McpEndpointBootstrapper.Builder} validation.
 *
 * <p>{@code registerAccessFilter} needs a live servlet container + wired {@link Engine}
 * subsystem and is left to the wikantik-admin-mcp / wikantik-knowledge integration
 * surfaces that exercise the real startup sequence; this class covers the builder's
 * required-field validation and the transport's shutdown hygiene.</p>
 */
class McpEndpointBootstrapperTest {

    private McpEndpointBootstrapper.Builder validBuilder() {
        return McpEndpointBootstrapper.builder()
                .logTag( "Test" )
                .endpointPath( "/test-mcp" )
                .filterName( "testFilter" )
                .servletName( "testServlet" )
                .engine( mock( Engine.class ) );
    }

    @Test
    void buildSucceedsWithAllRequiredFieldsSet() {
        assertNotNull( validBuilder().build() );
    }

    @Test
    void buildFailsWhenLogTagMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenEndpointPathMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenFilterNameMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenServletNameMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenEngineMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .build() );
    }

    @Test
    void loadOnStartupAndRequiredScopeSettersAreFluent() {
        final McpEndpointBootstrapper bootstrapper = validBuilder()
                .loadOnStartup( 5 )
                .requiredScope( ApiKeyService.Scope.MCP_READ )
                .build();
        assertNotNull( bootstrapper );
    }

    /** registerTransport must leave nothing that keeps a stopped Tomcat's JVM alive (see {@link SdkSessionSweepers}). */
    @Test
    void registeredTransportsSessionSweeperIsDisposedAtShutdown() throws Exception {
        Schedulers.removeExecutorServiceDecorator( SdkSessionSweepers.DECORATOR_KEY );   // no tracking left by other tests
        final ServletContext context = mock( ServletContext.class );
        when( context.addServlet( anyString(), any( Servlet.class ) ) ).thenReturn( mock( ServletRegistration.Dynamic.class ) );

        validBuilder().build().registerTransport( context ).closeGracefully().block();
        assertFalse( liveSweeperThreads().isEmpty(), "the transport started its idle-session sweeper" );

        SdkSessionSweepers.disposeAll();
        for ( int i = 0; i < 50 && !liveSweeperThreads().isEmpty(); i++ ) {
            Thread.sleep( 100 );
        }
        assertEquals( java.util.List.of(), liveSweeperThreads() );
    }

    /** The extractor handed to registerTransport is what the SDK calls with each POST; its context reaches the tool. */
    @Test
    void contextExtractorReachesTransport() throws Exception {
        final ServletContext context = mock( ServletContext.class );
        when( context.addServlet( anyString(), any( Servlet.class ) ) ).thenReturn( mock( ServletRegistration.Dynamic.class ) );
        final java.util.concurrent.atomic.AtomicReference< jakarta.servlet.http.HttpServletRequest > seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        final io.modelcontextprotocol.server.McpTransportContextExtractor< jakarta.servlet.http.HttpServletRequest > extractor = req -> {
            seen.set( req );
            return io.modelcontextprotocol.common.McpTransportContext.create( java.util.Map.of( "who", "me" ) );
        };
        final var provider = validBuilder().build().registerTransport( context, extractor );
        io.modelcontextprotocol.server.McpServer.sync( provider )
                .serverInfo( new io.modelcontextprotocol.spec.McpSchema.Implementation( "t", "T", "1" ) ).build();
        try {
            final var servlet = ( jakarta.servlet.http.HttpServlet ) provider;
            final var req = mock( jakarta.servlet.http.HttpServletRequest.class );
            final var resp = mock( jakarta.servlet.http.HttpServletResponse.class );
            when( req.getRequestURI() ).thenReturn( "/test-mcp" );
            when( req.getMethod() ).thenReturn( "POST" );
            when( req.getContentType() ).thenReturn( "application/json" );
            when( req.getHeader( "Accept" ) ).thenReturn( "application/json, text/event-stream" );
            when( req.getReader() ).thenReturn( new java.io.BufferedReader( new java.io.StringReader(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}" ) ) );
            when( req.getInputStream() ).thenReturn( new jakarta.servlet.ServletInputStream() {
                private final java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream( (
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}" ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener( final jakarta.servlet.ReadListener l ) { }
                @Override public int read() { return in.read(); }
            } );
            when( resp.getWriter() ).thenReturn( new java.io.PrintWriter( new java.io.StringWriter() ) );

            servlet.service( req, resp );

            assertNotNull( seen.get(), "the SDK called our extractor with the request" );
            assertEquals( req, seen.get() );
        } finally {
            provider.closeGracefully().block();
            SdkSessionSweepers.disposeAll();
        }
    }

    private static java.util.List< Thread > liveSweeperThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter( t -> t.isAlive() && t.getName().startsWith( "streamable-http-server-transport-session-sweeper" ) )
                .toList();
    }

    @Test
    void registerTransportMapsTheEndpointAsyncAndWithTheConfiguredStartupOrder() {
        final ServletContext context = mock( ServletContext.class );
        final ServletRegistration.Dynamic registration = mock( ServletRegistration.Dynamic.class );
        when( context.addServlet( anyString(), any( Servlet.class ) ) ).thenReturn( registration );

        validBuilder().loadOnStartup( 7 ).build().registerTransport( context ).closeGracefully().block();

        verify( registration ).addMapping( "/test-mcp" );
        verify( registration ).setAsyncSupported( true );
        verify( registration ).setLoadOnStartup( 7 );
    }

    @Test
    void agentTokensSetterIsFluent() {
        final McpEndpointBootstrapper.Builder builder = validBuilder();
        org.junit.jupiter.api.Assertions.assertSame( builder, builder.agentTokens( token -> java.util.Optional.empty() ) );
    }
}

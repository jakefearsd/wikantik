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

    private static java.util.List< Thread > liveSweeperThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter( t -> t.isAlive() && t.getName().startsWith( "streamable-http-server-transport-session-sweeper" ) )
                .toList();
    }
}

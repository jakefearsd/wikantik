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
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The key-only property, proved through the filter the bootstrapper actually registers: with {@code keyOnly(true)} a
 * config that opens the endpoint ({@code allowUnrestricted=true}, a permissive CIDR list) changes nothing, while the
 * same config with {@code keyOnly(false)} does open it (the positive control that the config really is in force).
 * The config reaches {@code new McpConfig()} the way an operator's file does: as {@code wikantik-mcp.properties} on
 * the thread-context classloader.
 */
class McpEndpointBootstrapperKeyOnlyTest {

    private ClassLoader original;

    @BeforeEach
    void setUp() {
        original = Thread.currentThread().getContextClassLoader();
        ApiKeyServiceHolder.setForTesting( mock( ApiKeyService.class ) );
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader( original );
        ApiKeyServiceHolder.setForTesting( null );
    }

    /** A loader that serves {@code wikantik-mcp.properties} with exactly this text, ahead of any other. */
    private static ClassLoader serving( final String properties, final ClassLoader parent ) {
        return new URLClassLoader( new URL[ 0 ], parent ) {
            @Override
            public InputStream getResourceAsStream( final String name ) {
                return "wikantik-mcp.properties".equals( name )
                    ? new ByteArrayInputStream( properties.getBytes( StandardCharsets.UTF_8 ) )
                    : super.getResourceAsStream( name );
            }
        };
    }

    private Filter registeredFilter( final boolean keyOnly, final String properties ) {
        Thread.currentThread().setContextClassLoader( serving( properties, original ) );
        final ServletContext ctx = mock( ServletContext.class );
        when( ctx.addFilter( anyString(), any( Filter.class ) ) ).thenReturn( mock( FilterRegistration.Dynamic.class ) );
        McpEndpointBootstrapper.builder().logTag( "Test" ).endpointPath( "/test-mcp" ).filterName( "testFilter" )
            .servletName( "testServlet" ).requiredScope( ApiKeyService.Scope.WORK ).keyOnly( keyOnly )
            .engine( mock( Engine.class ) ).build().registerAccessFilter( ctx );
        final ArgumentCaptor< Filter > captured = ArgumentCaptor.forClass( Filter.class );
        verify( ctx ).addFilter( eq( "testFilter" ), captured.capture() );
        return captured.getValue();
    }

    private static McpAccessFilter.Outcome keyless( final Filter filter, final String ip ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( ip );
        return ( ( McpAccessFilter ) filter ).authorize( req );
    }

    private static final String OPEN = "mcp.access.allowUnrestricted=true\nmcp.access.allowedCidrs=10.0.0.0/8\n";
    private static final String OPEN_UNRESTRICTED_ONLY = "mcp.access.allowUnrestricted=true\n";

    @Test
    void theConfigUsedHereReallyIsOpen() {
        Thread.currentThread().setContextClassLoader( serving( OPEN, original ) );
        final McpConfig config = new McpConfig();
        assertTrue( config.allowUnrestricted() );
        assertEquals( "10.0.0.0/8", config.allowedCidrs() );
    }

    @Test
    void notKeyOnlyTheOpenConfigAdmitsAKeylessAllowListedAddress() {
        assertInstanceOf( McpAccessFilter.Outcome.Allowed.class, keyless( registeredFilter( false, OPEN ), "10.1.2.3" ) );
    }

    @Test
    void notKeyOnlyUnrestrictedAdmitsAnyKeylessAddress() {
        assertInstanceOf( McpAccessFilter.Outcome.Allowed.class,
            keyless( registeredFilter( false, OPEN_UNRESTRICTED_ONLY ), "1.2.3.4" ) );
    }

    @Test
    void keyOnlyRefusesKeylessRequestsWhateverTheConfigOpens() {
        for ( final String config : new String[] { OPEN, OPEN_UNRESTRICTED_ONLY } ) {
            final Filter filter = registeredFilter( true, config );
            for ( final String ip : new String[] { "10.1.2.3", "1.2.3.4" } ) {
                final McpAccessFilter.Outcome outcome = keyless( filter, ip );
                assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome, ip + " / " + config );
                assertEquals( 403, ( ( McpAccessFilter.Outcome.Denied ) outcome ).status() );
            }
        }
    }

    @Test
    void keyOnlyRegistersTheNoPrincipalOneSessionOneKeyFilter() {
        assertInstanceOf( KeyOnlyMcpAccessFilter.class, registeredFilter( true, OPEN ) );
        assertEquals( McpAccessFilter.class, registeredFilter( false, OPEN ).getClass(),
            "the other MCP endpoints keep the plain filter" );
    }

    @Test
    void theFilterIsMappedAheadOfNothingAndRunsAsync() {
        Thread.currentThread().setContextClassLoader( serving( OPEN, original ) );
        final ServletContext ctx = mock( ServletContext.class );
        final FilterRegistration.Dynamic reg = mock( FilterRegistration.Dynamic.class );
        when( ctx.addFilter( anyString(), any( Filter.class ) ) ).thenReturn( reg );
        McpEndpointBootstrapper.builder().logTag( "Test" ).endpointPath( "/test-mcp" ).filterName( "testFilter" )
            .servletName( "testServlet" ).engine( mock( Engine.class ) ).build().registerAccessFilter( ctx );
        verify( reg ).addMappingForUrlPatterns( java.util.EnumSet.of( jakarta.servlet.DispatcherType.REQUEST ), true,
            "/test-mcp" );
        verify( reg ).setAsyncSupported( true );
    }

    @Test
    void anAgentVerifierGivenToTheBuilderIsInstalledOnTheRegisteredFilter() {
        Thread.currentThread().setContextClassLoader( serving( "", original ) );
        final ServletContext ctx = mock( ServletContext.class );
        when( ctx.addFilter( anyString(), any( Filter.class ) ) ).thenReturn( mock( FilterRegistration.Dynamic.class ) );
        McpEndpointBootstrapper.builder().logTag( "Test" ).endpointPath( "/test-mcp" ).filterName( "testFilter" )
            .servletName( "testServlet" ).requiredScope( ApiKeyService.Scope.WORK ).keyOnly( true )
            .agentTokens( token -> "wka_known".equals( token )
                ? java.util.Optional.of( new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 5, 6 ) )
                : java.util.Optional.empty() )
            .engine( mock( Engine.class ) ).build().registerAccessFilter( ctx );
        final ArgumentCaptor< Filter > captured = ArgumentCaptor.forClass( Filter.class );
        verify( ctx ).addFilter( eq( "testFilter" ), captured.capture() );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "1.2.3.4" );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wka_known" );
        final McpAccessFilter.Outcome outcome = ( ( McpAccessFilter ) captured.getValue() ).authorize( req );
        assertEquals( "agent:6", assertInstanceOf( McpAccessFilter.Outcome.Allowed.class, outcome ).clientId() );
    }
}

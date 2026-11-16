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

import com.wikantik.WikiEngine;
import com.wikantik.api.spi.EngineSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.mcp.McpAccessFilter;
import com.wikantik.work.WorkService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkMcpInitializerTest {

    private static void start( final WorkMcpInitializer initializer, final ServletContext ctx, final WikiEngine engine ) {
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) ).thenReturn( engine );
        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            initializer.contextInitialized( sce );
        }
    }

    @Test
    void disabledFeatureRegistersNothing() {
        final ServletContext ctx = mock( ServletContext.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.workService() ).thenReturn( null );   // wikantik.work.enabled=false, or work failed to wire

        start( new WorkMcpInitializer(), ctx, engine );

        verify( ctx, never() ).addFilter( anyString(), any( Filter.class ) );
        verify( ctx, never() ).addServlet( anyString(), any( Servlet.class ) );
    }

    @Test
    void anUnavailableEngineRegistersNothing() {
        final ServletContext ctx = mock( ServletContext.class );
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) ).thenThrow( new RuntimeException( "engine down" ) );
        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            new WorkMcpInitializer().contextInitialized( sce );
        }
        verify( ctx, never() ).addFilter( anyString(), any( Filter.class ) );
        verify( ctx, never() ).addServlet( anyString(), any( Servlet.class ) );
    }

    @Test
    void enabledRegistersKeyOnlyFilterAndServletAtWorkMcp() {
        final ServletContext ctx = mock( ServletContext.class );
        final FilterRegistration.Dynamic filterReg = mock( FilterRegistration.Dynamic.class );
        final ServletRegistration.Dynamic servletReg = mock( ServletRegistration.Dynamic.class );
        when( ctx.addFilter( anyString(), any( Filter.class ) ) ).thenReturn( filterReg );
        when( ctx.addServlet( anyString(), any( Servlet.class ) ) ).thenReturn( servletReg );
        final WikiEngine engine = mock( WikiEngine.class );
        // Properties that would open the other MCP endpoints to anyone: the work endpoint must ignore them.
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        props.setProperty( "mcp.access.allowedCidrs", "0.0.0.0/0" );
        when( engine.getWikiProperties() ).thenReturn( props );
        when( engine.workService() ).thenReturn( mock( WorkService.class ) );
        ApiKeyServiceHolder.setForTesting( mock( ApiKeyService.class ) );
        final WorkMcpInitializer initializer = new WorkMcpInitializer();
        try {
            start( initializer, ctx, engine );

            final ArgumentCaptor< Filter > filter = ArgumentCaptor.forClass( Filter.class );
            verify( ctx ).addFilter( eq( "WorkMcpAccessFilter" ), filter.capture() );
            assertInstanceOf( McpAccessFilter.class, filter.getValue() );
            verify( filterReg ).addMappingForUrlPatterns( any(), eq( true ), eq( "/work-mcp" ) );
            verify( ctx ).addServlet( eq( "WorkMcpTransportServlet" ), any( Servlet.class ) );
            verify( servletReg ).addMapping( "/work-mcp" );
            verify( servletReg ).setLoadOnStartup( 4 );
            verify( ctx ).setAttribute( eq( WorkMcpInitializer.ATTR_WORK_MCP_SERVER ), any() );

            final HttpServletRequest keyless = mock( HttpServletRequest.class );
            when( keyless.getRemoteAddr() ).thenReturn( "10.1.2.3" );
            final McpAccessFilter.Outcome outcome = ( ( McpAccessFilter ) filter.getValue() ).authorize( keyless );
            assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome, "a keyless request is refused" );
            assertEquals( 403, ( ( McpAccessFilter.Outcome.Denied ) outcome ).status() );
        } finally {
            initializer.contextDestroyed( mock( ServletContextEvent.class ) );
            ApiKeyServiceHolder.setForTesting( null );
        }
    }

    @Test
    void assembledToolNames() {
        final List< WorkMcpTool > tools = WorkMcpInitializer.assembleTools( mock( WorkService.class ),
            mock( com.wikantik.work.WorkDirectory.class ), Duration.ofMinutes( 30 ) );
        assertEquals( Set.of( "get_work_context", "list_claimable", "get_item" ),
            tools.stream().map( WorkMcpTool::name ).collect( Collectors.toSet() ) );
        tools.forEach( t -> {
            assertEquals( t.name(), t.definition().name() );
            assertNotNull( t.definition().annotations() );
            assertEquals( Boolean.TRUE, t.definition().annotations().readOnlyHint(), t.name() + " only reads" );
        } );
    }

    @Test
    void serverInstructionsAreTheClasspathResource() {
        assertNotNull( WorkMcpInitializer.serverInstructions() );
        org.junit.jupiter.api.Assertions.assertFalse( WorkMcpInitializer.serverInstructions().isBlank() );
    }
}

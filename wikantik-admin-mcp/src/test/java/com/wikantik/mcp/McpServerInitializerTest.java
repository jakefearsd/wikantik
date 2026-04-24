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
import com.wikantik.api.spi.EngineSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletRegistration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the wiring contract that the IT regression caught: the initializer must
 * resolve the {@link ApiKeyService} from {@link ApiKeyServiceHolder} using the
 * engine's properties and pass that exact instance into {@link McpAccessFilter}'s
 * constructor before registering the filter at {@code /mcp}. A single fragile
 * unit test here is the cheapest way to lock that down without spinning a
 * full IT.
 */
class McpServerInitializerTest {

    private ServletContextEvent eventWith( final ServletContext ctx ) {
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        return sce;
    }

    private final FilterRegistration.Dynamic filterReg = mock( FilterRegistration.Dynamic.class );
    private final ServletRegistration.Dynamic servletReg = mock( ServletRegistration.Dynamic.class );

    private ServletContext servletContextWithRegistrations() {
        final ServletContext ctx = mock( ServletContext.class );
        // The initializer registers a filter and a servlet — both calls must return
        // a non-null Dynamic registration so the wiring lines don't NPE.
        when( ctx.addFilter( anyString(), any( Filter.class ) ) ).thenReturn( filterReg );
        when( ctx.addServlet( anyString(), any( jakarta.servlet.Servlet.class ) ) ).thenReturn( servletReg );
        return ctx;
    }

    private static ApiKeyService apiKeyServiceField( final Filter filter ) throws Exception {
        final Field f = filter.getClass().getDeclaredField( "apiKeyService" );
        f.setAccessible( true );
        return ( ApiKeyService ) f.get( filter );
    }

    @Test
    void registersAccessFilterWithApiKeyServiceFromHolder() throws Exception {
        final ServletContext ctx = servletContextWithRegistrations();
        final ServletContextEvent sce = eventWith( ctx );

        final Properties engineProps = new Properties();
        engineProps.setProperty( "marker", "engine-props" );
        final Engine engine = mock( Engine.class );
        when( engine.getWikiProperties() ).thenReturn( engineProps );

        final EngineSPI engineSpi = mock( EngineSPI.class );
        when( engineSpi.find( ctx, null ) ).thenReturn( engine );

        final ApiKeyService injected = mock( ApiKeyService.class );

        try ( MockedStatic< Wiki > wikiStatic = mockStatic( Wiki.class );
              MockedStatic< ApiKeyServiceHolder > holderStatic = mockStatic( ApiKeyServiceHolder.class ) ) {
            wikiStatic.when( Wiki::engine ).thenReturn( engineSpi );
            holderStatic.when( () -> ApiKeyServiceHolder.get( engineProps ) ).thenReturn( injected );

            new McpServerInitializer().contextInitialized( sce );

            // The exact holder lookup must use the engine's properties — that's the
            // contract that broke in IT mode.
            holderStatic.verify( () -> ApiKeyServiceHolder.get( engineProps ), times( 1 ) );

            final ArgumentCaptor< Filter > filterCaptor = ArgumentCaptor.forClass( Filter.class );
            verify( ctx ).addFilter( eq( "McpAccessFilter" ), filterCaptor.capture() );
            final Filter filter = filterCaptor.getValue();
            assertNotNull( filter, "Initializer must construct and register a filter instance" );
            assertSame( injected, apiKeyServiceField( filter ),
                    "Filter must hold the exact ApiKeyService returned by the holder" );
        }
    }

    @Test
    void registersFilterAtMcpUrlPattern() {
        final ServletContext ctx = servletContextWithRegistrations();
        final ServletContextEvent sce = eventWith( ctx );

        final Engine engine = mock( Engine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        final EngineSPI engineSpi = mock( EngineSPI.class );
        when( engineSpi.find( ctx, null ) ).thenReturn( engine );

        try ( MockedStatic< Wiki > wikiStatic = mockStatic( Wiki.class );
              MockedStatic< ApiKeyServiceHolder > holderStatic = mockStatic( ApiKeyServiceHolder.class ) ) {
            wikiStatic.when( Wiki::engine ).thenReturn( engineSpi );
            holderStatic.when( () -> ApiKeyServiceHolder.get( any( Properties.class ) ) ).thenReturn( null );

            new McpServerInitializer().contextInitialized( sce );

            final ArgumentCaptor< String > patterns = ArgumentCaptor.forClass( String.class );
            verify( filterReg ).addMappingForUrlPatterns( any(), eq( false ), patterns.capture() );
            assertEquals( "/wikantik-admin-mcp", patterns.getValue(),
                    "MCP filter must guard the /wikantik-admin-mcp endpoint exactly" );
        }
    }

    @Test
    void engineLookupFailureSkipsFilterRegistration() {
        // If WikiEngine can't be found, the listener must bail before registering
        // the filter — otherwise we'd install an unconfigured filter on the only
        // path the MCP server lives at.
        final ServletContext ctx = servletContextWithRegistrations();
        final ServletContextEvent sce = eventWith( ctx );

        final EngineSPI engineSpi = mock( EngineSPI.class );
        when( engineSpi.find( ctx, null ) ).thenThrow( new RuntimeException( "boom" ) );

        try ( MockedStatic< Wiki > wikiStatic = mockStatic( Wiki.class ) ) {
            wikiStatic.when( Wiki::engine ).thenReturn( engineSpi );

            new McpServerInitializer().contextInitialized( sce );

            verify( ctx, never() ).addFilter( anyString(), any( Filter.class ) );
        }
    }
}

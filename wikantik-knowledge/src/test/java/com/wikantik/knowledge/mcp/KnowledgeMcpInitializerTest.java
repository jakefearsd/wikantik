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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.EngineSPI;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class KnowledgeMcpInitializerTest {

    @Test
    void contextInitialized_returnsSilentlyWhenEngineUnavailable() {
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        final ServletContext ctx = mock( ServletContext.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) )
            .thenThrow( new RuntimeException( "engine down" ) );

        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            new KnowledgeMcpInitializer().contextInitialized( sce );
        }
        // No filter or servlet registered when the engine can't be located.
        verify( ctx, never() ).addFilter( any( String.class ), any( jakarta.servlet.Filter.class ) );
        verify( ctx, never() ).addServlet( any( String.class ), any( jakarta.servlet.Servlet.class ) );
    }

    @Test
    void contextInitialized_returnsSilentlyWhenNeitherKnowledgeServicePresent() {
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        final ServletContext ctx = mock( ServletContext.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( KnowledgeGraphService.class ) ).thenReturn( null );
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( null );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) ).thenReturn( engine );

        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            new KnowledgeMcpInitializer().contextInitialized( sce );
        }
        verify( ctx, never() ).addFilter( any( String.class ), any( jakarta.servlet.Filter.class ) );
        verify( ctx, never() ).addServlet( any( String.class ), any( jakarta.servlet.Servlet.class ) );
    }
}

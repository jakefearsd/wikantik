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
package com.wikantik.knowledge;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.EngineSPI;
import com.wikantik.api.spi.Wiki;
import com.wikantik.search.SearchManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ContextRetrievalServiceInitializerTest {

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
            // Should not throw; the initializer is expected to log and return.
            new ContextRetrievalServiceInitializer().contextInitialized( sce );
        }
    }

    @Test
    void contextInitialized_returnsSilentlyWhenRequiredManagersMissing() {
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        final ServletContext ctx = mock( ServletContext.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( null );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) ).thenReturn( engine );

        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            new ContextRetrievalServiceInitializer().contextInitialized( sce );
        }
        verify( engine, never() ).getManager( ContextRetrievalService.class );
    }

    @Test
    void contextInitialized_logsWhenEngineNotWikiEngineSubclass() {
        // Engine has PM + SM but is not a WikiEngine — branch logs and does not setManager.
        final ServletContextEvent sce = mock( ServletContextEvent.class );
        final ServletContext ctx = mock( ServletContext.class );
        when( sce.getServletContext() ).thenReturn( ctx );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( mock( PageManager.class ) );
        when( engine.getManager( SearchManager.class ) ).thenReturn( mock( SearchManager.class ) );
        when( engine.getBaseURL() ).thenReturn( "https://wiki.example" );
        final EngineSPI spi = mock( EngineSPI.class );
        when( spi.find( any( ServletContext.class ), isNull() ) ).thenReturn( engine );

        try ( final MockedStatic< Wiki > mocked = mockStatic( Wiki.class ) ) {
            mocked.when( Wiki::engine ).thenReturn( spi );
            new ContextRetrievalServiceInitializer().contextInitialized( sce );
        }
    }
}

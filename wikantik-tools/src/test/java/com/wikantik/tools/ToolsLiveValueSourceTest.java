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
package com.wikantik.tools;

import com.wikantik.WikiEngine;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.core.registry.EngineServiceRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolsLiveValueSourceTest {

    @Test
    void publishesToolCountUnderItsName() {
        final WikiEngine engine = mock( WikiEngine.class );
        final EngineServiceRegistry registry = new EngineServiceRegistry();
        final LiveValues lv = mock( LiveValues.class );
        registry.put( LiveValues.class, lv );
        when( engine.serviceRegistry() ).thenReturn( registry );
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( mock( ToolsConfig.class ), new ToolsMetrics(),
                mock( SearchWikiTool.class ), mock( GetPageTool.class ) );
        final int expected = 2;
        ToolsServerInitializer.publishToolCount( engine, servlet );
        final ArgumentCaptor< LiveValueSource > c = ArgumentCaptor.forClass( LiveValueSource.class );
        verify( lv ).register( c.capture() );
        assertEquals( new Resolution.Value( expected ), c.getValue().values().get( "tools.openapi" ) );
    }
}

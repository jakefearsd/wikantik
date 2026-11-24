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
package com.wikantik.livevalues;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.core.registry.EngineServiceRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCountLiveValueTest {

    @Test
    void registersSourceWithCount() {
        final WikiEngine engine = mock( WikiEngine.class );
        final EngineServiceRegistry registry = new EngineServiceRegistry();
        final LiveValues lv = mock( LiveValues.class );
        registry.put( LiveValues.class, lv );
        when( engine.serviceRegistry() ).thenReturn( registry );

        assertTrue( ToolCountLiveValue.register( engine, "tools.x", 7 ) );

        final ArgumentCaptor< LiveValueSource > c = ArgumentCaptor.forClass( LiveValueSource.class );
        verify( lv ).register( c.capture() );
        assertEquals( new Resolution.Value( 7 ), c.getValue().values().get( "tools.x" ) );
        assertEquals( Set.of( "tools.x" ), c.getValue().declaredNames() );
    }

    @Test
    void skipsWhenServiceAbsentOrEngineNotWikiEngine() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.serviceRegistry() ).thenReturn( new EngineServiceRegistry() );
        assertFalse( ToolCountLiveValue.register( engine, "tools.x", 1 ) );
        assertFalse( ToolCountLiveValue.register( mock( Engine.class ), "tools.x", 1 ) );
        verify( mock( LiveValues.class ), never() ).register( any() );
    }
}

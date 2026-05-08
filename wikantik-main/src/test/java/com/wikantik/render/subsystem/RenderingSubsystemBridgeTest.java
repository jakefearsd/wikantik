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
package com.wikantik.render.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link RenderingSubsystemBridge}.
 *
 * <p>Confirms that {@link RenderingSubsystemBridge#rebuildFromManagers} delegates to
 * {@link RenderingSubsystemFactory#create} and produces a
 * {@link RenderingSubsystem.Services} record wired from the engine's manager registry.</p>
 */
final class RenderingSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresFourManagers() {
        final RenderingManager  rendering  = mock( RenderingManager.class );
        final PluginManager     plugins    = mock( PluginManager.class );
        final FilterManager     filters    = mock( FilterManager.class );
        final DifferenceManager difference = mock( DifferenceManager.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getManager( RenderingManager.class ) ).thenReturn( rendering );
        when( engine.getManager( PluginManager.class ) ).thenReturn( plugins );
        when( engine.getManager( FilterManager.class ) ).thenReturn( filters );
        when( engine.getManager( DifferenceManager.class ) ).thenReturn( difference );

        final RenderingSubsystem.Services services = RenderingSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( rendering,  services.renderingManager() );
        assertSame( plugins,    services.pluginManager() );
        assertSame( filters,    services.filterManager() );
        assertSame( difference, services.differenceManager() );
        // SpamFilter not registered as manager — spam helper slots should be null
        assertNull( services.spamRateLimiter() );
        assertNull( services.spamPatternMatcher() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        final RenderingSubsystem.Services services = RenderingSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.renderingManager() );
        assertNull( services.pluginManager() );
    }
}

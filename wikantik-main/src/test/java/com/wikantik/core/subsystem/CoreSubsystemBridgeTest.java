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
package com.wikantik.core.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.blog.BlogManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.variables.VariableManager;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link CoreSubsystemBridge}.
 *
 * <p>Confirms that {@link CoreSubsystemBridge#rebuildFromManagers} delegates to
 * {@link CoreSubsystemFactory#create} and produces a {@link CoreSubsystem.Services}
 * record wired from the engine's manager registry.</p>
 */
final class CoreSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresServicesFromRegistry() {
        final SystemPageRegistry   sys     = mock( SystemPageRegistry.class );
        final RecentArticlesManager recent = mock( RecentArticlesManager.class );
        final BlogManager          blog    = mock( BlogManager.class );
        final CachingManager       caching = mock( CachingManager.class );
        final VariableManager      vars    = mock( VariableManager.class );

        final Properties raw = new Properties();
        raw.setProperty( "answer", "42" );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( raw );
        when( engine.getManager( SystemPageRegistry.class ) ).thenReturn( sys );
        when( engine.getManager( RecentArticlesManager.class ) ).thenReturn( recent );
        when( engine.getManager( BlogManager.class ) ).thenReturn( blog );
        when( engine.getManager( CachingManager.class ) ).thenReturn( caching );
        when( engine.getManager( VariableManager.class ) ).thenReturn( vars );

        final CoreSubsystem.Services services = CoreSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNotNull( services.properties() );
        assertSame( raw, services.properties().asProperties() );
        assertSame( sys, services.systemPageRegistry() );
        assertSame( recent, services.recentArticlesManager() );
        assertSame( blog, services.blogManager() );
        assertSame( caching, services.cachingManager() );
        assertSame( vars, services.variableManager() );
    }

    @Test
    void rebuildFromManagers_toleratesNullWikiProperties() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( null );

        final CoreSubsystem.Services services = CoreSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNotNull( services.properties() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        // No managers registered — all getManager() calls return null

        final CoreSubsystem.Services services = CoreSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.systemPageRegistry() );
        assertNull( services.cachingManager() );
    }
}

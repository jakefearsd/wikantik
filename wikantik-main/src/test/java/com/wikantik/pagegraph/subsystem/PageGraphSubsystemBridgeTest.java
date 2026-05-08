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
package com.wikantik.pagegraph.subsystem;

import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link PageGraphSubsystemBridge}.
 *
 * <p>Confirms that {@link PageGraphSubsystemBridge#rebuildFromManagers} delegates to
 * {@link PageGraphSubsystemFactory#create} and produces a
 * {@link PageGraphSubsystem.Services} record wired from the engine's manager registry.</p>
 */
final class PageGraphSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresFourServices() {
        final StructuralIndexService     structuralIndex = mock( StructuralIndexService.class );
        final PageGraphService           pageGraph       = mock( PageGraphService.class );
        final ReferenceManager           refMgr          = mock( ReferenceManager.class );
        final ContentIndexRebuildService rebuildService  = mock( ContentIndexRebuildService.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( structuralIndex );
        when( engine.getManager( PageGraphService.class ) ).thenReturn( pageGraph );
        when( engine.getManager( ReferenceManager.class ) ).thenReturn( refMgr );
        when( engine.getManager( ContentIndexRebuildService.class ) ).thenReturn( rebuildService );

        final PageGraphSubsystem.Services services = PageGraphSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( structuralIndex, services.structuralIndexService() );
        assertSame( pageGraph,       services.pageGraphService() );
        assertSame( refMgr,          services.referenceManager() );
        assertSame( rebuildService,  services.contentIndexRebuildService() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        final PageGraphSubsystem.Services services = PageGraphSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.structuralIndexService() );
        assertNull( services.pageGraphService() );
    }
}

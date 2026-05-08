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
package com.wikantik.search.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.hybrid.HybridSearchService;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link SearchSubsystemBridge}.
 *
 * <p>Confirms that {@link SearchSubsystemBridge#rebuildFromManagers} delegates to
 * {@link SearchSubsystemFactory#create} and produces a {@link SearchSubsystem.Services}
 * record wired from the engine's manager registry.</p>
 */
final class SearchSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresManagersFromRegistry() {
        final SearchManager          searchManager  = mock( SearchManager.class );
        final HybridSearchService    hybridSearch   = mock( HybridSearchService.class );
        final FrontmatterMetadataCache metaCache    = mock( FrontmatterMetadataCache.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getManager( SearchManager.class ) ).thenReturn( searchManager );
        when( engine.getManager( HybridSearchService.class ) ).thenReturn( hybridSearch );
        when( engine.getManager( FrontmatterMetadataCache.class ) ).thenReturn( metaCache );

        final SearchSubsystem.Services services = SearchSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( searchManager, services.searchManager() );
        assertSame( hybridSearch,  services.hybridSearch() );
        assertSame( metaCache,     services.frontmatterMetadataCache() );
        // Lucene helpers are null when searchManager has no provider
        assertNull( services.luceneIndexer() );
        assertNull( services.luceneSearcher() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );

        final SearchSubsystem.Services services = SearchSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.searchManager() );
        assertNull( services.hybridSearch() );
    }
}

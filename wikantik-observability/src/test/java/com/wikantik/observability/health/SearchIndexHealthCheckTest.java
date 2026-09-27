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
package com.wikantik.observability.health;

import com.wikantik.api.core.Engine;
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class SearchIndexHealthCheckTest {

    @Mock private WikiEngine engine;
    @Mock private PageManager pageManager;
    @Mock private PageProvider pageProvider;

    @Test
    void reportsUpWhenPageManagerIsAvailable() {
        // D1: previously the probe asked the engine for PageProvider.class which is never
        // registered as a manager, returning null and reporting DOWN even on a healthy system.
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getProvider() ).thenReturn( pageProvider );

        final SearchIndexHealthCheck check = new SearchIndexHealthCheck( engine );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.UP, result.status() );
        assertTrue( result.responseTimeMs() >= 0 );
    }

    @Test
    void reportsDownWhenPageManagerIsNullAndNoProvider() {
        // D1: probe must report DOWN with an accurate message when neither the registered
        // PageManager nor the legacy PageProvider is present.
        when( engine.getManager( PageManager.class ) ).thenReturn( null );
        when( engine.getManager( PageProvider.class ) ).thenReturn( null );

        final SearchIndexHealthCheck check = new SearchIndexHealthCheck( engine );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.DOWN, result.status() );
        assertTrue( result.detail().containsKey( "error" ) );
    }

    @Test
    void reportsUpWhenPageManagerIsNullButLegacyProviderIsAvailable() {
        // Legacy fallback: PageSubsystemFactory only ever derives pageProvider() from
        // pages.getProvider() (null when pages is null), so the only way a snapshot can carry
        // a PageProvider with a null PageManager is a pre-built typed snapshot — mirroring a
        // caller that registered PageProvider directly, bypassing PageManager entirely.
        when( engine.getPageSubsystem() ).thenReturn( new com.wikantik.page.subsystem.PageSubsystem.Services(
            null, null, null, null, pageProvider, null, null, null, null ) );

        final SearchIndexHealthCheck check = new SearchIndexHealthCheck( engine );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.UP, result.status() );
        verify( pageProvider ).getProviderInfo();
    }

    @Test
    void reportsDownWhenPageManagerThrows() {
        when( engine.getManager( PageManager.class ) ).thenThrow( new RuntimeException( "manager error" ) );

        final SearchIndexHealthCheck check = new SearchIndexHealthCheck( engine );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.DOWN, result.status() );
        assertEquals( "Search index check failed", result.detail().get( "error" ) );
    }

    @Test
    void nameIsSearchIndex() {
        final SearchIndexHealthCheck check = new SearchIndexHealthCheck( engine );
        assertEquals( "searchIndex", check.name() );
    }

}

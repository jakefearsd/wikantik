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

import com.wikantik.TestEngine;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.pagegraph.spine.DefaultStructuralIndexService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the real wiring against a booted engine: the AuthorizationManager must be found in the service registry at
 * runtime and the guest-view predicate must yield real counts (TestEngine has no datasource, so the full
 * structural-spine boot step is not run; the index is a stub).
 */
class LiveValuesWiringTest {

    @Test
    void wiredServiceCountsPublicPagesUsingTheEnginesAuthorizationManager() throws Exception {
        final TestEngine engine = TestEngine.build(
            com.wikantik.TestEngine.with( "wikantik.access.privateClusters", "lvw-secret" ) );
        try {
            assertNotNull( engine.serviceRegistry().get( AuthorizationManager.class ), "auth manager in registry" );
            final DefaultStructuralIndexService index = mock( DefaultStructuralIndexService.class );
            final StructuralIndexService.StructuralProjectionSnapshot snap =
                mock( StructuralIndexService.StructuralProjectionSnapshot.class );
            when( snap.revision() ).thenReturn( 1L );
            when( index.snapshot() ).thenReturn( snap );
            engine.saveText( "WiringPageA", "Open page A." );
            engine.saveText( "WiringPageB", "Open page B." );
            engine.saveText( "WiringAclSecret", "[{ALLOW view Admin}]\n\nClassified." );
            engine.saveText( "WiringClusterSecret", "---\ncluster: lvw-secret\n---\nPrivate cluster member.\n" );
            final List< PageDescriptor > pages = List.of(
                new PageDescriptor( "id-c", "WiringAclSecret", "C", PageType.ARTICLE, null, List.of(), List.of(), null, null, false ),
                new PageDescriptor( "id-d", "WiringClusterSecret", "D", PageType.ARTICLE, null, List.of( "lvw-secret" ),
                                    List.of(), null, null, false ),
                new PageDescriptor( "id-a", "WiringPageA", "A", PageType.ARTICLE, null, List.of(), List.of(), null, null, false ),
                new PageDescriptor( "id-b", "WiringPageB", "B", PageType.ARTICLE, null, List.of(), List.of(), null, null, false ) );
            when( index.sitemap() ).thenReturn( new Sitemap( pages, pages.size(), Instant.EPOCH ) );
            final CoreSubsystem.Services core = mock( CoreSubsystem.Services.class );
            when( core.systemPageRegistry() ).thenReturn( mock( SystemPageRegistry.class ) );

            PageGraphWiringHelper.wireLiveValues( new Properties(), core, index, engine );

            final LiveValues lv = engine.serviceRegistry().get( LiveValues.class );
            assertNotNull( lv, "LiveValues registered" );
            // ACL-restricted and private-cluster pages are in the index but must not be counted
            assertEquals( "2", lv.expandText( "{{wiki:pages}}" ) );
            assertEquals( "2", lv.expandText( "{{wiki:articles}}" ) );
        } finally {
            engine.stop();
        }
    }
}

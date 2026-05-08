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
import com.wikantik.core.subsystem.CoreSubsystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 Checkpoint 1 subsystem-isolation test for
 * {@link PageGraphSubsystemFactory}.
 *
 * <p>Demonstrates that the Page Graph subsystem can be assembled without
 * {@code WikiEngine} or {@code TestEngine}: a mocked {@link Engine}
 * stocked with the relevant managers is enough to produce a populated
 * {@link PageGraphSubsystem.Services}.</p>
 */
final class PageGraphSubsystemFactoryTest {

    @Test
    void createWiresAllFourServicesFromEngineRegistry() {
        final StructuralIndexService     structuralIndex            = mock( StructuralIndexService.class );
        final PageGraphService           pageGraphService           = mock( PageGraphService.class );
        final ReferenceManager           referenceManager           = mock( ReferenceManager.class );
        final ContentIndexRebuildService contentIndexRebuildService = mock( ContentIndexRebuildService.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( structuralIndex );
        when( engine.getManager( PageGraphService.class ) ).thenReturn( pageGraphService );
        when( engine.getManager( ReferenceManager.class ) ).thenReturn( referenceManager );
        when( engine.getManager( ContentIndexRebuildService.class ) ).thenReturn( contentIndexRebuildService );

        final PageGraphSubsystem.Services services = PageGraphSubsystemFactory.create(
            new PageGraphSubsystem.Deps(
                /*core=*/        mock( CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/        null,
                engine ) );

        assertNotNull( services );
        assertSame( structuralIndex,            services.structuralIndexService() );
        assertSame( pageGraphService,           services.pageGraphService() );
        assertSame( referenceManager,           services.referenceManager() );
        assertSame( contentIndexRebuildService, services.contentIndexRebuildService() );
    }

    @Test
    void createTreatsMissingManagersAsNullSlots() {
        // No managers stubbed — every getManager() returns null. The factory
        // still produces a Services record with every field null (mirrors
        // the legacy getManager() behaviour for unwired engines).
        final WikiEngine engine = mock( WikiEngine.class );
        final PageGraphSubsystem.Services services = PageGraphSubsystemFactory.create(
            new PageGraphSubsystem.Deps(
                /*core=*/        mock( CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/        null,
                engine ) );

        assertNotNull( services );
        assertNull( services.structuralIndexService() );
        assertNull( services.pageGraphService() );
        assertNull( services.referenceManager() );
        assertNull( services.contentIndexRebuildService() );
    }

    @Test
    void createRejectsMissingDeps() {
        // null deps record → NPE
        assertThrows( NullPointerException.class,
            () -> PageGraphSubsystemFactory.create( null ) );
        // null engine → NPE (core/persistence/page are optional — factory does not use them today)
        assertThrows( NullPointerException.class,
            () -> PageGraphSubsystemFactory.create(
                new PageGraphSubsystem.Deps(
                    mock( CoreSubsystem.Services.class ), null, null, null ) ) );
    }
}

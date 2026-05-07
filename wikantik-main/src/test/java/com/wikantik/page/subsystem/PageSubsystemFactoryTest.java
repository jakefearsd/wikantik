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
package com.wikantik.page.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.PageRenamer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 subsystem-isolation test for {@link PageSubsystemFactory}.
 *
 * <p>Demonstrates that the Page subsystem can be assembled without
 * {@code WikiEngine} or {@code TestEngine}: a mocked {@link Engine}
 * stocked with the three core page managers + a stub {@link PageProvider}
 * is enough to produce a fully populated {@link PageSubsystem.Services}.</p>
 */
final class PageSubsystemFactoryTest {

    @Test
    void createWiresThreeManagersAndProviderFromEngineRegistry() {
        final PageProvider      provider    = mock( PageProvider.class );
        final PageManager       pages       = mock( PageManager.class );
        final AttachmentManager attachments = mock( AttachmentManager.class );
        final PageRenamer       renamer     = mock( PageRenamer.class );
        final ReferenceManager  refMgr      = mock( ReferenceManager.class );

        when( pages.getProvider() ).thenReturn( provider );

        final Engine engine = mock( Engine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pages );
        when( engine.getManager( AttachmentManager.class ) ).thenReturn( attachments );
        when( engine.getManager( PageRenamer.class ) ).thenReturn( renamer );
        when( engine.getManager( ReferenceManager.class ) ).thenReturn( refMgr );

        final PageSubsystem.Services services = PageSubsystemFactory.create(
            new PageSubsystem.Deps(
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*auth=*/ null,
                engine ) );

        assertSame( pages, services.pages() );
        assertSame( attachments, services.attachments() );
        assertSame( renamer, services.pageRenamer() );
        assertSame( provider, services.pageProvider() );
        assertNotNull( services.pageSaveHelper(), "pageSaveHelper" );
        assertSame( refMgr, services.referenceManager() );
        // pageRepository/pageLifecycle/pageLockService are null when pages is a mock
        // (not a DefaultPageManager) — that is expected and acceptable in this test

    }

    @Test
    void createRejectsMissingDeps() {
        assertThrows( NullPointerException.class, () -> PageSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> PageSubsystemFactory.create(
            new PageSubsystem.Deps( null, null, null, mock( Engine.class ) ) ) );
        assertThrows( NullPointerException.class, () -> PageSubsystemFactory.create(
            new PageSubsystem.Deps(
                mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                null, null, null ) ) );
    }
}

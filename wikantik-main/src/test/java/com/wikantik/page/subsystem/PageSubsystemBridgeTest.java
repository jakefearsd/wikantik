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

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.content.PageRenamer;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 bridge-delegation test for {@link PageSubsystemBridge}.
 *
 * <p>Confirms that {@link PageSubsystemBridge#rebuildFromManagers} delegates to
 * {@link PageSubsystemFactory#create} and produces a {@link PageSubsystem.Services}
 * record wired from the engine's manager registry.</p>
 */
final class PageSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_delegatesToFactory_wiresManagersFromRegistry() {
        final PageManager       pages       = mock( PageManager.class );
        final AttachmentManager attachments = mock( AttachmentManager.class );
        final PageRenamer       renamer     = mock( PageRenamer.class );
        final ReferenceManager  refMgr      = mock( ReferenceManager.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getManager( PageManager.class ) ).thenReturn( pages );
        when( engine.getManager( AttachmentManager.class ) ).thenReturn( attachments );
        when( engine.getManager( PageRenamer.class ) ).thenReturn( renamer );
        when( engine.getManager( ReferenceManager.class ) ).thenReturn( refMgr );

        final PageSubsystem.Services services = PageSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( pages,       services.pages() );
        assertSame( attachments, services.attachments() );
        assertSame( renamer,     services.pageRenamer() );
        assertSame( refMgr,      services.referenceManager() );
        assertNotNull( services.pageSaveHelper() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        // No managers registered

        final PageSubsystem.Services services = PageSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.pages() );
        assertNull( services.attachments() );
    }
}

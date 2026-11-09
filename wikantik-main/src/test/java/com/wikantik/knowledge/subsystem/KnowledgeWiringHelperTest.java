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
package com.wikantik.knowledge.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.knowledge.chunking.ChunkProjector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@link KnowledgeWiringHelper#wireChunkLifecycle}: chunk rows follow page deletes and renames. */
class KnowledgeWiringHelperTest {

    @Test
    void wireChunkLifecycle_registersOnBothEventSourcesAndKeepsTheListenerStronglyReachable() {
        final WikiEngine engine = mock( WikiEngine.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final com.wikantik.api.managers.PageManager pageManager = mock( com.wikantik.api.managers.PageManager.class );
        final com.wikantik.content.PageRenamer renamer = mock( com.wikantik.content.PageRenamer.class );

        final com.wikantik.knowledge.chunking.ChunkLifecycleListener listener =
            KnowledgeWiringHelper.wireChunkLifecycle( chunkProjector, pageManager, renamer, engine );
        try {
            verify( engine ).setManager( com.wikantik.knowledge.chunking.ChunkLifecycleListener.class, listener );
            com.wikantik.event.WikiEventManager.fireEvent( pageManager, new com.wikantik.event.WikiPageEvent(
                this, com.wikantik.event.WikiPageEvent.PAGE_DELETED, "Gone" ) );
            com.wikantik.event.WikiEventManager.fireEvent( renamer,
                new com.wikantik.event.WikiPageRenameEvent( renamer, "Old", "New" ) );
            verify( chunkProjector ).onPageDeleted( "Gone" );
            verify( chunkProjector ).onPageRenamed( "Old", "New" );
        } finally {
            com.wikantik.event.WikiEventManager.removeWikiEventListener( pageManager, listener );
            com.wikantik.event.WikiEventManager.removeWikiEventListener( renamer, listener );
        }
    }

    @Test
    void wireChunkLifecycle_withoutAProjectorWiresNothing() {
        final WikiEngine engine = mock( WikiEngine.class );
        assertTrue( KnowledgeWiringHelper.wireChunkLifecycle( null, null, null, engine ) == null );
        verify( engine, never() ).setManager( any(), any() );
    }
}

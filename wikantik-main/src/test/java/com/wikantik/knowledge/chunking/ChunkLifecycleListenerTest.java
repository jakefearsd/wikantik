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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.managers.PageManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChunkLifecycleListenerTest {

    private ChunkProjector projector;
    private ChunkLifecycleListener listener;
    private PageManager pageManager;
    private PageRenamer renamer;

    @BeforeEach
    void setUp() {
        projector = mock( ChunkProjector.class );
        listener = new ChunkLifecycleListener( projector );
        pageManager = mock( PageManager.class );
        renamer = mock( PageRenamer.class );
        listener.register( pageManager, renamer );
    }

    @AfterEach
    void tearDown() {
        WikiEventManager.removeWikiEventListener( pageManager, listener );
        WikiEventManager.removeWikiEventListener( renamer, listener );
    }

    @Test
    void pageDeletedFromThePageManagerDropsTheChunks() {
        WikiEventManager.fireEvent( pageManager, new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Gone" ) );

        verify( projector ).onPageDeleted( "Gone" );
    }

    @Test
    void aPageDeletedEventForAPageThatStillExistsKeepsItsChunks() throws Exception {
        // A provider whose file delete failed: the page is still there, its chunks must stay.
        when( pageManager.pageExists( "Stuck" ) ).thenReturn( true );

        WikiEventManager.fireEvent( pageManager, new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Stuck" ) );

        verify( projector, never() ).onPageDeleted( anyString() );
    }

    @Test
    void aRenameFromTheRenamerRekeysTheChunksAndDeletesNothing() {
        WikiEventManager.fireEvent( renamer, new WikiPageRenameEvent( renamer, "OldName", "NewName" ) );

        verify( projector ).onPageRenamed( "OldName", "NewName" );
        verify( projector, never() ).onPageDeleted( anyString() );
    }

    @Test
    void otherPageEventsAreIgnored() {
        WikiEventManager.fireEvent( pageManager, new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Saved" ) );
        WikiEventManager.fireEvent( pageManager, new WikiPageEvent( this, WikiPageEvent.PAGE_DELETE_REQUEST, "Gone" ) );

        verifyNoInteractions( projector );
    }
}

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
package com.wikantik.citation;

import static org.mockito.Mockito.*;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.junit.jupiter.api.Test;

class CitationEventListenerTest {

    @Test
    void postSaveEndDispatchesOnPageSaved() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "MyPage" ) );
        verify( sync ).onPageSaved( "MyPage" );
    }

    @Test
    void pageDeletedDispatchesOnPageDeleted() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "MyPage" ) );
        verify( sync ).onPageDeleted( "MyPage" );
    }

    @Test
    void renameEventDispatchesOnPageRenamed() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageRenameEvent( this, "OldPage", "NewPage" ) );
        verify( sync ).onPageRenamed( "OldPage", "NewPage" );
    }

    @Test
    void unrelatedEventIsIgnored() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "SomePage" ) );
        verifyNoInteractions( sync );
    }
}

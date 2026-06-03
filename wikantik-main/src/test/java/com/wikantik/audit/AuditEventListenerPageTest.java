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
package com.wikantik.audit;

import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuditEventListenerPageTest {

    /** Capturing AuditService stub — same pattern as AuditEventListenerTest. */
    private static final class CapturingService implements AuditService {
        final List<AuditEntry> recorded = new ArrayList<>();
        public void record( AuditEntry e ) { recorded.add( e ); }
        public List<PersistedAuditEntry> query( AuditQuery q ) { return List.of(); }
        public java.util.Optional<Long> verifyChain( long a, long b ) { return java.util.Optional.empty(); }
        public long droppedCount() { return 0; }
    }

    // Real WikiPageEvent API:
    //   constructor: WikiPageEvent( Object src, int type, String newPagename )
    //   page-name accessor: getPageName()
    //   constants used: PAGE_DELETED (27), POST_SAVE (22)
    //
    // Real WikiPageRenameEvent API:
    //   constructor: WikiPageRenameEvent( Object src, String oldname, String newname )
    //   old-name accessor: getOldPageName()
    //   new-name accessor: getNewPageName()

    @Test
    void pageDeleteMapsToContentSuccess() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "MyPage" );

        listener.actionPerformed( evt );

        assertEquals( 1, svc.recorded.size() );
        AuditEntry e = svc.recorded.get( 0 );
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.delete", e.eventType() );
        assertEquals( "page", e.targetType() );
        assertEquals( "MyPage", e.targetId() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
    }

    @Test
    void postSaveMapsToPageSave() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "SomePage" );

        listener.actionPerformed( evt );

        assertEquals( 1, svc.recorded.size() );
        AuditEntry e = svc.recorded.get( 0 );
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.save", e.eventType() );
        assertEquals( "page", e.targetType() );
        assertEquals( "SomePage", e.targetId() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
    }

    @Test
    void pageRenameMapsToContentRenameWithDetail() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        WikiPageRenameEvent evt = new WikiPageRenameEvent( this, "OldName", "NewName" );

        listener.actionPerformed( evt );

        assertEquals( 1, svc.recorded.size() );
        AuditEntry e = svc.recorded.get( 0 );
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.rename", e.eventType() );
        assertEquals( "page", e.targetType() );
        assertEquals( "NewName", e.targetId() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertNotNull( e.detail() );
        assertTrue( e.detail().contains( "OldName" ), "detail should contain old name" );
        assertTrue( e.detail().contains( "NewName" ), "detail should contain new name" );
    }

    @Test
    void unmappedPageEventTypeIsIgnored() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        // PAGE_LOCK is not audited
        WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "SomePage" );

        listener.actionPerformed( evt );

        assertTrue( svc.recorded.isEmpty(), "PAGE_LOCK should not produce an audit entry" );
    }
}

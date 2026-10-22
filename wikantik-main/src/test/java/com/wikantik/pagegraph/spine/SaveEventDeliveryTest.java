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
package com.wikantik.pagegraph.spine;

import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.citation.CitationEventListener;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditEventListener;
import com.wikantik.audit.AuditService;
import com.wikantik.citation.CitationSync;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * One page save through a real engine must reach the structural-index and citation listeners exactly once.
 * The listeners are registered the way the production wiring registers them (PageManager + FilterManager).
 */
class SaveEventDeliveryTest {

    private static final String PAGE = "SaveEventDeliveryPage";

    private TestEngine engine;
    private final List< Integer > seenTypes = new CopyOnWriteArrayList<>();
    private final AtomicInteger structuralCalls = new AtomicInteger();
    private final AtomicInteger citationCalls = new AtomicInteger();
    // strong refs: WikiEventManager holds listeners weakly
    private WikiEventListener recorder;
    private StructuralIndexEventListener structuralListener;
    private CitationEventListener citationListener;
    private AuditEventListener auditListener;
    private final List< AuditEntry > audited = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        final PageManager pm = engine.getManager( PageManager.class );
        final FilterManager fm = engine.getManager( FilterManager.class );

        recorder = ( final WikiEvent e ) -> {
            if ( e instanceof WikiPageEvent pe && PAGE.equals( pe.getPageName() ) ) {
                seenTypes.add( pe.getType() );
            }
        };
        WikiEventManager.addWikiEventListener( pm, recorder );
        WikiEventManager.addWikiEventListener( fm, recorder );

        final DefaultStructuralIndexService service = mock( DefaultStructuralIndexService.class );
        doAnswer( inv -> { structuralCalls.incrementAndGet(); return null; } ).when( service ).onPageSaved( anyString() );
        structuralListener = new StructuralIndexEventListener( service );
        structuralListener.register( pm, fm );

        final CitationSync sync = mock( CitationSync.class );
        doAnswer( inv -> { citationCalls.incrementAndGet(); return null; } ).when( sync ).onPageSaved( anyString() );
        citationListener = new CitationEventListener( sync );
        citationListener.register( pm, fm );

        final AuditService audit = mock( AuditService.class );
        doAnswer( inv -> { audited.add( inv.getArgument( 0 ) ); return null; } ).when( audit ).record( org.mockito.ArgumentMatchers.any() );
        auditListener = new AuditEventListener( audit );
        // registered as WikiEngine.attachContentAuditListeners does
        WikiEventManager.addWikiEventListener( pm, auditListener );
        WikiEventManager.addWikiEventListener( fm, auditListener );
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( PAGE );
        engine.stop();
    }

    @Test
    void oneSaveDeliversPostSaveEndOnceAndRunsEachSyncOnce() throws Exception {
        engine.saveText( PAGE, "Hello world" );

        final long postSaveEnd = seenTypes.stream().filter( t -> t == WikiPageEvent.POST_SAVE_END ).count();
        assertEquals( 1, postSaveEnd, "events seen: " + seenTypes );
        assertEquals( 1, structuralCalls.get(), "structural index sync runs once per save; events seen: " + seenTypes );
        assertEquals( 1, citationCalls.get(), "citation sync runs once per save; events seen: " + seenTypes );
    }

    @Test
    void oneSaveProducesOnePageSaveAuditEntry() throws Exception {
        engine.saveText( PAGE, "Hello world" );

        final List< AuditEntry > saves = audited.stream()
            .filter( e -> "page.save".equals( e.eventType() ) && PAGE.equals( e.targetId() ) ).toList();
        assertEquals( 1, saves.size(), "page.save entries: " + audited );
    }
}

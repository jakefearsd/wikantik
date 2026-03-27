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
package com.wikantik.observability;

import com.wikantik.api.core.Engine;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith( MockitoExtension.class )
class WikiMetricsTest {

    @Mock private Engine engine;
    private SimpleMeterRegistry registry;
    private WikiMetrics wikiMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        wikiMetrics = new WikiMetrics( registry, engine );
    }

    @Test
    void incrementsPageViewsOnPageRequested() {
        wikiMetrics.actionPerformed( new WikiPageEvent( engine, WikiPageEvent.PAGE_REQUESTED, "Main" ) );
        wikiMetrics.actionPerformed( new WikiPageEvent( engine, WikiPageEvent.PAGE_REQUESTED, "TestPage" ) );

        final Counter counter = registry.find( "wikantik.page.views" ).counter();
        assertNotNull( counter );
        assertEquals( 2.0, counter.count() );
    }

    @Test
    void incrementsPageEditsOnPostSave() {
        wikiMetrics.actionPerformed( new WikiPageEvent( engine, WikiPageEvent.POST_SAVE, "Main" ) );

        final Counter counter = registry.find( "wikantik.page.edits" ).counter();
        assertNotNull( counter );
        assertEquals( 1.0, counter.count() );
    }

    @Test
    void incrementsPageDeletesOnPageDeleted() {
        wikiMetrics.actionPerformed( new WikiPageEvent( engine, WikiPageEvent.PAGE_DELETED, "OldPage" ) );

        final Counter counter = registry.find( "wikantik.page.deletes" ).counter();
        assertNotNull( counter );
        assertEquals( 1.0, counter.count() );
    }

    @Test
    void incrementsLoginSuccessOnAuthenticated() {
        wikiMetrics.actionPerformed(
                new WikiSecurityEvent( engine, WikiSecurityEvent.LOGIN_AUTHENTICATED, null, null ) );

        final Counter counter = registry.find( "wikantik.auth.logins" ).tag( "result", "success" ).counter();
        assertNotNull( counter );
        assertEquals( 1.0, counter.count() );
    }

    @Test
    void incrementsLoginFailureOnLoginFailed() {
        wikiMetrics.actionPerformed(
                new WikiSecurityEvent( engine, WikiSecurityEvent.LOGIN_FAILED, null, null ) );

        final Counter counter = registry.find( "wikantik.auth.logins" ).tag( "result", "failure" ).counter();
        assertNotNull( counter );
        assertEquals( 1.0, counter.count() );
    }

    @Test
    void ignoresUnrelatedPageEvents() {
        wikiMetrics.actionPerformed( new WikiPageEvent( engine, WikiPageEvent.PAGE_LOCK, "Main" ) );

        assertEquals( 0.0, registry.find( "wikantik.page.views" ).counter().count() );
        assertEquals( 0.0, registry.find( "wikantik.page.edits" ).counter().count() );
        assertEquals( 0.0, registry.find( "wikantik.page.deletes" ).counter().count() );
    }

}

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
package com.wikantik.search;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link LuceneSearchProvider#getReindexQueueDepth()}.
 * Uses the package-private constructor to avoid standing up a full TestEngine —
 * we only need to observe the {@link LuceneSearchProvider#updates} queue state.
 */
class LuceneSearchProviderQueueTest {

    @Test
    void testQueueDepthStartsAtZero() {
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                Mockito.mock( PageManager.class ),
                Mockito.mock( AttachmentManager.class ),
                null, null );
        Assertions.assertEquals( 0, provider.getReindexQueueDepth() );
    }

    @Test
    void testReindexPageIncrementsQueueDepth() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                pm, Mockito.mock( AttachmentManager.class ), null, null );

        final Page p1 = Mockito.mock( Page.class );
        Mockito.when( p1.getName() ).thenReturn( "Page1" );
        Mockito.when( pm.getPureText( p1 ) ).thenReturn( "body text 1" );

        provider.reindexPage( p1 );

        Assertions.assertEquals( 1, provider.getReindexQueueDepth(),
                "Queue depth should reflect one pending reindex after a single reindexPage call" );
    }

    @Test
    void testMultipleReindexPageCallsAccumulate() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                pm, Mockito.mock( AttachmentManager.class ), null, null );

        for ( int i = 0; i < 5; i++ ) {
            final Page p = Mockito.mock( Page.class );
            final String name = "Page" + i;
            Mockito.when( p.getName() ).thenReturn( name );
            Mockito.when( pm.getPureText( p ) ).thenReturn( "body " + i );
            provider.reindexPage( p );
        }

        Assertions.assertEquals( 5, provider.getReindexQueueDepth(),
                "Queue depth should equal number of pending reindexes when no updater is draining" );
    }

    @Test
    void testReindexPageWithNullTextDoesNotEnqueue() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                pm, Mockito.mock( AttachmentManager.class ), null, null );

        final Page p = Mockito.mock( Page.class );
        Mockito.when( p.getName() ).thenReturn( "GhostPage" );
        Mockito.when( pm.getPureText( p ) ).thenReturn( null );

        provider.reindexPage( p );

        Assertions.assertEquals( 0, provider.getReindexQueueDepth(),
                "Pages with null text must not be queued — matches existing skip logic" );
    }
}

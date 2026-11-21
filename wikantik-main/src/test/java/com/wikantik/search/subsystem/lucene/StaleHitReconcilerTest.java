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
package com.wikantik.search.subsystem.lucene;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.search.subsystem.lucene.StaleHitReconciler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The stale-hit policy: delete from the index only once absence is confirmed. */
class StaleHitReconcilerTest {

    private PageManager pm;
    private LuceneIndexer indexer;
    private StaleHitReconciler reconciler;

    @BeforeEach
    void setUp() {
        pm = mock( PageManager.class );
        indexer = mock( LuceneIndexer.class );
        reconciler = new StaleHitReconciler( pm, indexer, mock( Engine.class ) );
    }

    @Test
    void confirmedAbsentPageIsRemovedFromTheIndex() throws Exception {
        when( pm.wikiPageExists( "Gone", PageProvider.LATEST_VERSION ) ).thenReturn( false );

        assertEquals( Outcome.DELETED_FROM_INDEX, reconciler.reconcile( "Gone" ) );

        final ArgumentCaptor< Page > removed = ArgumentCaptor.forClass( Page.class );
        verify( indexer ).pageRemoved( removed.capture() );
        assertEquals( "Gone", removed.getValue().getName() );
    }

    @Test
    void pageThatExistsAgainIsKept() throws Exception {
        when( pm.wikiPageExists( "Back", PageProvider.LATEST_VERSION ) ).thenReturn( true );

        assertEquals( Outcome.KEPT, reconciler.reconcile( "Back" ) );
        verify( indexer, never() ).pageRemoved( any() );
    }

    @Test
    void unconfirmedAbsenceIsKept() throws Exception {
        when( pm.wikiPageExists( "Flaky", PageProvider.LATEST_VERSION ) )
            .thenThrow( new ProviderException( "fs race" ) );

        assertEquals( Outcome.KEPT, reconciler.reconcile( "Flaky" ) );
        verify( indexer, never() ).pageRemoved( any() );
    }
}

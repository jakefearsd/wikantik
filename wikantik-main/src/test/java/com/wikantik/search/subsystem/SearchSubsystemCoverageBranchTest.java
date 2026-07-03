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
package com.wikantik.search.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.subsystem.lucene.DefaultLuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.LuceneIndexer;
import com.wikantik.search.subsystem.lucene.LuceneSearcher;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for {@link SearchSubsystemBridge},
 * {@link SearchSubsystemFactory}, and {@link DefaultLuceneIndexLifecycle} that
 * target paths missed by the existing checkpoint tests.
 *
 * <p>Every test asserts concrete return values or collaborator state — no test
 * asserts nothing or only not-null.</p>
 */
final class SearchSubsystemCoverageBranchTest {

    // -----------------------------------------------------------------------
    // SearchSubsystemBridge.fromLegacyEngine — non-WikiEngine input
    // -----------------------------------------------------------------------

    /**
     * When the caller passes a non-{@link WikiEngine} {@link Engine} implementation
     * (e.g. a test stub that only implements the interface), {@code fromLegacyEngine}
     * must return a fully-null {@link SearchSubsystem.Services} record immediately,
     * without attempting any {@code getManager} lookups.
     */
    @Test
    void fromLegacyEngine_withNonWikiEngine_returnsNullRecord() {
        final Engine nonWikiEngine = mock( Engine.class );
        final SearchSubsystem.Services services = SearchSubsystemBridge.fromLegacyEngine( nonWikiEngine );
        assertNotNull( services, "record itself must not be null" );
        assertNull( services.searchManager(), "searchManager" );
        assertNull( services.hybridSearch(), "hybridSearch" );
        assertNull( services.luceneIndexer(), "luceneIndexer" );
        assertNull( services.embeddingIndexService(), "embeddingIndexService" );
    }

    /**
     * When {@link WikiEngine#getSearchSubsystem()} already returns a non-null
     * snapshot (post-initialize state), {@code fromLegacyEngine} must return
     * that exact snapshot without re-delegating to the factory.
     */
    @Test
    void fromLegacyEngine_withCachedSnapshot_returnsCachedSnapshot() {
        final WikiEngine wikiEngine = mock( WikiEngine.class );

        // Pre-built snapshot — as if WikiEngine.initialize() already ran.
        final SearchSubsystem.Services preBuilt = new SearchSubsystem.Services(
            null, null, null, null, null,
            null, null, null, null, null,
            null, null, null, null, null,
            null, null );
        when( wikiEngine.getSearchSubsystem() ).thenReturn( preBuilt );

        final SearchSubsystem.Services result = SearchSubsystemBridge.fromLegacyEngine( wikiEngine );

        assertSame( preBuilt, result,
            "fromLegacyEngine must return the pre-built snapshot, not re-build it" );
    }

    // -----------------------------------------------------------------------
    // SearchSubsystemFactory — pgvector with null DataSource degrades
    // -----------------------------------------------------------------------

    /**
     * {@code buildSearchSubsystem()} runs on every boot, including the documented
     * no-datasource mode. This used to throw {@link IllegalStateException} and
     * crash engine startup under a non-inmemory backend (fixed alongside the
     * lucene-hnsw default flip in d027a546da) — it must now degrade to a null
     * {@code chunkVectorIndex} slot, matching {@code SearchWiringHelper}'s own
     * catch-and-warn behaviour, and let the engine boot anyway.
     */
    @Test
    void pgvectorBackend_withNullDataSource_degradesWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "pgvector" );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null, /*page=*/ null, /*knowledge=*/ null,
                engine ) );

        assertNull( services.chunkVectorIndex(),
            "no DataSource and nothing wired -> dense retrieval degrades to null, not a crash" );
    }

    // -----------------------------------------------------------------------
    // SearchSubsystemFactory — lucene-hnsw with null DataSource degrades
    // -----------------------------------------------------------------------

    @Test
    void luceneHnswBackend_withNullDataSource_degradesWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );
        props.setProperty( EmbeddingConfig.PROP_MODEL, "qwen3-embedding-0.6b" );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null, /*page=*/ null, /*knowledge=*/ null,
                engine ) );

        assertNull( services.chunkVectorIndex(),
            "no DataSource and nothing wired -> dense retrieval degrades to null, not a crash" );
    }

    // -----------------------------------------------------------------------
    // SearchSubsystemFactory — safeGetSearchEngine absorbs exception
    // -----------------------------------------------------------------------

    /**
     * {@link SearchSubsystemFactory#safeGetSearchEngine} must absorb a
     * {@link RuntimeException} thrown by {@link SearchManager#getSearchEngine()}
     * (e.g. a partial mock with no stub), leaving {@code searchProvider} null
     * instead of propagating to the caller.
     */
    @Test
    void safeGetSearchEngine_whenThrows_leavesProviderNull() {
        final SearchManager manager = mock( SearchManager.class );
        when( manager.getSearchEngine() ).thenThrow( new RuntimeException( "not wired" ) );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( SearchManager.class ) ).thenReturn( manager );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null, /*page=*/ null, /*knowledge=*/ null,
                engine ) );

        assertNotNull( services );
        assertSame( manager, services.searchManager(),
            "searchManager must still be wired even when getSearchEngine() throws" );
        assertNull( services.searchProvider(),
            "searchProvider must be null when getSearchEngine() throws" );
    }

    // -----------------------------------------------------------------------
    // SearchSubsystemFactory — LuceneSearchProvider with partial-null accessors
    // -----------------------------------------------------------------------

    /**
     * When a {@link LuceneSearchProvider} returns {@code null} for one of its
     * decomposed helper accessors (defensive guard), the factory should log a
     * warn and still populate the non-null slots.
     */
    @Test
    void luceneProvider_withNullSearcher_populatesPartialSlots() {
        final LuceneIndexer indexer = mock( LuceneIndexer.class );
        // searcher is null — simulates a provider that hasn't fully initialised
        final com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle lifecycle =
            mock( com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle.class );

        final LuceneSearchProvider provider = mock( LuceneSearchProvider.class );
        when( provider.getIndexer() ).thenReturn( indexer );
        when( provider.getSearcher() ).thenReturn( null ); // partial null
        when( provider.getIndexLifecycle() ).thenReturn( lifecycle );

        final SearchManager manager = mock( SearchManager.class );
        when( manager.getSearchEngine() ).thenReturn( provider );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( SearchManager.class ) ).thenReturn( manager );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null, /*page=*/ null, /*knowledge=*/ null,
                engine ) );

        assertSame( indexer, services.luceneIndexer(),
            "non-null accessor must still propagate" );
        assertNull( services.luceneSearcher(),
            "null accessor must produce null slot" );
        assertSame( lifecycle, services.luceneIndexLifecycle(),
            "non-null accessor must still propagate" );
    }

    // -----------------------------------------------------------------------
    // DefaultLuceneIndexLifecycle — metric recording and epoch default
    // -----------------------------------------------------------------------

    @Test
    void defaultLifecycle_initialSearchCount_isZero() {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        assertEquals( 0L, lifecycle.getTotalSearchCount() );
        assertEquals( 0L, lifecycle.getZeroResultSearchCount() );
        assertEquals( 0L, lifecycle.getLastQueryElapsedMillis() );
    }

    @Test
    void defaultLifecycle_lastUpdateInstant_startsAtEpoch() {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        assertEquals( Instant.EPOCH, lifecycle.lastUpdateInstant() );
    }

    @Test
    void defaultLifecycle_touchLastUpdateInstant_advancesTimestamp() throws InterruptedException {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        final Instant before = Instant.now();
        // Small sleep so the instant is strictly after "before" even on fast clocks.
        Thread.sleep( 5 );
        lifecycle.touchLastUpdateInstant();
        assertTrue( lifecycle.lastUpdateInstant().isAfter( before ),
            "touchLastUpdateInstant must advance the timestamp beyond the pre-call instant" );
    }

    @Test
    void defaultLifecycle_recordSearchMetrics_nonZeroResult_countsOnlyTotal() {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        lifecycle.recordSearchMetrics( 42L, false );

        assertEquals( 1L, lifecycle.getTotalSearchCount() );
        assertEquals( 0L, lifecycle.getZeroResultSearchCount(),
            "zeroResultCount must NOT increment when zeroResults=false" );
        assertEquals( 42L, lifecycle.getLastQueryElapsedMillis() );
    }

    @Test
    void defaultLifecycle_recordSearchMetrics_zeroResult_incrementsBothCounters() {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        lifecycle.recordSearchMetrics( 10L, true );

        assertEquals( 1L, lifecycle.getTotalSearchCount() );
        assertEquals( 1L, lifecycle.getZeroResultSearchCount(),
            "zeroResultCount must increment when zeroResults=true" );
        assertEquals( 10L, lifecycle.getLastQueryElapsedMillis() );
    }

    @Test
    void defaultLifecycle_recordSearchMetrics_multipleCalls_accumulatesTotal() {
        final DefaultLuceneIndexLifecycle lifecycle =
            new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
        lifecycle.recordSearchMetrics( 5L, false );
        lifecycle.recordSearchMetrics( 8L, true );
        lifecycle.recordSearchMetrics( 3L, false );

        assertEquals( 3L, lifecycle.getTotalSearchCount() );
        assertEquals( 1L, lifecycle.getZeroResultSearchCount() );
        assertEquals( 3L, lifecycle.getLastQueryElapsedMillis(),
            "lastQueryElapsedMillis must reflect the most recent call" );
    }

    @Test
    void defaultLifecycle_getAnalyzer_returnsSameInstance() {
        final StandardAnalyzer analyzer = new StandardAnalyzer();
        final DefaultLuceneIndexLifecycle lifecycle = new DefaultLuceneIndexLifecycle( analyzer );
        assertSame( analyzer, lifecycle.getAnalyzer() );
    }
}

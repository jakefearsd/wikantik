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
package com.wikantik.knowledge;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.knowledge.testfakes.FakeDeps;
import com.wikantik.knowledge.testfakes.FakeEngine;
import com.wikantik.knowledge.testfakes.FakeHybridSearch;
import com.wikantik.knowledge.testfakes.FakePageManager;
import com.wikantik.knowledge.testfakes.FakeSearchManager;
import com.wikantik.knowledge.testfakes.FakeSearchResult;
import com.wikantik.search.SearchManager;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Construction, degrade paths, filtering and hybrid-fusion handling of {@link DefaultContextRetrievalService}. */
class DefaultContextRetrievalServiceBehaviourTest {

    private static ContextQuery query( final String q, final int maxPages, final PageListFilter filter ) {
        return new ContextQuery( q, maxPages, 3, filter );
    }

    private static FakePageManager pagesWithCluster( final String... nameAndCluster ) {
        final FakePageManager pm = new FakePageManager();
        for ( int i = 0; i < nameAndCluster.length; i += 2 ) {
            pm.addPage( nameAndCluster[ i ], "---\ncluster: " + nameAndCluster[ i + 1 ] + "\n---\n\nbody", "a", new Date() );
        }
        return pm;
    }

    // ---- construction ----

    @Test
    void constructionRequiresEngineSearchManagerAndPageManager() {
        final Engine engine = FakeEngine.create();
        final SearchManager sm = new FakeSearchManager();
        final PageManager pm = new FakePageManager();
        final var noEngine = assertThrows( IllegalArgumentException.class,
                () -> new DefaultContextRetrievalService( null, sm, null, null, null, null, pm, null, "", null ) );
        assertTrue( noEngine.getMessage().contains( "engine" ), noEngine.getMessage() );
        final var noSearch = assertThrows( IllegalArgumentException.class,
                () -> new DefaultContextRetrievalService( engine, null, null, null, null, null, pm, null, "", null ) );
        assertTrue( noSearch.getMessage().contains( "searchManager" ), noSearch.getMessage() );
        final var noPages = assertThrows( IllegalArgumentException.class,
                () -> new DefaultContextRetrievalService( engine, sm, null, null, null, null, null, null, "", null ) );
        assertTrue( noPages.getMessage().contains( "pageManager" ), noPages.getMessage() );
    }

    @Test
    void aNullPublicBaseUrlLinksSiteRelative() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "body", "alice", new Date() );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), new FakeSearchManager(), null, null, null,
                null, pm, null, null, null );
        assertEquals( "/wiki/Hub", svc.getPage( "Hub" ).url() );
    }

    @Test
    void aConfiguredPublicBaseUrlPrefixesThePageLink() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "body", "alice", new Date() );
        final var svc = FakeDeps.minimal().pageManager( pm ).baseUrl( "https://wiki.example.com" ).build();
        assertEquals( "https://wiki.example.com/wiki/Hub", svc.getPage( "Hub" ).url() );
    }

    @Test
    void aNullPublicIdentityLeavesTheStoredAuthorAsIs() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "body", "alice", new Date() );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), new FakeSearchManager(), null, null, null,
                null, pm, null, "", null, null );
        assertEquals( "alice", svc.getPage( "Hub" ).author() );
    }

    @Test
    void theSuppliedPublicIdentityResolvesTheAuthorAtCallTime() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "body", "alice", new Date() );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "alice" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "alice" ) ).thenReturn( "Alice Example" );
        final var svc = FakeDeps.minimal().pageManager( pm ).publicIdentity( () -> identity ).build();
        assertEquals( "Alice Example", svc.getPage( "Hub" ).author() );
    }

    // ---- retrieve ----

    @Test
    void retrieveRefusesANullQuery() {
        final var svc = FakeDeps.minimal().build();
        final var e = assertThrows( IllegalArgumentException.class, () -> svc.retrieve( null ) );
        assertTrue( e.getMessage().contains( "query" ), e.getMessage() );
    }

    @Test
    void retrieveAppliesTheStructuralFilterButStillReportsEveryLexicalMatch() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x", "Beta", "y", "Gamma", "x" );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( FakeSearchResult.of( "Alpha", 3 ), FakeSearchResult.of( "Beta", 2 ),
                FakeSearchResult.of( "Gamma", 1 ) ) );
        final var svc = FakeDeps.minimal().search( sm ).pageManager( pm ).build();

        final RetrievalResult result = svc.retrieve( query( "q", 10,
                new PageListFilter( "x", null, null, null, null, null, 50, 0 ) ) );

        assertEquals( List.of( "Alpha", "Gamma" ), result.pages().stream().map( p -> p.name() ).toList() );
        assertEquals( 3, result.totalMatched(), "totalMatched counts lexical matches before the filter and the cap" );
    }

    @Test
    void retrieveSkipsASearchResultThatHasNoPage() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x" );
        final SearchResult orphan = mock( SearchResult.class );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( orphan, FakeSearchResult.of( "Alpha", 3 ) ) );
        final var svc = FakeDeps.minimal().search( sm ).pageManager( pm ).build();

        final RetrievalResult result = svc.retrieve( query( "q", 10, null ) );

        assertEquals( List.of( "Alpha" ), result.pages().stream().map( p -> p.name() ).toList() );
    }

    @Test
    void aSearchFailureYieldsAnEmptyResultThatStillEchoesTheQuery() throws Exception {
        final SearchManager sm = mock( SearchManager.class );
        when( sm.findPages( anyString(), any() ) ).thenThrow( new IllegalStateException( "index closed" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, null, null, null, null,
                new FakePageManager(), null, "", null );

        final RetrievalResult result = svc.retrieve( query( "needle", 5, null ) );

        assertEquals( "needle", result.query() );
        assertTrue( result.pages().isEmpty() );
        assertEquals( 0, result.totalMatched() );
    }

    @Test
    void aMalformedQueryFailureRootedInALuceneParseExceptionAlsoYieldsAnEmptyResult() throws Exception {
        final SearchManager sm = mock( SearchManager.class );
        when( sm.findPages( anyString(), any() ) ).thenThrow( new RuntimeException( "wrapped",
                new org.apache.lucene.queryparser.classic.ParseException( "Cannot parse 'a AND'" ) ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, null, null, null, null,
                new FakePageManager(), null, "", null );

        final RetrievalResult result = svc.retrieve( query( "a AND", 5, null ) );

        assertEquals( "a AND", result.query() );
        assertTrue( result.pages().isEmpty() );
    }

    @Test
    void withNoMainPageTheLexicalSearchRunsWithoutAContext() {
        final FakeSearchManager sm = new FakeSearchManager();
        FakeDeps.minimal().search( sm ).build().retrieve( query( "q", 5, null ) );
        assertNull( sm.lastContext() );
    }

    @Test
    void aNullLexicalResultCollectionIsTreatedAsNoMatches() throws Exception {
        final SearchManager sm = mock( SearchManager.class );
        when( sm.findPages( anyString(), any() ) ).thenReturn( null );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, null, null, null, null,
                new FakePageManager(), null, "", null );
        final RetrievalResult result = svc.retrieve( query( "q", 5, null ) );
        assertTrue( result.pages().isEmpty() );
        assertEquals( 0, result.totalMatched() );
    }

    @Test
    void ontologyExpansionAppendsEveryTermToTheQuery() {
        final FakeSearchManager sm = new FakeSearchManager();
        FakeDeps.minimal().search( sm ).ontologyQuery( q -> List.of( "QuickSort", "MergeSort" ) ).build()
                .retrieve( query( "algorithm", 5, null ) );
        assertEquals( "algorithm QuickSort MergeSort", sm.lastQuery() );
    }

    @Test
    void anOntologyServiceWithNothingToAddLeavesTheQueryAlone() {
        final FakeSearchManager sm = new FakeSearchManager();
        FakeDeps.minimal().search( sm ).ontologyQuery( q -> List.of() ).build().retrieve( query( "algorithm", 5, null ) );
        assertEquals( "algorithm", sm.lastQuery() );
    }

    // ---- hybrid fusion ----

    @Test
    void aDenseOnlyPageTheFusionAddsIsLoadedFromThePageStoreAtScoreZero() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x", "Gamma", "y" );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( FakeSearchResult.of( "Alpha", 7 ) ) );
        final var hybrid = FakeHybridSearch.enabledReturning( List.of( "Gamma", "Alpha" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, hybrid, null, null, null, pm, null, "", null );

        final RetrievalResult result = svc.retrieve( query( "q", 10, null ) );

        assertEquals( List.of( "Gamma", "Alpha" ), result.pages().stream().map( p -> p.name() ).toList() );
        assertEquals( 0.0, result.pages().get( 0 ).score(), 0.0 );
        assertEquals( "y", result.pages().get( 0 ).cluster() );
        assertEquals( 7.0, result.pages().get( 1 ).score(), 0.0, "a lexical hit keeps its lexical score" );
        assertEquals( 2, result.totalMatched() );
    }

    @Test
    void aFusedPageThatNoLongerExistsIsDropped() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x" );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( FakeSearchResult.of( "Alpha", 7 ) ) );
        final var hybrid = FakeHybridSearch.enabledReturning( List.of( "Ghost", "Alpha" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, hybrid, null, null, null, pm, null, "", null );

        final RetrievalResult result = svc.retrieve( query( "q", 10, null ) );

        assertEquals( List.of( "Alpha" ), result.pages().stream().map( p -> p.name() ).toList() );
        assertEquals( 1, result.totalMatched() );
    }

    @Test
    void anUnchangedFusionOrderKeepsTheLexicalResultsAsTheyAre() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x", "Beta", "x" );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( FakeSearchResult.of( "Alpha", 7 ), FakeSearchResult.of( "Beta", 5 ) ) );
        final var hybrid = FakeHybridSearch.enabledReturning( List.of( "Alpha", "Beta" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, hybrid, null, null, null, pm, null, "", null );

        final RetrievalResult result = svc.retrieve( query( "q", 10, null ) );

        assertEquals( List.of( "Alpha", "Beta" ), result.pages().stream().map( p -> p.name() ).toList() );
        assertEquals( 7.0, result.pages().get( 0 ).score(), 0.0 );
    }

    @Test
    void aResultWithoutAPageDoesNotReachTheFusionStep() {
        final FakePageManager pm = pagesWithCluster( "Alpha", "x" );
        final SearchResult orphan = mock( SearchResult.class );
        final FakeSearchManager sm = new FakeSearchManager();
        sm.setResults( List.of( orphan, FakeSearchResult.of( "Alpha", 7 ) ) );
        final var hybrid = FakeHybridSearch.enabledReturning( List.of( "Alpha" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), sm, hybrid, null, null, null, pm, null, "", null );

        final RetrievalResult result = svc.retrieve( query( "q", 10, null ) );

        assertEquals( List.of( "Alpha" ), result.pages().stream().map( p -> p.name() ).toList() );
    }

    // ---- getPage / listMetadataValues ----

    @Test
    void getPageOfANullOrBlankNameIsNullWithoutTouchingTheStore() {
        final PageManager pm = mock( PageManager.class );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), new FakeSearchManager(), null, null, null,
                null, pm, null, "", null );
        assertNull( svc.getPage( null ) );
        assertNull( svc.getPage( "" ) );
        assertNull( svc.getPage( "   " ) );
        org.mockito.Mockito.verifyNoInteractions( pm );
    }

    @Test
    void listMetadataValuesOfANullOrBlankFieldIsEmptyWithoutScanningPages() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), new FakeSearchManager(), null, null, null,
                null, pm, null, "", null );
        assertEquals( List.of(), svc.listMetadataValues( null, s -> true ) );
        assertEquals( List.of(), svc.listMetadataValues( " ", s -> true ) );
        org.mockito.Mockito.verify( pm, org.mockito.Mockito.never() ).getAllPages();
    }

    @Test
    void listMetadataValuesDegradesToEmptyWhenThePageStoreFails() throws Exception {
        final PageManager pm = mock( PageManager.class );
        when( pm.getAllPages() ).thenThrow( new ProviderException( "disk gone" ) );
        final var svc = new DefaultContextRetrievalService( FakeEngine.create(), new FakeSearchManager(), null, null, null,
                null, pm, null, "", null );
        assertEquals( List.of(), svc.listMetadataValues( "cluster", s -> true ) );
    }

    @Test
    void listMetadataValuesSortsByDescendingCount() {
        final FakePageManager pm = pagesWithCluster( "A", "rare", "B", "common", "C", "common", "D", "common", "E", "mid", "F", "mid" );
        final var svc = FakeDeps.minimal().pageManager( pm ).build();
        final List< MetadataValue > values = svc.listMetadataValues( "cluster", s -> true );
        assertEquals( List.of( new MetadataValue( "common", 3 ), new MetadataValue( "mid", 2 ), new MetadataValue( "rare", 1 ) ), values );
    }

    // ---- inline tag memoisation ----

    @Test
    void theStructuralIndexIsResolvedOnceAndReusedAcrossLookups() {
        final com.wikantik.api.pagegraph.StructuralIndexService index = mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        when( index.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        final AtomicInteger resolutions = new AtomicInteger();
        final Function< String, Optional< List< String > > > lookup =
                DefaultContextRetrievalService.indexedInlineTags( () -> { resolutions.incrementAndGet(); return index; } );

        lookup.apply( "A" );
        lookup.apply( "B" );
        lookup.apply( "C" );

        assertEquals( 1, resolutions.get(), "a non-null index is memoised, so the supplier is not asked per page" );
    }

    @SuppressWarnings( "unused" )
    private static Collection< SearchResult > unused() {
        return List.of();
    }
}

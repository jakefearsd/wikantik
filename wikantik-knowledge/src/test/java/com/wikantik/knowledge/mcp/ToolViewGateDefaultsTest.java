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
package com.wikantik.knowledge.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Every read tool takes a nullable {@link PageViewGate}; null must mean "allow all" (not "deny all", not a
 * NullPointerException). Also pins the pagination semantics of {@code totalMatched}.
 */
class ToolViewGateDefaultsTest {

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    private static JsonObject json( final McpSchema.CallToolResult result ) {
        assertFalse( Boolean.TRUE.equals( result.isError() ), () -> text( result ) );
        return JsonParser.parseString( text( result ) ).getAsJsonObject();
    }

    private static RetrievedPage retrieved( final String name ) {
        return new RetrievedPage( name, "https://w/" + name, 0.5, name + " summary", "c", List.of(),
                List.of(), List.of(), "alice", new Date(), false );
    }

    private static PageDescriptor descriptor( final String slug ) {
        return new PageDescriptor( "id-" + slug, slug, slug, PageType.ARTICLE, null, List.of(), "s", Instant.EPOCH, false );
    }

    @Test
    void getPageWithANullGateReturnsThePage() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Alpha" ) ).thenReturn( retrieved( "Alpha" ) );
        assertEquals( "Alpha", json( new GetPageTool( svc, null ).execute( Map.of( "slug", "Alpha" ) ) ).get( "name" ).getAsString() );
    }

    @Test
    void getPageWithoutASlugSaysWhichArgumentIsNeeded() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        for ( final Map< String, Object > args : List.< Map< String, Object > >of( Map.of(), Map.of( "slug", "" ), Map.of( "slug", "   " ) ) ) {
            final var result = new GetPageTool( svc ).execute( args );
            assertTrue( result.isError() );
            assertTrue( text( result ).contains( "a page identifier is required" ), text( result ) );
        }
        verifyNoInteractions( svc );
    }

    @Test
    void listPagesWithANullGateReturnsEveryPage() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ), any() ) ).thenReturn(
                new PageList( List.of( retrieved( "A" ), retrieved( "B" ) ), 2, 50, 0 ) );
        final JsonObject out = json( new ListPagesTool( svc, null ).execute( Map.of() ) );
        assertEquals( 2, out.getAsJsonArray( "pages" ).size() );
    }

    @Test
    void listPagesKeepsTheFullMatchCountWhenOnlyThePageIsShort() {
        // 2 shown out of 10 matched, none hidden: totalMatched stays 10 (the pagination figure), not 2.
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ), any() ) ).thenReturn(
                new PageList( List.of( retrieved( "A" ), retrieved( "B" ) ), 10, 2, 0 ) );
        final JsonObject out = json( new ListPagesTool( svc ).execute( Map.of( "limit", 2 ) ) );
        assertEquals( 10, out.get( "totalMatched" ).getAsInt() );
    }

    @Test
    void listPagesDiscountsOnlyThePagesTheBackstopGateHid() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ), any() ) ).thenReturn(
                new PageList( List.of( retrieved( "A" ), retrieved( "Hidden" ) ), 10, 2, 0 ) );
        final JsonObject out = json( new ListPagesTool( svc, slug -> !"Hidden".equals( slug ) ).execute( Map.of() ) );
        assertEquals( 1, out.getAsJsonArray( "pages" ).size() );
        assertEquals( 9, out.get( "totalMatched" ).getAsInt() );
    }

    @Test
    void listPagesPassesDefaultsAndExplicitPaging() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ), any() ) ).thenReturn( new PageList( List.of(), 0, 50, 0 ) );
        new ListPagesTool( svc ).execute( Map.of() );
        new ListPagesTool( svc ).execute( Map.of( "limit", 7, "offset", 3, "cluster", "c", "type", "t", "author", "a",
                "modifiedAfter", "2026-01-01T00:00:00Z", "modifiedBefore", "2026-02-01T00:00:00Z" ) );
        final var captor = org.mockito.ArgumentCaptor.forClass( PageListFilter.class );
        verify( svc, times( 2 ) ).listPages( captor.capture(), any() );
        assertEquals( 50, captor.getAllValues().get( 0 ).limit() );
        assertEquals( 0, captor.getAllValues().get( 0 ).offset() );
        final PageListFilter f = captor.getAllValues().get( 1 );
        assertEquals( 7, f.limit() );
        assertEquals( 3, f.offset() );
        assertEquals( "c", f.cluster() );
        assertEquals( "t", f.type() );
        assertEquals( "a", f.author() );
        assertEquals( Instant.parse( "2026-01-01T00:00:00Z" ), f.modifiedAfter() );
        assertEquals( Instant.parse( "2026-02-01T00:00:00Z" ), f.modifiedBefore() );
    }

    @Test
    void listPagesReportsAnInvalidDateAsAnErrorResultNamingTheValue() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final var result = new ListPagesTool( svc ).execute( Map.of( "modifiedAfter", "yesterday" ) );
        assertTrue( result.isError() );
        verifyNoInteractions( svc );
    }

    @Test
    void retrieveContextKeepsTheFullMatchCountWhenOnlyThePageIsShort() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn(
                new RetrievalResult( "q", List.of( retrieved( "A" ) ), 12 ) );
        assertEquals( 12, json( new RetrieveContextTool( svc ).execute( Map.of( "query", "q" ) ) ).get( "totalMatched" ).getAsInt() );
    }

    @Test
    void retrieveContextDiscountsOnlyHiddenPagesFromTheMatchCount() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn(
                new RetrievalResult( "q", List.of( retrieved( "A" ), retrieved( "Hidden" ), retrieved( "B" ) ), 12 ) );
        final JsonObject out = json( new RetrieveContextTool( svc, slug -> !"Hidden".equals( slug ) )
                .execute( Map.of( "query", "q" ) ) );
        assertEquals( 2, out.getAsJsonArray( "pages" ).size() );
        assertEquals( 11, out.get( "totalMatched" ).getAsInt() );
    }

    @Test
    void retrieveContextWithANullGateKeepsEveryRelatedPage() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final RetrievedPage page = new RetrievedPage( "A", "u", 0.5, "s", "c", List.of(), List.of(),
                List.of( new RelatedPage( "R1", "why" ), new RelatedPage( "R2", "why" ) ), "alice", new Date(), false );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn( new RetrievalResult( "q", List.of( page ), 1 ) );
        final JsonObject out = json( new RetrieveContextTool( svc, null ).execute( Map.of( "query", "q" ) ) );
        assertEquals( 2, out.getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject().getAsJsonArray( "relatedPages" ).size() );
    }

    @Test
    void retrieveContextDropsRestrictedRelatedPagesButKeepsTheViewableOnes() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final RetrievedPage page = new RetrievedPage( "A", "u", 0.5, "s", "c", List.of(), List.of(),
                List.of( new RelatedPage( "Open", "why" ), new RelatedPage( "Secret", "why" ) ), "alice", new Date(), false );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn( new RetrievalResult( "q", List.of( page ), 1 ) );
        final var related = json( new RetrieveContextTool( svc, slug -> !"Secret".equals( slug ) ).execute( Map.of( "query", "q" ) ) )
                .getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject().getAsJsonArray( "relatedPages" );
        assertEquals( 1, related.size() );
        assertEquals( "Open", related.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void retrieveContextUsesDefaultsOfFivePagesAndThreeChunks() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn( new RetrievalResult( "q", List.of(), 0 ) );
        new RetrieveContextTool( svc ).execute( Map.of( "query", "q" ) );
        final var captor = org.mockito.ArgumentCaptor.forClass( ContextQuery.class );
        verify( svc ).retrieve( captor.capture() );
        assertEquals( 5, captor.getValue().maxPages() );
        assertEquals( 3, captor.getValue().chunksPerPage() );
        assertEquals( new PageListFilter( null, null, null, null, null, null, 50, 0 ), captor.getValue().filter() );
    }

    @Test
    void retrieveContextTurnsABlankQueryIntoAnErrorResult() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final var result = new RetrieveContextTool( svc ).execute( Map.of( "query", "" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "query must not be blank" ), text( result ) );
        verifyNoInteractions( svc );
    }

    @Test
    void listMetadataValuesWithANullGateCountsEveryPage() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listMetadataValues( anyString(), any() ) ).thenAnswer( inv -> {
            final Predicate< String > viewable = inv.getArgument( 1 );
            return viewable.test( "AnySlug" ) ? List.of( new MetadataValue( "retrieval", 4 ) ) : List.of();
        } );
        final JsonObject out = json( new ListMetadataValuesTool( svc, null ).execute( Map.of( "field", "cluster" ) ) );
        assertEquals( 1, out.getAsJsonArray( "values" ).size() );
        assertEquals( "cluster", out.get( "field" ).getAsString() );
    }

    @Test
    void listMetadataValuesRequiresAFieldAndSaysSo() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        for ( final Map< String, Object > args : List.< Map< String, Object > >of( Map.of(), Map.of( "field", " " ) ) ) {
            final var result = new ListMetadataValuesTool( svc ).execute( args );
            assertTrue( result.isError() );
            assertTrue( text( result ).contains( "field must not be blank" ), text( result ) );
        }
        verifyNoInteractions( svc );
    }

    @Test
    void listMetadataValuesStillAcceptsTheDeprecatedKeyAlias() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listMetadataValues( eq( "type" ), any() ) ).thenReturn( List.of( new MetadataValue( "hub", 2 ) ) );
        final JsonObject out = json( new ListMetadataValuesTool( svc ).execute( Map.of( "key", "type" ) ) );
        assertEquals( "type", out.get( "field" ).getAsString() );
        assertEquals( "hub", out.getAsJsonArray( "values" ).get( 0 ).getAsJsonObject().get( "value" ).getAsString() );
    }

    @Test
    void listTagsWithANullGateCountsEveryPage() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( anyInt(), any() ) ).thenAnswer( inv -> {
            final Predicate< PageDescriptor > visible = inv.getArgument( 1 );
            return visible.test( descriptor( "Any" ) ) ? List.of( new TagSummary( "t", 1, List.of() ) ) : List.of();
        } );
        assertEquals( 1, json( new ListTagsTool( svc, null ).execute( Map.of() ) ).getAsJsonArray( "tags" ).size() );
    }

    @Test
    void listTagsClampsMinPagesToAtLeastOne() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( anyInt(), any() ) ).thenReturn( List.of() );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", 0 ) );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", -4 ) );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", "7" ) );
        verify( svc, times( 3 ) ).listTags( eq( 1 ), any() );
    }

    @Test
    void listPagesByFilterWithANullGateReturnsEveryPage() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of( descriptor( "A" ), descriptor( "B" ) ) );
        final JsonObject out = json( new ListPagesByFilterTool( svc, null ).execute( Map.of() ) );
        assertEquals( 2, out.get( "count" ).getAsInt() );
    }

    @Test
    void getPageByIdExplainsAMissingIdAndNamesTheUnknownOne() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "nope" ) ).thenReturn( Optional.empty() );
        final GetPageByIdTool tool = new GetPageByIdTool( svc );

        final var missingArg = tool.execute( Map.of() );
        assertTrue( text( missingArg ).contains( "id (or canonical_id) argument is required" ), text( missingArg ) );
        final var blank = tool.execute( Map.of( "id", "  " ) );
        assertTrue( text( blank ).contains( "id (or canonical_id) argument is required" ), text( blank ) );
        final var unknown = tool.execute( Map.of( "id", "nope" ) );
        assertTrue( unknown.isError() );
        assertTrue( text( unknown ).contains( "no page for canonical_id nope" ), text( unknown ) );
    }

    @Test
    void getPageByIdPrefersIdOverTheDeprecatedAlias() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "new" ) ).thenReturn( Optional.of( descriptor( "New" ) ) );
        when( svc.getByCanonicalId( "old" ) ).thenReturn( Optional.of( descriptor( "Old" ) ) );
        final JsonObject out = json( new GetPageByIdTool( svc ).execute( Map.of( "id", "new", "canonical_id", "old" ) ) );
        assertEquals( "New", out.get( "slug" ).getAsString() );
    }

    private static BundleSection section( final String slug ) {
        return new BundleSection( "id-" + slug, slug, List.of( "H" ), "text of " + slug, 0.9,
                new CitationHandle( "id-" + slug, 1, List.of( "H" ), "text of " + slug, "h" ) );
    }

    @Test
    void assembleBundleWithANullGateKeepsEverySection() {
        final BundleAssemblyService svc = q -> new ContextBundle( q, List.of( section( "A" ), section( "B" ) ) );
        final JsonObject out = json( new AssembleBundleTool( svc, () -> null, null ).execute( Map.of( "query", "q" ) ) );
        assertEquals( 2, out.getAsJsonArray( "sections" ).size() );
    }

    @Test
    void assembleBundleWorksWithoutAQueryLogSupplier() {
        final BundleAssemblyService svc = q -> new ContextBundle( q, List.of( section( "A" ) ) );
        final JsonObject out = json( new AssembleBundleTool( svc, null ).execute( Map.of( "query", "q" ) ) );
        assertEquals( 1, out.getAsJsonArray( "sections" ).size() );
    }

    @Test
    void assembleBundleRefusesABlankQueryWithAMessage() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        for ( final Map< String, Object > args : List.< Map< String, Object > >of( Map.of(), Map.of( "query", "  " ) ) ) {
            final var result = new AssembleBundleTool( svc, () -> null ).execute( args );
            assertTrue( result.isError() );
            assertTrue( text( result ).contains( "query argument is required" ), text( result ) );
        }
        verifyNoInteractions( svc );
    }

    @Test
    void assembleBundleRefusesAnUnknownModeWithoutAssembling() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final var result = new AssembleBundleTool( svc, () -> null ).execute( Map.of( "query", "q", "mode", "telepathy" ) );
        assertTrue( result.isError() );
        verifyNoInteractions( svc );
    }

    @Test
    void assembleBundleTurnsAServiceFailureIntoAnErrorResultCarryingTheMessage() {
        final BundleAssemblyService svc = q -> { throw new IllegalStateException( "index offline" ); };
        final var result = new AssembleBundleTool( svc, () -> null ).execute( Map.of( "query", "q" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "index offline" ), text( result ) );
    }

    @Test
    void assembleBundleLogsTheSectionCountOfWhatTheCallerMaySee() {
        final com.wikantik.api.querylog.QueryLogService qlog = mock( com.wikantik.api.querylog.QueryLogService.class );
        final BundleAssemblyService svc = q -> new ContextBundle( q, List.of( section( "A" ), section( "Hidden" ) ),
                new BundleCoverage( 2, 2, 0.9, BundleCoverage.STRONG ) );
        new AssembleBundleTool( svc, () -> qlog, slug -> !"Hidden".equals( slug ) ).execute( Map.of( "query", "q" ) );
        verify( qlog ).log( eq( "q" ), eq( com.wikantik.api.querylog.ActorType.AGENT ),
                eq( com.wikantik.api.querylog.SourceSurface.MCP_ASSEMBLE_BUNDLE ), eq( 1 ), any(), isNull() );
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}

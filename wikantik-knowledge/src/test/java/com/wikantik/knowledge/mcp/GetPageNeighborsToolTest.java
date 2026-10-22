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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageNeighborsToolTest {

    private StructuralIndexService index;
    private ReferenceManager refs;

    @BeforeEach
    void setUp() {
        index = mock( StructuralIndexService.class );
        refs = mock( ReferenceManager.class );
        when( index.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        when( index.getCluster( anyString() ) ).thenReturn( Optional.empty() );
    }

    private PageDescriptor page( final String slug, final PageType type, final String... clusters ) {
        final PageDescriptor d = new PageDescriptor( "id-" + slug, slug, slug + " title", type,
            clusters.length == 0 ? null : clusters[ 0 ], List.of( clusters ), List.of(), slug + " summary",
            Instant.EPOCH, Optional.empty(), false );
        when( index.resolveCanonicalIdFromSlug( slug ) ).thenReturn( Optional.of( d.canonicalId() ) );
        when( index.getByCanonicalId( d.canonicalId() ) ).thenReturn( Optional.of( d ) );
        return d;
    }

    /** As the structural projection builds it: {@code articles} lists every member, the hub included. */
    private void cluster( final String name, final PageDescriptor hub, final PageDescriptor... articles ) {
        final List< PageDescriptor > members = new ArrayList<>( List.of( hub ) );
        members.addAll( List.of( articles ) );
        when( index.getCluster( name ) ).thenReturn(
            Optional.of( new ClusterDetails( name, hub, members, Map.of(), Instant.EPOCH ) ) );
    }

    private GetPageNeighborsTool tool( final PageViewGate gate ) {
        return new GetPageNeighborsTool( index, () -> refs, gate );
    }

    private static JsonObject json( final McpSchema.CallToolResult result ) {
        assertFalse( result.isError(), () -> "unexpected error: " + text( result ) );
        return JsonParser.parseString( text( result ) ).getAsJsonObject();
    }

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    private static List< String > slugs( final JsonArray arr ) {
        final List< String > out = new ArrayList<>();
        arr.forEach( e -> out.add( e.getAsJsonObject().get( "slug" ).getAsString() ) );
        return out;
    }

    @Test
    void returnsLinksBothWaysAndClusterSiblingsAsLightDescriptors() {
        final PageDescriptor self = page( "Retrieval", PageType.ARTICLE, "search" );
        final PageDescriptor hub = page( "SearchHub", PageType.HUB, "search" );
        page( "Bm25", PageType.REFERENCE, "search" );
        page( "Dense", PageType.ARTICLE, "search" );
        page( "Agents", PageType.ARTICLE );
        cluster( "search", hub, self, index.getByCanonicalId( "id-Bm25" ).get(), index.getByCanonicalId( "id-Dense" ).get() );
        // Uncreated link targets and the page's link to itself are not neighbors.
        when( refs.findRefersTo( "Retrieval" ) ).thenReturn( List.of( "Dense", "Bm25", "NotYetWritten", "Retrieval" ) );
        when( refs.findReferrers( "Retrieval" ) ).thenReturn( Set.of( "SearchHub", "Agents" ) );

        final JsonObject out = json( tool( PageViewGate.ALLOW_ALL ).execute( Map.of( "slug", "Retrieval" ) ) );

        assertEquals( "Retrieval", out.get( "page" ).getAsString() );
        assertEquals( List.of( "Bm25", "Dense" ), slugs( out.getAsJsonArray( "outbound" ) ) );
        assertEquals( 2, out.get( "outboundTotal" ).getAsInt() );
        assertEquals( List.of( "Agents", "SearchHub" ), slugs( out.getAsJsonArray( "inbound" ) ) );
        final JsonObject bm25 = out.getAsJsonArray( "outbound" ).get( 0 ).getAsJsonObject();
        assertEquals( "Bm25 title", bm25.get( "title" ).getAsString() );
        assertEquals( "reference", bm25.get( "type" ).getAsString() );
        assertEquals( "Bm25 summary", bm25.get( "summary" ).getAsString() );

        final JsonObject search = out.getAsJsonArray( "clusters" ).get( 0 ).getAsJsonObject();
        assertEquals( "search", search.get( "name" ).getAsString() );
        assertEquals( "SearchHub", search.getAsJsonObject( "hub" ).get( "slug" ).getAsString() );
        assertEquals( List.of( "Bm25", "Dense" ), slugs( search.getAsJsonArray( "siblings" ) ) );
        assertEquals( 2, search.get( "siblingsTotal" ).getAsInt() );
    }

    @Test
    void reportsEveryClusterOfAMultiMembershipPage() {
        page( "Retrieval", PageType.ARTICLE, "search", "agents" );
        final PageDescriptor searchHub = page( "SearchHub", PageType.HUB, "search" );
        final PageDescriptor agentsHub = page( "AgentsHub", PageType.HUB, "agents" );
        cluster( "search", searchHub, page( "Bm25", PageType.ARTICLE, "search" ) );
        cluster( "agents", agentsHub, page( "Memory", PageType.ARTICLE, "agents" ) );
        when( refs.findRefersTo( "Retrieval" ) ).thenReturn( List.of() );
        when( refs.findReferrers( "Retrieval" ) ).thenReturn( Set.of() );

        final JsonArray clusters = json( tool( PageViewGate.ALLOW_ALL ).execute( Map.of( "slug", "Retrieval" ) ) )
            .getAsJsonArray( "clusters" );

        assertEquals( 2, clusters.size() );
        assertEquals( "search", clusters.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( List.of( "Bm25" ), slugs( clusters.get( 0 ).getAsJsonObject().getAsJsonArray( "siblings" ) ) );
        assertEquals( "agents", clusters.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( List.of( "Memory" ), slugs( clusters.get( 1 ).getAsJsonObject().getAsJsonArray( "siblings" ) ) );
    }

    @Test
    void neighborsTheCallerCannotViewAreLeftOutOfListsAndTotals() {
        page( "Retrieval", PageType.ARTICLE, "search" );
        final PageDescriptor secretHub = page( "SecretHub", PageType.HUB, "search" );
        page( "Secret", PageType.ARTICLE, "search" );
        page( "Open", PageType.ARTICLE, "search" );
        cluster( "search", secretHub, index.getByCanonicalId( "id-Secret" ).get(), index.getByCanonicalId( "id-Open" ).get() );
        when( refs.findRefersTo( "Retrieval" ) ).thenReturn( List.of( "Secret", "Open" ) );
        when( refs.findReferrers( "Retrieval" ) ).thenReturn( Set.of( "Secret" ) );
        final PageViewGate guest = slug -> !slug.startsWith( "Secret" );

        final JsonObject out = json( tool( guest ).execute( Map.of( "slug", "Retrieval" ) ) );

        assertEquals( List.of( "Open" ), slugs( out.getAsJsonArray( "outbound" ) ) );
        assertEquals( 1, out.get( "outboundTotal" ).getAsInt() );
        assertEquals( List.of(), slugs( out.getAsJsonArray( "inbound" ) ) );
        assertEquals( 0, out.get( "inboundTotal" ).getAsInt() );
        final JsonObject search = out.getAsJsonArray( "clusters" ).get( 0 ).getAsJsonObject();
        assertFalse( search.has( "hub" ), "a restricted hub is not named" );
        assertEquals( List.of( "Open" ), slugs( search.getAsJsonArray( "siblings" ) ) );
    }

    @Test
    void aRestrictedPageReadsExactlyLikeAMissingOne() {
        page( "Secret", PageType.ARTICLE );
        final PageViewGate guest = slug -> !"Secret".equals( slug );

        final McpSchema.CallToolResult restricted = tool( guest ).execute( Map.of( "slug", "Secret" ) );
        final McpSchema.CallToolResult missing = tool( guest ).execute( Map.of( "slug", "Nope" ) );

        assertTrue( restricted.isError() );
        assertTrue( missing.isError() );
        assertEquals( text( missing ).replace( "Nope", "X" ), text( restricted ).replace( "Secret", "X" ) );
        verifyNoInteractions( refs );
    }

    @Test
    void limitCapsEachListButTotalsCountEveryNeighbor() {
        page( "Retrieval", PageType.ARTICLE );
        final List< String > targets = new ArrayList<>();
        for ( int i = 0; i < 5; i++ ) {
            page( "P" + i, PageType.ARTICLE );
            targets.add( "P" + i );
        }
        when( refs.findRefersTo( "Retrieval" ) ).thenReturn( targets );
        when( refs.findReferrers( "Retrieval" ) ).thenReturn( Set.copyOf( targets ) );

        final JsonObject out = json( tool( PageViewGate.ALLOW_ALL ).execute( Map.of( "slug", "Retrieval", "limit", 2 ) ) );

        assertEquals( List.of( "P0", "P1" ), slugs( out.getAsJsonArray( "outbound" ) ) );
        assertEquals( 5, out.get( "outboundTotal" ).getAsInt() );
        assertEquals( List.of( "P0", "P1" ), slugs( out.getAsJsonArray( "inbound" ) ) );
        assertEquals( 5, out.get( "inboundTotal" ).getAsInt() );
    }

    @Test
    void aMissingSlugArgumentIsAnError() {
        assertTrue( tool( PageViewGate.ALLOW_ALL ).execute( Map.of() ).isError() );
    }

    @Test
    void anUnreadyLinkIndexIsAnErrorNotAnEmptyAnswer() {
        page( "Retrieval", PageType.ARTICLE );
        final McpSchema.CallToolResult result =
            new GetPageNeighborsTool( index, () -> null, PageViewGate.ALLOW_ALL ).execute( Map.of( "slug", "Retrieval" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "not ready" ) );
    }

    @Test
    void definitionNamesThePageGraphAndIsReadOnly() {
        final McpSchema.Tool definition = tool( PageViewGate.ALLOW_ALL ).definition();
        assertEquals( "get_page_neighbors", definition.name() );
        assertTrue( definition.description().contains( "Page Graph" ) );
        assertTrue( ToolSchemas.properties( definition.inputSchema() ).containsKey( "slug" ) );
        assertTrue( definition.annotations().readOnlyHint() );
    }
}

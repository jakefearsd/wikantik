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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Argument handling, defaults and degrade paths of {@code get_page_neighbors}. */
class GetPageNeighborsToolArgumentsTest {

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
                Instant.EPOCH, false );
        when( index.resolveCanonicalIdFromSlug( slug ) ).thenReturn( Optional.of( d.canonicalId() ) );
        when( index.getByCanonicalId( d.canonicalId() ) ).thenReturn( Optional.of( d ) );
        return d;
    }

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    @Test
    void aBlankOrMissingSlugIsRefusedWithAMessageNamingTheArgument() {
        final GetPageNeighborsTool tool = new GetPageNeighborsTool( index, () -> refs, PageViewGate.ALLOW_ALL );
        for ( final Map< String, Object > args : List.< Map< String, Object > >of( Map.of(), Map.of( "slug", "  " ) ) ) {
            final var result = tool.execute( args );
            assertTrue( result.isError() );
            assertTrue( text( result ).contains( "slug argument is required" ), text( result ) );
        }
        verifyNoInteractions( index );
    }

    @Test
    void anUnknownPageIsReportedByName() {
        final var result = new GetPageNeighborsTool( index, () -> refs, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Nowhere" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "no page named Nowhere" ), text( result ) );
    }

    @Test
    void aNullReferenceSupplierDegradesToTheNotReadyMessage() {
        page( "Alpha", PageType.ARTICLE );
        final var result = new GetPageNeighborsTool( index, null, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Alpha" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "not ready yet" ), text( result ) );
    }

    @Test
    void aSupplierYieldingNoReferenceManagerYieldsTheNotReadyMessage() {
        page( "Alpha", PageType.ARTICLE );
        final var result = new GetPageNeighborsTool( index, () -> null, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Alpha" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "not ready yet" ), text( result ) );
    }

    @Test
    void aNullViewGateAllowsEveryPage() {
        page( "Alpha", PageType.ARTICLE );
        page( "Beta", PageType.ARTICLE );
        when( refs.findRefersTo( "Alpha" ) ).thenReturn( List.of( "Beta" ) );
        when( refs.findReferrers( "Alpha" ) ).thenReturn( Set.of() );
        final var result = new GetPageNeighborsTool( index, () -> refs, null ).execute( Map.of( "slug", "Alpha" ) );
        assertFalse( result.isError(), text( result ) );
        final JsonObject out = JsonParser.parseString( text( result ) ).getAsJsonObject();
        assertEquals( "Beta", out.getAsJsonArray( "outbound" ).get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    @Test
    void aClusterWithoutAHubStillListsItsMembersAndOmitsTheHub() {
        final PageDescriptor self = page( "Alpha", PageType.ARTICLE, "loose" );
        final PageDescriptor other = page( "Beta", PageType.ARTICLE, "loose" );
        when( index.getCluster( "loose" ) ).thenReturn( Optional.of(
                new ClusterDetails( "loose", null, List.of( self, other ), Map.of(), Instant.EPOCH ) ) );
        when( refs.findRefersTo( "Alpha" ) ).thenReturn( List.of() );
        when( refs.findReferrers( "Alpha" ) ).thenReturn( Set.of() );

        final var result = new GetPageNeighborsTool( index, () -> refs, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Alpha" ) );

        assertFalse( result.isError(), text( result ) );
        final JsonObject cluster = JsonParser.parseString( text( result ) ).getAsJsonObject()
                .getAsJsonArray( "clusters" ).get( 0 ).getAsJsonObject();
        assertFalse( cluster.has( "hub" ) );
        assertEquals( 1, cluster.get( "siblingsTotal" ).getAsInt() );
        assertEquals( "Beta", cluster.getAsJsonArray( "siblings" ).get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    @Test
    void aPageWithoutATypeIsListedWithTheUnknownType() {
        page( "Alpha", PageType.ARTICLE );
        page( "Untyped", null );
        when( refs.findRefersTo( "Alpha" ) ).thenReturn( List.of( "Untyped" ) );
        when( refs.findReferrers( "Alpha" ) ).thenReturn( Set.of() );

        final var result = new GetPageNeighborsTool( index, () -> refs, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Alpha" ) );

        assertFalse( result.isError(), text( result ) );
        final JsonArray outbound = JsonParser.parseString( text( result ) ).getAsJsonObject().getAsJsonArray( "outbound" );
        assertEquals( "Untyped", outbound.get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
        assertEquals( PageType.UNKNOWN.asFrontmatterValue(), outbound.get( 0 ).getAsJsonObject().get( "type" ).getAsString() );
    }

    @Test
    void anIndexFailureBecomesAnErrorResultCarryingTheMessage() {
        when( index.resolveCanonicalIdFromSlug( "Alpha" ) ).thenThrow( new IllegalStateException( "index down" ) );
        final var result = new GetPageNeighborsTool( index, () -> refs, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "slug", "Alpha" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "index down" ), text( result ) );
    }
}

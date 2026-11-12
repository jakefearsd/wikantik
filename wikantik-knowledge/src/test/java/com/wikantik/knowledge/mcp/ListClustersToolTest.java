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

import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListClustersToolTest {

    @Test
    void returns_tool_definition_with_expected_name() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final ListClustersTool tool = new ListClustersTool( svc );
        assertEquals( "list_clusters", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertNotNull( def );
        assertEquals( "list_clusters", def.name() );
    }

    @Test
    void execute_returns_clusters_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH, false ),
                12, Instant.EPOCH ) ) );

        final var result = new ListClustersTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "wikantik-development" ) );
    }

    private ClusterSummary cluster( final int i ) {
        return new ClusterSummary( "c" + i,
                new PageDescriptor( "01A" + i, "Hub" + i, "Hub " + i, PageType.HUB, "c" + i,
                        List.of(), "hub", Instant.EPOCH, false ),
                1, Instant.EPOCH );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > parse( final McpSchema.CallToolResult r ) {
        return new com.google.gson.Gson().fromJson(
                ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text(), Map.class );
    }

    @Test
    void execute_redactsHubPageWhenNotGuestViewable() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final ClusterSummary secret = new ClusterSummary( "secret-cluster",
                new PageDescriptor( "01S", "SecretHub", "Secret Hub Title", PageType.HUB, "secret-cluster",
                        List.of(), "hub", Instant.EPOCH, false ),
                5, Instant.EPOCH );
        final ClusterSummary open = new ClusterSummary( "open-cluster",
                new PageDescriptor( "01P", "PublicHub", "Public Hub Title", PageType.HUB, "open-cluster",
                        List.of(), "hub", Instant.EPOCH, false ),
                3, Instant.EPOCH );
        when( svc.listClusters() ).thenReturn( List.of( secret, open ) );
        when( svc.getCluster( "secret-cluster" ) ).thenReturn( java.util.Optional.of( new ClusterDetails(
                "secret-cluster", secret.hubPage(), List.of( article( "01S1", "SecretClusterOpenArticle", "2026-01-01T00:00:00Z" ) ),
                Map.of(), Instant.EPOCH ) ) );
        when( svc.getCluster( "open-cluster" ) ).thenReturn( java.util.Optional.of( new ClusterDetails(
                "open-cluster", open.hubPage(), List.of( article( "01P1", "OpenClusterArticle", "2026-01-01T00:00:00Z" ) ),
                Map.of(), Instant.EPOCH ) ) );

        // Gate denies the restricted hub page only.
        final PageViewGate gate = slug -> !"SecretHub".equals( slug );
        final var result = new ListClustersTool( svc, gate ).execute( Map.of() );

        assertFalse( result.isError() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        // The restricted hub page's descriptor (slug + title) must be redacted...
        assertFalse( text.contains( "SecretHub" ), "restricted hub page slug must not leak" );
        assertFalse( text.contains( "Secret Hub Title" ), "restricted hub page title must not leak" );
        // ...but the cluster itself (its name) still lists, and the public hub is untouched.
        assertTrue( text.contains( "secret-cluster" ), "the cluster name itself still lists" );
        assertTrue( text.contains( "PublicHub" ), "a guest-viewable hub page is unaffected" );
    }

    private static PageDescriptor article( final String id, final String slug, final String updated ) {
        return new PageDescriptor( id, slug, slug, PageType.ARTICLE, null, List.of(), List.of(), "s",
                Instant.parse( updated ), false, List.of() );
    }

    /** A cluster's count and freshness are over the members the caller may view; a fully hidden cluster is not listed. */
    @Test
    void execute_countsOnlyViewableMembersAndOmitsAFullyHiddenCluster() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final PageDescriptor mixedHub = new PageDescriptor( "01H", "MixedHub", "Mixed hub", PageType.HUB, "mixed",
                List.of(), "s", Instant.parse( "2026-01-01T00:00:00Z" ), false );
        final PageDescriptor hiddenHub = new PageDescriptor( "01X", "HiddenHub", "Hidden hub", PageType.HUB, "hidden",
                List.of(), "s", Instant.parse( "2026-09-09T00:00:00Z" ), false );
        final PageDescriptor open = article( "01O", "OpenArticle", "2026-02-01T00:00:00Z" );
        final PageDescriptor hiddenNewer = article( "01N", "HiddenNewer", "2026-09-09T00:00:00Z" );
        final PageDescriptor hiddenOnly = article( "01Y", "HiddenOnly", "2026-09-09T00:00:00Z" );
        when( svc.listClusters() ).thenReturn( List.of(
                new ClusterSummary( "mixed", mixedHub, 2, Instant.parse( "2026-09-09T00:00:00Z" ) ),
                new ClusterSummary( "hidden", hiddenHub, 1, Instant.parse( "2026-09-09T00:00:00Z" ) ) ) );
        when( svc.getCluster( "mixed" ) ).thenReturn( java.util.Optional.of( new ClusterDetails( "mixed", mixedHub,
                List.of( open, hiddenNewer ), Map.of(), Instant.EPOCH ) ) );
        when( svc.getCluster( "hidden" ) ).thenReturn( java.util.Optional.of( new ClusterDetails( "hidden", hiddenHub,
                List.of( hiddenOnly ), Map.of(), Instant.EPOCH ) ) );
        final PageViewGate gate = slug -> "MixedHub".equals( slug ) || "OpenArticle".equals( slug );

        final Map< String, Object > out = parse( new ListClustersTool( svc, gate ).execute( Map.of() ) );

        final List< ? > clusters = ( List< ? > ) out.get( "clusters" );
        assertEquals( 1, clusters.size(), "the fully hidden cluster is not listed: " + out );
        assertEquals( 1, ( (Number) out.get( "count" ) ).intValue(), "the total follows the filtered list" );
        final String text = clusters.toString();
        assertTrue( text.contains( "mixed" ) );
        assertFalse( text.contains( "hidden" ) || text.contains( "HiddenHub" ), "nothing of the hidden cluster: " + text );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > mixed = (Map< String, Object >) clusters.get( 0 );
        assertEquals( 1, ( (Number) mixed.get( "articleCount" ) ).intValue(), "the hidden member is not counted: " + mixed );
        assertTrue( mixed.toString().contains( "2026" ) && !mixed.toString().contains( "2026-09" ),
                "freshness must not come from the hidden newer member: " + mixed );
    }

    @Test
    void paginates_with_default_limit_and_offset() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listClusters() ).thenReturn(
                java.util.stream.IntStream.range( 0, 60 ).mapToObj( this::cluster ).toList() );

        // Default limit caps the dump at 50, but reports the true total + hasMore.
        final Map< String, Object > def = parse( new ListClustersTool( svc ).execute( Map.of() ) );
        assertEquals( 60.0, def.get( "count" ) );
        assertEquals( 50.0, def.get( "returned" ) );
        assertEquals( Boolean.TRUE, def.get( "hasMore" ) );
        assertEquals( 50, ( ( List< ? > ) def.get( "clusters" ) ).size() );

        // offset pages to the tail.
        final Map< String, Object > pg = parse( new ListClustersTool( svc ).execute( Map.of( "offset", 50 ) ) );
        assertEquals( 10.0, pg.get( "returned" ) );
        assertEquals( Boolean.FALSE, pg.get( "hasMore" ) );
    }
}

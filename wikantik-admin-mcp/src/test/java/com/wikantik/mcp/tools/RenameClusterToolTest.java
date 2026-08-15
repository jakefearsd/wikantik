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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.pagegraph.spine.ClusterRenameService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 *  ClusterDeclarationDesign Phase 4 — the {@code rename_cluster} MCP surface.
 *
 *  <p>Exercises the tool against a real {@link ClusterRenameService} over mocked
 *  collaborators, so the argument handling and the JSON envelope are checked against
 *  the service's actual behaviour rather than a stubbed stand-in.</p>
 */
class RenameClusterToolTest {

    private PageManager pageManager;
    private StructuralIndexService structural;
    private PageSaveHelper saveHelper;
    private RenameClusterTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        structural = mock( StructuralIndexService.class );
        saveHelper = mock( PageSaveHelper.class );
        when( structural.getCluster( anyString() ) ).thenReturn( Optional.empty() );
        tool = new RenameClusterTool( new ClusterRenameService( pageManager, structural, saveHelper ) );
    }

    private static PageDescriptor page( final String slug, final PageType type, final String cluster ) {
        return new PageDescriptor( "01HAA0000000000000000000" + slug.length(), slug, slug, type,
                cluster, List.of(), null, Instant.now(), Optional.empty(), false );
    }

    private void corpus( final PageDescriptor... pages ) {
        when( structural.listPagesByFilter( any( StructuralFilter.class ) ) ).thenReturn( List.of( pages ) );
        for ( final PageDescriptor p : pages ) {
            final Page mockPage = mock( Page.class );
            when( pageManager.getPage( p.slug() ) ).thenReturn( mockPage );
            when( pageManager.getPureText( mockPage ) ).thenReturn(
                    "---\ncanonical_id: " + p.canonicalId() + "\ntype: " + p.type().asFrontmatterValue()
                            + "\ncluster: " + p.cluster() + "\n---\nBody of " + p.slug() + "." );
        }
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > call( final Map< String, Object > args ) throws Exception {
        final McpSchema.CallToolResult result = tool.execute( args );
        return gson.fromJson( ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text(), Map.class );
    }

    /**
     * Without confirm the tool must preview, not refuse: a bulk rename is exactly the
     * operation a curator needs to see the blast radius of before committing to it.
     */
    @Test
    void without_confirm_it_returns_the_plan_and_writes_nothing() throws Exception {
        corpus( page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "machine-learning" );
        args.put( "to", "ml" );

        final Map< String, Object > data = call( args );

        assertFalse( ( Boolean ) data.get( "applied" ), "a plan preview must not report itself as applied" );
        assertEquals( 2.0, data.get( "pageCount" ) );
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        assertEquals( "ml/mlops", changes.get( 1 ).get( "toCluster" ) );
        verifyNoInteractions( saveHelper );
    }

    @Test
    void with_confirm_it_applies_the_rename() throws Exception {
        corpus( page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "machine-learning" );
        args.put( "to", "ml" );
        args.put( "confirm", true );

        final Map< String, Object > data = call( args );

        assertTrue( ( Boolean ) data.get( "applied" ) );
        assertTrue( ( Boolean ) data.get( "complete" ) );
        assertEquals( List.of( "MLHub", "InferenceServing" ), data.get( "renamed" ) );
    }

    /** A conflict must surface as an error before any page is rewritten. */
    @Test
    void a_conflicting_rename_is_refused_even_with_confirm() throws Exception {
        corpus( page( "AmericanCoinageHub", PageType.HUB, "american-coinage" ) );
        final PageDescriptor incumbent = page( "CoinCollectingHub", PageType.HUB, "numismatics" );
        when( structural.getCluster( "numismatics" ) ).thenReturn( Optional.of(
                new ClusterDetails( "numismatics", incumbent, List.of( incumbent ), Map.of(), Instant.now() ) ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "american-coinage" );
        args.put( "to", "numismatics" );
        args.put( "confirm", true );

        final Map< String, Object > data = call( args );

        assertTrue( String.valueOf( data.get( "error" ) ).contains( "CoinCollectingHub" ),
                    "the refusal must name the incumbent hub: " + data );
        verifyNoInteractions( saveHelper );
    }

    @Test
    void a_rename_to_the_same_cluster_is_refused() throws Exception {
        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "machine-learning" );
        args.put( "to", "machine-learning" );

        assertTrue( String.valueOf( call( args ).get( "error" ) ).contains( "same cluster" ) );
    }

    /**
     * The tool is registered unconditionally so the MCP surface is the same everywhere;
     * a deployment without a structural index must therefore refuse at call time with a
     * usable message, not vanish from tools/list.
     */
    @Test
    void without_a_structural_index_it_refuses_with_an_explanation() throws Exception {
        final RenameClusterTool unwired = new RenameClusterTool( null );

        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "machine-learning" );
        args.put( "to", "ml" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = unwired.execute( args );
        final Map< String, Object > data =
                gson.fromJson( ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text(), Map.class );

        assertTrue( String.valueOf( data.get( "error" ) ).contains( "structural index" ),
                    "the refusal must say why: " + data );
    }

    /** Renaming a cluster nothing names is a no-op the caller should be told about plainly. */
    @Test
    void an_empty_plan_reports_no_members() throws Exception {
        corpus();

        final Map< String, Object > args = new HashMap<>();
        args.put( "from", "no-such-cluster" );
        args.put( "to", "whatever" );

        final Map< String, Object > data = call( args );

        assertEquals( 0.0, data.get( "pageCount" ) );
        assertFalse( ( Boolean ) data.get( "applied" ) );
    }
}

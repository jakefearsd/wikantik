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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.api.providers.WikiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentHintsDeriverPreferToolsTest {

    private StructuralIndexService index;
    private PageManager pageManager;
    private ReferenceManager refs;
    private AgentHintsDeriver deriver;

    @BeforeEach
    void setUp() {
        index       = mock( StructuralIndexService.class );
        pageManager = mock( PageManager.class );
        refs        = mock( ReferenceManager.class );
        deriver     = new AgentHintsDeriver( index, pageManager, refs );
    }

    private PageDescriptor pd( final String id, final String slug, final String cluster ) {
        return new PageDescriptor(
                id, slug, slug, PageType.UNKNOWN, cluster,
                List.of(), null, Instant.parse( "2026-05-10T00:00:00Z" ), Optional.empty(), false );
    }

    @Test
    void preferToolsExtractsBareNamesFromMcpToolHintFrontmatter() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );  // no hub
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn(
                """
                ---
                mcp_tool_hints:
                  - tool: search_knowledge
                    when: example
                  - tool: /knowledge-mcp/get_page_for_agent
                    when: example
                ---
                body
                """ );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        assertEquals( List.of( "search_knowledge", "get_page_for_agent" ),
                      out.prefer_tools() );
    }

    @Test
    void preferToolsAggregatesAndRanksByFrequencyAcrossPageAndHub() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        final PageDescriptor hub  = pd( "hub_x",  "HubX",  "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.of(
                new ClusterDetails( "cluster_x", hub, List.of( self, hub ),
                                    Map.of(), Instant.parse( "2026-05-10T00:00:00Z" ) ) ) );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn(
                "---\nmcp_tool_hints:\n  - {tool: search_knowledge, when: x}\n---\nbody" );
        when( pageManager.getPureText( eq( "HubX" ), anyInt() ) ).thenReturn(
                "---\nmcp_tool_hints:\n" +
                "  - {tool: search_knowledge, when: x}\n" +
                "  - {tool: list_clusters, when: x}\n" +
                "---\nbody" );

        final AgentHintsBlock out = deriver.derive( "page_a" );

        // search_knowledge appears 2x (page + hub), list_clusters 1x → search_knowledge first
        assertEquals( "search_knowledge", out.prefer_tools().get( 0 ) );
        assertEquals( "list_clusters",    out.prefer_tools().get( 1 ) );
    }

    @Test
    void preferToolsCapsAtFive() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn(
                "---\nmcp_tool_hints:\n" +
                "  - {tool: t1, when: x}\n  - {tool: t2, when: x}\n" +
                "  - {tool: t3, when: x}\n  - {tool: t4, when: x}\n" +
                "  - {tool: t5, when: x}\n  - {tool: t6, when: x}\n" +
                "---\nbody" );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        // McpToolHintsResolver itself caps at 5; the deriver should see 5 and emit 5.
        assertEquals( 5, out.prefer_tools().size() );
    }

    @Test
    void unknownCanonicalIdReturnsEmptyBlock() {
        when( index.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final AgentHintsBlock out = deriver.derive( "missing" );
        assertEquals( AgentHintsBlock.empty(), out );
    }

    @Test
    void exceptionInPageManagerYieldsEmptyToolListNotThrows() throws Exception {
        final PageDescriptor self = pd( "page_a", "PageA", "cluster_x" );
        when( index.getByCanonicalId( "page_a" ) ).thenReturn( Optional.of( self ) );
        when( index.getCluster( "cluster_x" ) ).thenReturn( Optional.empty() );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) )
                .thenThrow( new RuntimeException( "boom" ) );

        final AgentHintsBlock out = deriver.derive( "page_a" );
        assertNotNull( out );
        assertTrue( out.prefer_tools().isEmpty() );
    }
}

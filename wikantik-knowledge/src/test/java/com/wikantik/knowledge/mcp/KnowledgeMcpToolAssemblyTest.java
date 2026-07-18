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

import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.mcp.tools.McpTool;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the Knowledge-MCP tool surface against the {@code wikantik.knowledge.enabled}
 * master flag: when {@code kgService} is null (the shape the disabled flag produces)
 * the six KG tools drop out, while the retrieval / page / spine tools stay.
 */
class KnowledgeMcpToolAssemblyTest {

    /** The six KG tools gated on a non-null kgService. */
    private static final Set< String > KG_TOOLS = Set.of(
            "discover_schema", "query_nodes", "get_node",
            "traverse", "search_knowledge", "find_similar" );

    private static Set< String > names( final java.util.List< McpTool > tools ) {
        return tools.stream().map( McpTool::name ).collect( Collectors.toSet() );
    }

    @Test
    void kgToolsAbsentWhenKgServiceNull_retrievalAndSpineStay() {
        final ContextRetrievalService ctx = mock( ContextRetrievalService.class );
        final StructuralIndexService spine = mock( StructuralIndexService.class );
        final PageManager pages = mock( PageManager.class );

        final Set< String > names = names( KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                .ctxService( ctx )
                .pageManager( pages )
                .structuralIndex( spine )
                .viewGate( PageViewGate.ALLOW_ALL )
                .build() ) );

        for ( final String kg : KG_TOOLS ) {
            assertFalse( names.contains( kg ), "KG tool '" + kg + "' must be absent when kgService is null" );
        }
        // Retrieval + structural-spine tools remain available.
        assertTrue( names.contains( "retrieve_context" ), "retrieve_context must stay" );
        assertTrue( names.contains( "read_pages" ),       "read_pages must stay" );
        assertTrue( names.contains( "list_clusters" ),    "list_clusters must stay" );
        assertTrue( names.contains( "get_page_by_id" ),   "get_page_by_id must stay" );
    }

    @Test
    void kgToolsPresentWhenKgServiceWired() {
        final KnowledgeGraphService kg = mock( KnowledgeGraphService.class );
        final NodeMentionSimilarity similarity = mock( NodeMentionSimilarity.class );
        final ContextRetrievalService ctx = mock( ContextRetrievalService.class );
        final StructuralIndexService spine = mock( StructuralIndexService.class );
        final ForAgentProjectionService forAgent = mock( ForAgentProjectionService.class );
        final PageManager pages = mock( PageManager.class );

        final Set< String > names = names( KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                .kgService( kg )
                .similarity( similarity )
                .ctxService( ctx )
                .pageManager( pages )
                .structuralIndex( spine )
                .forAgent( forAgent )
                .viewGate( PageViewGate.ALLOW_ALL )
                .build() ) );

        for ( final String kgTool : KG_TOOLS ) {
            assertTrue( names.contains( kgTool ), "KG tool '" + kgTool + "' must be present when kgService is wired" );
        }
    }
}

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

import com.wikantik.agentreports.AgentReportService;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.citation.CitationRepository;
import com.wikantik.mcp.tools.ExchangeAwareMcpTool;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.ontology.OntologyModelManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Which tools {@link KnowledgeMcpInitializer#assembleTools} registers for each combination of collaborators. */
class KnowledgeMcpAssemblyToolSetTest {

    private static final Set< String > RETRIEVAL = Set.of( "retrieve_context", "get_page", "list_pages",
            "list_metadata_values", "read_pages" );
    private static final Set< String > SPINE = Set.of( "list_clusters", "list_tags", "list_pages_by_filter",
            "get_page_by_id", "get_page_neighbors" );

    private static Set< String > names( final KnowledgeToolDeps deps ) {
        return KnowledgeMcpInitializer.assembleTools( deps ).stream().map( McpTool::name )
                .collect( Collectors.toCollection( TreeSet::new ) );
    }

    private static Set< String > plus( final Set< String > base, final String... more ) {
        final Set< String > out = new TreeSet<>( base );
        out.addAll( List.of( more ) );
        return out;
    }

    @Test
    void withNoCollaboratorsOnlyTheReportFilingToolIsRegistered() {
        assertEquals( Set.of( "file_agent_report" ), names( KnowledgeToolDeps.builder().build() ) );
    }

    @Test
    void theRetrievalServiceBringsExactlyTheRetrievalTools() {
        assertEquals( plus( RETRIEVAL, "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .ctxService( mock( ContextRetrievalService.class ) ).pageManager( mock( PageManager.class ) ).build() ) );
    }

    @Test
    void theStructuralIndexBringsExactlyTheSpineTools() {
        assertEquals( plus( SPINE, "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .structuralIndex( mock( StructuralIndexService.class ) ).build() ) );
    }

    @Test
    void eachOptionalServiceBringsItsOwnToolAndNothingElse() {
        assertEquals( Set.of( "get_page_for_agent", "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .forAgent( mock( ForAgentProjectionService.class ) ).build() ) );
        assertEquals( Set.of( "assemble_bundle", "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .bundleService( mock( BundleAssemblyService.class ) ).build() ) );
        assertEquals( Set.of( "get_briefing", "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .briefingService( mock( BriefingAssemblyService.class ) ).build() ) );
        assertEquals( Set.of( "list_stale_citations", "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .citationRepo( mock( CitationRepository.class ) ).build() ) );
        assertEquals( Set.of( "get_ontology", "sparql_query", "file_agent_report" ), names( KnowledgeToolDeps.builder()
                .ontoMgr( OntologyModelManager.inMemory() ).build() ) );
    }

    @Test
    void aFullyWiredEndpointRegistersSeventeenToolsEachOnce() {
        final List< McpTool > tools = KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                .ctxService( mock( ContextRetrievalService.class ) ).pageManager( mock( PageManager.class ) )
                .structuralIndex( mock( StructuralIndexService.class ) ).forAgent( mock( ForAgentProjectionService.class ) )
                .bundleService( mock( BundleAssemblyService.class ) ).briefingService( mock( BriefingAssemblyService.class ) )
                .ontoMgr( OntologyModelManager.inMemory() ).citationRepo( mock( CitationRepository.class ) )
                .agentReports( () -> mock( AgentReportService.class ) ).build() );
        assertEquals( 17, tools.size() );
        assertEquals( 17, tools.stream().map( McpTool::name ).distinct().count() );
    }

    @Test
    void theViewGateReachesTheToolsItProtects() {
        final StructuralIndexService index = mock( StructuralIndexService.class );
        final var found = new com.wikantik.api.pagegraph.PageDescriptor( "01A", "Secret", "Secret",
                com.wikantik.api.pagegraph.PageType.ARTICLE, null, List.of(), "s", java.time.Instant.EPOCH, false );
        org.mockito.Mockito.when( index.getByCanonicalId( "01A" ) ).thenReturn( java.util.Optional.of( found ) );
        final McpTool byId = KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                        .structuralIndex( index ).viewGate( slug -> !"Secret".equals( slug ) ).build() )
                .stream().filter( t -> "get_page_by_id".equals( t.name() ) ).findFirst().orElseThrow();
        assertTrue( byId.execute( Map.of( "id", "01A" ) ).isError(), "the gate from the deps must be the one the tool enforces" );
    }

    @Test
    void invokeHandsTheExchangeOnlyToToolsThatAskForIt() {
        final McpSyncServerExchange exchange = mock( McpSyncServerExchange.class );
        final McpSchema.CallToolResult plainResult = McpSchema.CallToolResult.builder().addTextContent( "plain" ).build();
        final McpSchema.CallToolResult awareResult = McpSchema.CallToolResult.builder().addTextContent( "aware" ).build();
        final McpTool plain = new McpTool() {
            @Override public String name() { return "plain"; }
            @Override public McpSchema.Tool definition() { return null; }
            @Override public McpSchema.CallToolResult execute( final Map< String, Object > a ) { return plainResult; }
        };
        final ExchangeAwareMcpTool aware = new ExchangeAwareMcpTool() {
            @Override public String name() { return "aware"; }
            @Override public McpSchema.Tool definition() { return null; }
            @Override public McpSchema.CallToolResult execute( final Map< String, Object > a ) { return plainResult; }
            @Override public McpSchema.CallToolResult execute( final Map< String, Object > a, final McpSyncServerExchange e ) {
                assertSame( exchange, e );
                return awareResult;
            }
        };
        assertSame( plainResult, KnowledgeMcpInitializer.invoke( plain, Map.of(), exchange ) );
        assertSame( awareResult, KnowledgeMcpInitializer.invoke( aware, Map.of(), exchange ) );
    }
}

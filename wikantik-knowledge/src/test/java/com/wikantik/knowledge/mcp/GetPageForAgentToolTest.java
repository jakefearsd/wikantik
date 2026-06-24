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
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pagegraph.Confidence;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageForAgentToolTest {

    private ForAgentProjectionService svc;
    private GetPageForAgentTool tool;

    @BeforeEach
    void setUp() {
        svc = mock( ForAgentProjectionService.class );
        tool = new GetPageForAgentTool( svc );
    }

    @Test
    void name_and_definition_are_well_formed() {
        assertEquals( "get_page_for_agent", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_page_for_agent", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.description().toLowerCase().contains( "agent" ) );
        assertTrue( def.inputSchema().required().contains( "canonical_id" ) );
    }

    @Test
    void missing_argument_returns_error_result() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        assertTrue( result.isError(), "missing canonical_id must return isError = true" );
    }

    @Test
    void unknown_canonical_id_returns_error_result() {
        when( svc.project( "missing" ) ).thenReturn( Optional.empty() );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "canonical_id", "missing" ) );
        assertTrue( result.isError() );
    }

    @Test
    void happy_path_serialises_projection() {
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                null, null, null,
                "Operator reference for hybrid retrieval.",
                List.of(), List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "canonical_id", "01ABC" ) );

        assertFalse( result.isError() );
        assertFalse( result.content().isEmpty() );
        assertTrue( result.content().toString().contains( "HybridRetrieval" ) );
    }

    @Test
    void restrictedPageFilteredForGuest() {
        final ForAgentProjection secret = new ForAgentProjection(
                "01SECRET", "Secret", "Secret Page", "article",
                "classified",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                null, null, null,
                "TOP SECRET SUMMARY",
                List.of(), List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/Secret", "/wiki/Secret?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01SECRET" ) ).thenReturn( Optional.of( secret ) );

        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final GetPageForAgentTool gated = new GetPageForAgentTool( svc, gate );
        final McpSchema.CallToolResult result = gated.execute( Map.of( "canonical_id", "01SECRET" ) );

        assertTrue( result.isError(), "restricted page must return error (same as not found)" );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "TOP SECRET SUMMARY" ),
                "restricted page content must not leak through get_page_for_agent" );
    }

    @Test
    void wire_shape_includes_agent_hints_and_summary_synthesized() {
        // Fixture passes agentHints=null, summarySynthesized=false (the null-serialisation path).
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                null, null, null,
                "Operator reference for hybrid retrieval.",
                List.of(), List.of(), List.of(), List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "canonical_id", "01ABC" ) );

        assertFalse( result.isError() );
        final String json = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        final JsonObject body = JsonParser.parseString( json ).getAsJsonObject();

        assertTrue( body.has( "agent_hints" ),
                "agent_hints field expected on MCP get_page_for_agent response" );
        assertTrue( body.has( "summary_synthesized" ),
                "summary_synthesized field expected on MCP get_page_for_agent response" );
        // Fixture passes agentHints=null — should serialise as JSON null, not be absent.
        assertTrue( body.get( "agent_hints" ).isJsonNull(),
                "fixture passes agentHints=null which should serialise as JSON null" );
        assertEquals( false, body.get( "summary_synthesized" ).getAsBoolean() );
    }
}

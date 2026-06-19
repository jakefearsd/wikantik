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
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AssembleBundleToolTest {

    private AssembleBundleTool tool;

    private static final ContextBundle FIXED_BUNDLE = new ContextBundle(
            "deploy",
            List.of( new BundleSection(
                    "01DEP", "DeployGuide", List.of( "Setup" ), "do x", 0.9,
                    new CitationHandle( "01DEP", 7, List.of( "Setup" ), "do x", "abc123" )
            ) )
    );

    @BeforeEach
    void setUp() {
        final BundleAssemblyService stub = query -> FIXED_BUNDLE;
        tool = new AssembleBundleTool( stub, () -> null );
    }

    @Test
    void execute_logsQuery_asAgentOnMcpSurface() {
        final QueryLogService qlog = mock( QueryLogService.class );
        final AssembleBundleTool t = new AssembleBundleTool( query -> FIXED_BUNDLE, () -> qlog );

        t.execute( Map.of( "query", "deploy" ) );

        // MCP is agent-by-construction; result_count = bundle section count
        verify( qlog ).log( "deploy", ActorType.AGENT, SourceSurface.MCP_ASSEMBLE_BUNDLE, 1 );
    }

    @Test
    void execute_missingQuery_doesNotLog() {
        final QueryLogService qlog = mock( QueryLogService.class );
        final AssembleBundleTool t = new AssembleBundleTool( query -> FIXED_BUNDLE, () -> qlog );

        t.execute( Map.of() );

        verifyNoInteractions( qlog );
    }

    @Test
    void name_is_assemble_bundle() {
        assertEquals( "assemble_bundle", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertEquals( "assemble_bundle", def.name() );
        assertTrue( def.inputSchema().required().contains( "query" ) );
    }

    @Test
    void execute_returns_bundle_json() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "query", "deploy" ) );

        assertFalse( result.isError() );
        assertFalse( result.content().isEmpty() );

        final String json = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        final JsonObject body = JsonParser.parseString( json ).getAsJsonObject();

        assertEquals( "deploy", body.get( "query" ).getAsString() );
        final JsonObject section = body.getAsJsonArray( "sections" ).get( 0 ).getAsJsonObject();
        assertEquals( "01DEP", section.get( "canonicalId" ).getAsString() );
        assertEquals( 7, section.getAsJsonObject( "citation" ).get( "version" ).getAsInt() );
    }

    @Test
    void missing_query_returns_error() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        assertTrue( result.isError(), "missing query must return isError = true" );
    }
}

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
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void restrictedPageFilteredForGuest() {
        final ContextBundle bundleWithSecret = new ContextBundle( "q", List.of(
                new BundleSection( "01SEC", "Secret", List.of( "Setup" ), "TOP SECRET BODY", 0.99,
                        new CitationHandle( "01SEC", 1, List.of( "Setup" ), "TOP SECRET BODY", "abc" ) ),
                new BundleSection( "01PUB", "PublicPage", List.of( "Intro" ), "public content", 0.80,
                        new CitationHandle( "01PUB", 2, List.of( "Intro" ), "public content", "def" ) )
        ) );
        final BundleAssemblyService stub = query -> bundleWithSecret;
        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final AssembleBundleTool t = new AssembleBundleTool( stub, () -> null, gate );

        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "q" ) );

        assertFalse( result.isError() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "TOP SECRET BODY" ),
                "restricted page content must not leak through assemble_bundle" );
        assertTrue( text.contains( "public content" ),
                "non-restricted sections must still appear" );
    }

    /* ---------- mode argument ---------- */

    @Test
    void mode_dense_calls_assemble_with_DENSE() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        when( svc.assemble( eq( "deploy" ), eq( RetrievalMode.DENSE ) ) ).thenReturn( FIXED_BUNDLE );
        final AssembleBundleTool t = new AssembleBundleTool( svc, () -> null );

        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "deploy", "mode", "dense" ) );

        assertFalse( result.isError() );
        verify( svc ).assemble( "deploy", RetrievalMode.DENSE );
    }

    @Test
    void missing_mode_calls_assemble_with_HYBRID() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        when( svc.assemble( eq( "deploy" ), eq( RetrievalMode.HYBRID ) ) ).thenReturn( FIXED_BUNDLE );
        final AssembleBundleTool t = new AssembleBundleTool( svc, () -> null );

        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "deploy" ) );   // no mode key

        assertFalse( result.isError() );
        verify( svc ).assemble( "deploy", RetrievalMode.HYBRID );
    }

    @Test
    void bogus_mode_returns_error_result_and_no_assemble_call() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final AssembleBundleTool t = new AssembleBundleTool( svc, () -> null );

        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "deploy", "mode", "bogus" ) );

        assertTrue( result.isError(), "invalid mode must return isError = true" );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "hybrid" ) && text.contains( "dense" ) && text.contains( "lexical" ),
                "error must list valid mode values; got: " + text );
        verifyNoInteractions( svc );
    }

    @Test
    void serializesCoverageBlock() {
        // Three viewable sections so the strong coverage survives the >=3 floor check in recount.
        final List< BundleSection > three = List.of(
                new BundleSection( "01DEP", "DeployGuide", List.of( "Setup" ), "do x", 0.9,
                        new CitationHandle( "01DEP", 7, List.of( "Setup" ), "do x", "abc123" ) ),
                new BundleSection( "02CFG", "Config", List.of( "Setup" ), "config y", 0.85,
                        new CitationHandle( "02CFG", 1, List.of( "Setup" ), "config y", "def456" ) ),
                new BundleSection( "03RUN", "Runbook", List.of( "Run" ), "run z", 0.80,
                        new CitationHandle( "03RUN", 2, List.of( "Run" ), "run z", "ghi789" ) )
        );
        final BundleCoverage cov = new BundleCoverage( 3, 3, 0.82, BundleCoverage.STRONG );
        final ContextBundle withCoverage = new ContextBundle( "deploy", three, cov );
        final BundleAssemblyService stub = query -> withCoverage;
        final AssembleBundleTool t = new AssembleBundleTool( stub, () -> null );

        final McpSchema.CallToolResult res = t.execute( Map.of( "query", "deploy" ) );
        final String json = ( (McpSchema.TextContent) res.content().get( 0 ) ).text();
        final JsonObject obj = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject coverage = obj.getAsJsonObject( "coverage" );
        assertEquals( "strong", coverage.get( "confidence" ).getAsString() );
        assertEquals( 0.82, coverage.get( "topSimilarity" ).getAsDouble(), 1e-9 );
        assertEquals( 3, coverage.get( "sectionCount" ).getAsInt() );
    }
}

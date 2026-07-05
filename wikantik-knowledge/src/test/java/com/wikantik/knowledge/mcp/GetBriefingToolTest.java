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

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetBriefingToolTest {

    private static final BundleSection SECTION_A = new BundleSection(
            "01PIN", "PageA", List.of( "Intro" ), "section body A", 0.9,
            new CitationHandle( "01PIN", 1, List.of( "Intro" ), "section body A", "hashA" ) );

    private static final BriefingItem PIN_ITEM_A = new BriefingItem(
            "PageA", "01PIN", "Page A Title", "summary A", "pin", true, "full content A" );

    private static ContextBriefing briefingWith( final List< BundleSection > sections, final List< BriefingItem > items ) {
        return new ContextBriefing( "prompt", sections, new BundleCoverage( sections.size(), sections.size(), 0.9, BundleCoverage.STRONG ),
                items, List.of(), 4000, 100 );
    }

    /* ---------- (a) no args -> error ---------- */

    @Test
    void execute_noArgs_returnsErrorResult() {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError(), "no pins/clusters/prompt must return isError = true" );
        verifyNoInteractions( svc );
    }

    /* ---------- (b) pins returns markdown with title ---------- */

    @Test
    void execute_withPins_returnsMarkdownWithTitle() {
        final ContextBriefing briefing = briefingWith( List.of(), List.of( PIN_ITEM_A ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        assertFalse( result.isError() );
        final String md = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( md.contains( "# Wiki context briefing" ), "must render the briefing heading; got: " + md );
        assertTrue( md.contains( "Page A Title" ), "must contain pinned page's title; got: " + md );
    }

    /* ---------- (c) viewGate denies -> section and pointer absent ---------- */

    @Test
    void execute_viewGateDenies_dropsSectionAndPointer() {
        final BriefingItem pointerItem = new BriefingItem(
                "PageA", "01PIN", "Page A Title", "summary A", "pin", false, null );
        final ContextBriefing briefing = briefingWith( List.of( SECTION_A ), List.of( pointerItem ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final PageViewGate denyA = slug -> !"PageA".equals( slug );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, denyA );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        assertFalse( result.isError() );
        final String md = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( md.contains( "section body A" ), "restricted section must not appear; got: " + md );
        assertFalse( md.contains( "Page A Title" ), "restricted pointer must not leak even a title; got: " + md );
    }

    /* ---------- (c2) dropped-restricted pin warns like a nonexistent one ---------- */

    @Test
    void execute_viewGateDeniesPin_surfacesUnknownPinWarning() {
        final BriefingItem pinItem = new BriefingItem(
                "PageA", "01PIN", "Page A Title", "summary A", "pin", true, "full content A" );
        final ContextBriefing briefing = briefingWith( List.of(), List.of( pinItem ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final PageViewGate denyA = slug -> !"PageA".equals( slug );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, denyA );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        final String md = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( md.contains( "Page A Title" ), "restricted pin must not leak a title; got: " + md );
        assertTrue( md.contains( "unknown pin: PageA" ),
                "dropped-restricted pin must warn like a nonexistent one; got: " + md );
    }

    /* ---------- (d) definition ---------- */

    @Test
    void definition_hasNameAndSchemaProperties() {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        assertEquals( "get_briefing", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_briefing", def.name() );
        final Map< String, Object > props = def.inputSchema().properties();
        assertTrue( props.containsKey( "pins" ) );
        assertTrue( props.containsKey( "clusters" ) );
        assertTrue( props.containsKey( "prompt" ) );
        assertTrue( props.containsKey( "budget" ) );
        assertTrue( props.containsKey( "scope_mode" ) );
        assertTrue( def.inputSchema().required().isEmpty(), "no required fields — validated via hasAnySource" );
    }

    /* ---------- (e) briefing-log surface ---------- */

    @Test
    void execute_logsBriefing_withMcpGetBriefingSurface() {
        final ContextBriefing briefing = briefingWith( List.of(), List.of( PIN_ITEM_A ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingLogService blog = mock( BriefingLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> blog, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        verify( blog ).log( argThatSurfaceIsMcpGetBriefing() );
    }

    private static BriefingLogEntry argThatSurfaceIsMcpGetBriefing() {
        return org.mockito.ArgumentMatchers.argThat( entry ->
                entry != null && "mcp_get_briefing".equals( entry.surface() ) && entry.pinCount() == 1 );
    }

    @Test
    void execute_withPrompt_logsQuery_asAgentOnMcpGetBriefingSurface() {
        final ContextBriefing briefing = briefingWith( List.of( SECTION_A ), List.of() );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "prompt", "deploy steps" ) );

        verify( qlog ).log( "deploy steps", ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, 1 );
    }

    @Test
    void execute_withoutPrompt_doesNotLogQuery() {
        final ContextBriefing briefing = briefingWith( List.of(), List.of( PIN_ITEM_A ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        verifyNoInteractions( qlog );
    }

    /* ---------- (f) RuntimeException -> errorResult, no propagation ---------- */

    @Test
    void execute_serviceThrows_returnsErrorResultWithoutPropagating() {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenThrow( new RuntimeException( "boom" ) );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        assertTrue( result.isError() );
    }
}

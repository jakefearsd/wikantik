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
import org.mockito.ArgumentCaptor;

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

    /* ---------- (c3) unknown argument is warned, not silently ignored ---------- */

    @Test
    void execute_unknownArgument_isWarnedNotIgnored() {
        final ContextBriefing briefing = briefingWith( List.of(), List.of( PIN_ITEM_A ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        final McpSchema.CallToolResult result =
            tool.execute( Map.of( "clusters", List.of( "roller" ), "scope", "strict" ) );

        assertFalse( result.isError(), "an unknown argument is a warning, not a failure" );
        final String md = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( md.contains( "unknown argument: scope" ), "got: " + md );
        verify( svc ).assemble( any( BriefingRequest.class ) );
    }

    @Test
    void execute_onlyKnownArguments_addsNoUnknownWarning() {
        final ContextBriefing briefing = briefingWith( List.of(), List.of( PIN_ITEM_A ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pins", List.of( "PageA" ),
            "clusters", List.of( "roller" ), "prompt", "p", "budget", 500, "scope_mode", "strict" ) );

        final String md = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( md.contains( "unknown argument" ), "got: " + md );
    }

    /* ---------- (d) definition ---------- */

    @Test
    void definition_hasNameAndSchemaProperties() {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );

        assertEquals( "get_briefing", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_briefing", def.name() );
        final Map< String, Object > props = ToolSchemas.properties( def.inputSchema() );
        assertTrue( props.containsKey( "pins" ) );
        assertTrue( props.containsKey( "clusters" ) );
        assertTrue( props.containsKey( "prompt" ) );
        assertTrue( props.containsKey( "budget" ) );
        assertTrue( props.containsKey( "scope_mode" ) );
        assertTrue( ToolSchemas.required( def.inputSchema() ).isEmpty(), "no required fields — validated via hasAnySource" );
        assertTrue( def.description().contains( "Contracts this project cites" ),
                "the tool description names the cited tier, not just pins and cluster members: " + def.description() );
        assertTrue( def.description().contains( "Elsewhere" ) || def.description().contains( "elsewhere" ),
                "and says out-of-scope hits become pointers: " + def.description() );
        final String scopeModeDescription = String.valueOf( ( (Map< ?, ? >) props.get( "scope_mode" ) ).get( "description" ) );
        assertTrue( scopeModeDescription.contains( "pointers" ) && scopeModeDescription.contains( "dropped" ),
                "scope_mode describes pointers (prefer) and dropped hits (strict): " + scopeModeDescription );
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

    /**
     * pointerCount is the budget-trimmed pin/cluster pointers only; cited and elsewhere items are
     * counted in their own columns so they cannot inflate it. scope_mode is logged in wire form.
     */
    @Test
    void execute_logsCitedAndElsewhereCounts() {
        final BriefingItem clusterPointer = new BriefingItem(
                "PageB", "01B", "Page B", "summary B", "cluster", false, null );
        final BriefingItem citedPointer = new BriefingItem(
                "SharedC", "01C", "Shared C", "summary C", "cited", false, null, "shared" );
        final BriefingItem elsewherePointer = new BriefingItem(
                "OtherD", "01D", "Other D", "summary D", "elsewhere", false, null, "other" );
        final ContextBriefing briefing = briefingWith( List.of(),
                List.of( PIN_ITEM_A, clusterPointer, citedPointer, elsewherePointer ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingLogService blog = mock( BriefingLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> blog, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "pins", List.of( "PageA" ), "scope_mode", "strict" ) );

        final ArgumentCaptor< BriefingLogEntry > captor = ArgumentCaptor.forClass( BriefingLogEntry.class );
        verify( blog ).log( captor.capture() );
        final BriefingLogEntry entry = captor.getValue();
        assertEquals( 1, entry.pinCount() );
        assertEquals( 1, entry.pointerCount(), "only the cluster pointer; cited/elsewhere are counted separately" );
        assertEquals( 1, entry.citedCount() );
        assertEquals( 1, entry.elsewhereCount() );
        assertEquals( "strict", entry.scopeMode() );
    }

    @Test
    void execute_logsPreferScopeMode_whenNoneSupplied() {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) )
                .thenReturn( briefingWith( List.of(), List.of( PIN_ITEM_A ) ) );
        final BriefingLogService blog = mock( BriefingLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> null, () -> blog, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "pins", List.of( "PageA" ) ) );

        final ArgumentCaptor< BriefingLogEntry > captor = ArgumentCaptor.forClass( BriefingLogEntry.class );
        verify( blog ).log( captor.capture() );
        assertEquals( "prefer", captor.getValue().scopeMode(), "the effective mode is logged, never null" );
        assertEquals( 0, captor.getValue().citedCount() );
        assertEquals( 0, captor.getValue().elsewhereCount() );
    }

    @Test
    void execute_withPrompt_logsQuery_asAgentOnMcpGetBriefingSurface() {
        final ContextBriefing briefing = briefingWith( List.of( SECTION_A ), List.of() );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "prompt", "deploy steps" ) );

        // What retrieval found is recorded, not what survived the gate or the scope partition:
        // briefingWith carries no separate retrievalCoverage, so it is the briefing's STRONG coverage
        // even though BriefingAclGate recounts the delivered coverage down to PARTIAL.
        verify( qlog ).log( "deploy steps", ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, 1,
                BundleCoverage.STRONG, null );
    }

    /**
     * A cluster-scoped briefing whose retrieval hits all lie outside the cluster delivers zero
     * sections (they become "elsewhere" pointers) yet retrieval was strong. The query log must see
     * the retrieval truth, or Content Intelligence reads a false AGENT_GAP.
     */
    @Test
    void execute_logsRetrievalCoverage_notDeliveredCoverage() {
        final BriefingItem e1 = new BriefingItem( "OtherA", "01A", "Other A", "H", "elsewhere", false, null, "other" );
        final BriefingItem e2 = new BriefingItem( "OtherB", "01B", "Other B", "H", "elsewhere", false, null, "other" );
        final BriefingItem e3 = new BriefingItem( "OtherC", "01C", "Other C", "H", "elsewhere", false, null, "other" );
        final ContextBriefing briefing = new ContextBriefing( "prompt", List.of(),
                new BundleCoverage( 0, 0, 0.9, BundleCoverage.WEAK ), List.of( e1, e2, e3 ), List.of(), 4000, 0,
                new BundleCoverage( 12, 6, 0.9, BundleCoverage.STRONG ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "prompt", "deploy steps", "clusters", List.of( "roller" ) ) );

        verify( qlog ).log( "deploy steps", ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, 3,
                BundleCoverage.STRONG, null );
    }

    /**
     * The MCP half of the briefing surface must feed AGENT_GAP the same datum its REST twin
     * (BriefingResource) already does. It previously called the 4-arg log overload, storing a NULL
     * coverage, so a briefing that covered the prompt poorly was indistinguishable from one that
     * covered it well.
     */
    @Test
    void execute_recordsBriefingCoverage_soAgentGapHasItsInput() {
        final ContextBriefing briefing = new ContextBriefing( "prompt", List.of( SECTION_A ),
                new BundleCoverage( 1, 1, 0.18, BundleCoverage.WEAK ), List.of(), List.of(), 4000, 100 );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, PageViewGate.ALLOW_ALL );

        tool.execute( Map.of( "prompt", "deploy steps" ) );

        verify( qlog ).log( "deploy steps", ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, 1,
                BundleCoverage.WEAK, null );
    }

    /**
     * The retrieval backend does not ACL-filter. The query log is server-side and independent of who asked,
     * so it gets the raw pre-gate confidence and hit count even when the gate hides a section from this
     * (guest) caller.
     */
    @Test
    void execute_logsPreGateRetrievalFigures_evenWhenTheViewGateHidesASection() {
        final BundleSection hidden = new BundleSection( "01HID", "HiddenPage", List.of( "Intro" ), "hidden body", 0.8,
                new CitationHandle( "01HID", 1, List.of( "Intro" ), "hidden body", "hashH" ) );
        final ContextBriefing briefing = new ContextBriefing( "prompt", List.of( SECTION_A, hidden ),
                new BundleCoverage( 2, 2, 0.9, BundleCoverage.STRONG ), List.of(), List.of(), 4000, 100,
                new BundleCoverage( 9, 5, 0.9, BundleCoverage.STRONG ) );
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final PageViewGate denyHidden = slug -> !"HiddenPage".equals( slug );
        final GetBriefingTool tool = new GetBriefingTool( svc, () -> qlog, () -> null, denyHidden );

        tool.execute( Map.of( "prompt", "deploy steps" ) );

        verify( qlog ).log( "deploy steps", ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, 2,
                BundleCoverage.STRONG, null );
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

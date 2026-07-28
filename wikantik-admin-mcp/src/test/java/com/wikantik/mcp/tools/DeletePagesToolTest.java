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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Page;
import com.wikantik.mcp.ToolSchemas;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.managers.SystemPageRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeletePagesTool} — the destructive batch-delete MCP tool.
 *
 * <p>The safety rails are what matter here: {@code confirm=true}, the system-page
 * refusal, and the backlink guard. Each must be provable at the unit level because
 * a regression silently destroys wiki content.</p>
 */
class DeletePagesToolTest {

    private PageManager        pageManager;
    private ReferenceManager   referenceManager;
    private SystemPageRegistry systemPages;
    private DeletePagesTool    tool;

    @BeforeEach
    void setUp() {
        pageManager      = mock( PageManager.class );
        referenceManager = mock( ReferenceManager.class );
        systemPages      = mock( SystemPageRegistry.class );
        tool = new DeletePagesTool( pageManager, referenceManager, systemPages );
    }

    // ------------------------------------------------------------------ helpers

    private static String text( final McpSchema.CallToolResult result ) {
        return ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
    }

    private static JsonObject payload( final McpSchema.CallToolResult result ) {
        return JsonParser.parseString( text( result ) ).getAsJsonObject();
    }

    private static JsonObject entry( final McpSchema.CallToolResult result, final int index ) {
        return payload( result ).getAsJsonArray( "results" ).get( index ).getAsJsonObject();
    }

    private static void assertSummary( final McpSchema.CallToolResult result,
                                       final int total, final int deleted,
                                       final int skipped, final int failed ) {
        final JsonObject s = payload( result ).getAsJsonObject( "summary" );
        assertEquals( total,   s.get( "total" ).getAsInt(),        "total" );
        assertEquals( deleted, s.get( "deletedCount" ).getAsInt(), "deletedCount" );
        assertEquals( skipped, s.get( "skippedCount" ).getAsInt(), "skippedCount" );
        assertEquals( failed,  s.get( "failedCount" ).getAsInt(),  "failedCount" );
    }

    /** Makes {@code name} an ordinary, deletable, backlink-free page. */
    private void existingPage( final String name ) {
        when( pageManager.getPage( name ) ).thenReturn( mock( Page.class ) );
        when( referenceManager.findReferrers( name ) ).thenReturn( java.util.Set.of() );
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void toolIsNamedDeletePages() {
        assertEquals( "delete_pages", tool.name() );
        assertEquals( "delete_pages", DeletePagesTool.TOOL_NAME );
    }

    @Test
    void definitionRequiresSlugsAndConfirmAndIsMarkedDestructive() {
        final McpSchema.Tool def = tool.definition();

        assertEquals( "delete_pages", def.name() );
        assertEquals( List.of( "slugs", "confirm" ), ToolSchemas.required( def.inputSchema() ) );
        assertTrue( ToolSchemas.properties( def.inputSchema() ).containsKey( "allowWithBacklinks" ) );
        assertTrue( ToolSchemas.properties( def.inputSchema() ).containsKey( "changeNote" ) );
        assertFalse( def.annotations().readOnlyHint(), "delete is not read-only" );
        assertTrue( def.annotations().destructiveHint(), "delete must be flagged destructive" );
    }

    // ------------------------------------------------------------------ argument guards

    @Test
    void missingSlugs_isRejectedWithoutTouchingThePageManager() throws Exception {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "confirm", true ) );

        assertTrue( text( result ).contains( "non-empty array" ), text( result ) );
        verifyNoInteractions( pageManager );
    }

    @Test
    void emptySlugList_isRejected() throws Exception {
        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of(), "confirm", true ) );

        assertTrue( text( result ).contains( "non-empty array" ), text( result ) );
        verifyNoInteractions( pageManager );
    }

    @Test
    void withoutConfirm_nothingIsDeleted() throws Exception {
        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Doomed" ) ) );

        assertTrue( text( result ).contains( "Delete not confirmed" ), text( result ) );
        verify( pageManager, never() ).deletePage( anyString() );
    }

    @Test
    void confirmFalse_nothingIsDeleted() throws Exception {
        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Doomed" ), "confirm", false ) );

        assertTrue( text( result ).contains( "Delete not confirmed" ), text( result ) );
        verify( pageManager, never() ).deletePage( anyString() );
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void confirmedDeleteRemovesThePageAndReportsIt() throws Exception {
        existingPage( "Doomed" );

        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Doomed" ), "confirm", true ) );

        verify( pageManager ).deletePage( "Doomed" );
        assertTrue( entry( result, 0 ).get( "deleted" ).getAsBoolean() );
        assertEquals( "Doomed", entry( result, 0 ).get( "pageName" ).getAsString() );
        assertSummary( result, 1, 1, 0, 0 );
    }

    @Test
    void slugsAreTrimmedBeforeUse() throws Exception {
        existingPage( "Doomed" );

        tool.execute( Map.of( "slugs", List.of( "  Doomed  " ), "confirm", true ) );

        verify( pageManager ).deletePage( "Doomed" );
    }

    @Test
    void aBatchIsProcessedIndependentlyPerPage() throws Exception {
        existingPage( "First" );
        existingPage( "Second" );
        when( pageManager.getPage( "Missing" ) ).thenReturn( null );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "slugs", List.of( "First", "Missing", "Second" ), "confirm", true ) );

        verify( pageManager ).deletePage( "First" );
        verify( pageManager ).deletePage( "Second" );
        verify( pageManager, never() ).deletePage( "Missing" );
        assertSummary( result, 3, 2, 1, 0 );
        assertEquals( "page not found", entry( result, 1 ).get( "error" ).getAsString() );
    }

    // ------------------------------------------------------------------ safety rails

    @Test
    void systemPagesAreRefused() throws Exception {
        when( systemPages.isSystemPage( "Main" ) ).thenReturn( true );

        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Main" ), "confirm", true ) );

        verify( pageManager, never() ).deletePage( anyString() );
        assertTrue( entry( result, 0 ).get( "error" ).getAsString().contains( "system page" ) );
        assertSummary( result, 1, 0, 1, 0 );
    }

    @Test
    void invalidPageNamesAreCountedAsFailuresNotSkips() throws Exception {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "slugs", List.of( "  " ), "confirm", true ) );

        verify( pageManager, never() ).deletePage( anyString() );
        assertFalse( entry( result, 0 ).get( "deleted" ).getAsBoolean() );
        assertSummary( result, 1, 0, 0, 1 );
    }

    @Test
    void pagesWithBacklinksAreSkippedAndTheReferrersAreListed() throws Exception {
        when( pageManager.getPage( "Linked" ) ).thenReturn( mock( Page.class ) );
        when( referenceManager.findReferrers( "Linked" ) )
                .thenReturn( new java.util.LinkedHashSet<>( List.of( "Main", "AgentMemory" ) ) );

        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Linked" ), "confirm", true ) );

        verify( pageManager, never() ).deletePage( anyString() );
        final JsonObject e = entry( result, 0 );
        assertTrue( e.get( "error" ).getAsString().contains( "2 inbound backlinks" ), e.toString() );
        assertTrue( e.get( "error" ).getAsString().contains( "allowWithBacklinks=true" ) );
        final JsonArray backlinks = e.getAsJsonArray( "backlinks" );
        assertEquals( 2, backlinks.size() );
        assertSummary( result, 1, 0, 1, 0 );
    }

    @Test
    void allowWithBacklinksOverridesTheGuard() throws Exception {
        when( pageManager.getPage( "Linked" ) ).thenReturn( mock( Page.class ) );
        when( referenceManager.findReferrers( "Linked" ) ).thenReturn( java.util.Set.of( "Main" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "slugs", List.of( "Linked" ), "confirm", true, "allowWithBacklinks", true ) );

        verify( pageManager ).deletePage( "Linked" );
        assertSummary( result, 1, 1, 0, 0 );
    }

    @Test
    void aPageWithNoReferrersPassesTheBacklinkGuard() throws Exception {
        when( pageManager.getPage( "Orphan" ) ).thenReturn( mock( Page.class ) );
        when( referenceManager.findReferrers( "Orphan" ) ).thenReturn( null );

        tool.execute( Map.of( "slugs", List.of( "Orphan" ), "confirm", true ) );

        verify( pageManager ).deletePage( "Orphan" );
    }

    // ------------------------------------------------------------------ degraded wiring

    @Test
    void withoutASystemPageRegistryTheOtherRailsStillApply() throws Exception {
        tool = new DeletePagesTool( pageManager, referenceManager, null );
        when( pageManager.getPage( "Main" ) ).thenReturn( null );

        final McpSchema.CallToolResult result =
                tool.execute( Map.of( "slugs", List.of( "Main" ), "confirm", true ) );

        assertEquals( "page not found", entry( result, 0 ).get( "error" ).getAsString() );
    }

    @Test
    void withoutAReferenceManagerTheBacklinkGuardIsSkipped() throws Exception {
        tool = new DeletePagesTool( pageManager, null, systemPages );
        when( pageManager.getPage( "Linked" ) ).thenReturn( mock( Page.class ) );

        tool.execute( Map.of( "slugs", List.of( "Linked" ), "confirm", true ) );

        verify( pageManager ).deletePage( "Linked" );
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    void aDeleteFailureIsReportedPerPageAndDoesNotAbortTheBatch() throws Exception {
        existingPage( "Broken" );
        existingPage( "Fine" );
        doThrow( new com.wikantik.api.exceptions.ProviderException( "provider offline" ) )
                .when( pageManager ).deletePage( "Broken" );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "slugs", List.of( "Broken", "Fine" ), "confirm", true ) );

        assertEquals( "provider offline", entry( result, 0 ).get( "error" ).getAsString() );
        assertTrue( entry( result, 1 ).get( "deleted" ).getAsBoolean() );
        assertSummary( result, 2, 1, 0, 1 );
    }
}

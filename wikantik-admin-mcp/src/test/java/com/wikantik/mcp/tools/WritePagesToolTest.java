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

import com.wikantik.mcp.ToolSchemas;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WritePagesToolTest {

    @Test
    void name_isWritePages() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        assertEquals( "write_pages", t.name() );
    }

    @Test
    void definition_requiresPagesArray() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        assertTrue( ToolSchemas.required( t.definition().inputSchema() ).contains( "pages" ) );
    }

    @Test
    void execute_createsNewPages() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "test-agent" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "slug", "NewPageA", "content", "body A", "metadata", Map.of( "cluster", "x" ) ),
                Map.of( "slug", "NewPageB", "content", "body B" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "NewPageA" ) );
        assertTrue( text.contains( "NewPageB" ) );
        assertTrue( text.contains( "\"created\":true" ) );
        assertTrue( text.contains( "\"total\":2" ) );
        assertTrue( text.contains( "\"createdCount\":2" ) );

        verify( helper, times( 2 ) ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
    }

    /** #104: a page carrying an access rule the wiki cannot read is not created; the result cites the reason. */
    /** I1 + M3: the refusal is bounded and carries the total; a refused page leaves no warning in the thread's sinks. */
    @Test
    void execute_anAccessRuleRefusalIsBoundedAndClearsTheWarningSinks() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );
        final List< AccessRuleShapeException.Violation > many = java.util.stream.IntStream.rangeClosed( 1, 150_000 )
                .mapToObj( i -> new AccessRuleShapeException.Violation( AccessRuleShapeException.CODE, "DENY", i, null,
                        "[{deny ", "The access rule on line " + i + " cannot be read." ) ).toList();
        doAnswer( inv -> {
            com.wikantik.api.frontmatter.schema.FrontmatterWarningSink.put( "Locked", List.of(
                    com.wikantik.api.frontmatter.schema.FieldViolation.of( "status",
                            com.wikantik.api.frontmatter.schema.Severity.WARNING, "status.noncanonical", "non-canonical" ) ) );
            com.wikantik.api.content.ContentWarningSink.put( List.of( new com.wikantik.api.content.ContentViolation(
                    "body", com.wikantik.api.frontmatter.schema.Severity.WARNING, "C", "a math warning", null ) ) );
            throw new AccessRuleShapeException( many );
        } ).when( helper ).saveText( eq( "Locked" ), anyString(), any( SaveOptions.class ) );

        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final String text = ( (McpSchema.TextContent) tool.execute( Map.of( "pages", List.of(
                Map.of( "slug", "Locked", "content", "[{deny " ) ) ) ).content().get( 0 ) ).text();

        assertTrue( text.length() < 64 * 1024, "refusal of " + text.length() + " chars" );
        assertTrue( text.contains( "\"total\":150000" ), text.substring( 0, 300 ) );
        assertTrue( com.wikantik.api.frontmatter.schema.FrontmatterWarningSink.drain( "Locked" ).isEmpty() );
        assertTrue( com.wikantik.api.content.ContentWarningSink.drain().isEmpty() );
    }

    @Test
    void execute_refusesAPageWithAnUnreadableAccessRuleCitingTheReason() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );
        doThrow( new AccessRuleShapeException( List.of( new AccessRuleShapeException.Violation(
                AccessRuleShapeException.CODE, "IN_FRONTMATTER", null, "summary", "[{ALLOW view Admin}]",
                "The access rule in the frontmatter field `summary` cannot be read; put it in `code`" ) ) ) )
            .when( helper ).saveText( eq( "Locked" ), anyString(), any( SaveOptions.class ) );

        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final String text = ( (McpSchema.TextContent) tool.execute( Map.of( "pages", List.of(
                Map.of( "slug", "Locked", "content", "---\nsummary: '[{ALLOW view Admin}]'\n---\nbody" ),
                Map.of( "slug", "Fine", "content", "body" ) ) ) ).content().get( 0 ) ).text();

        assertTrue( text.contains( "access rule shape refused" ), text );
        assertTrue( text.contains( "\"code\":\"ACCESS_RULE_SHAPE\"" ), text );
        assertTrue( text.contains( "\"shape\":\"IN_FRONTMATTER\"" ), text );
        assertTrue( text.contains( "\"field\":\"summary\"" ), "a rule in the frontmatter is cited by its field: " + text );
        assertTrue( text.contains( "\"createdCount\":1" ), text );
        assertTrue( text.contains( "\"failedCount\":1" ), text );
    }

    @Test
    void execute_returnsFailureForExistingPage() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Exists" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPage( "Fresh" ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "slug", "Exists", "content", "body" ),
                Map.of( "slug", "Fresh", "content", "body" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "already exists" ) );
        assertTrue( text.contains( "\"createdCount\":1" ) );
        assertTrue( text.contains( "\"failedCount\":1" ) );

        verify( helper, times( 1 ) ).saveText( eq( "Fresh" ), anyString(), any( SaveOptions.class ) );
        verify( helper, never() ).saveText( eq( "Exists" ), anyString(), any( SaveOptions.class ) );
    }

    @Test
    void execute_rejectsEmptyPagesList() {
        final WritePagesTool tool = new WritePagesTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pages", List.of() ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_rejectsMissingPagesKey() {
        final WritePagesTool tool = new WritePagesTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_reportsPerPageFailureForBlankPageName() {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "slug", "", "content", "body" ) ) ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "slug must not be blank" ) );
        assertTrue( text.contains( "\"failedCount\":1" ) );
    }

    @Test
    void execute_reportsPerPageFailureForMissingContent() {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );
        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );

        final Map< String, Object > badPage = new java.util.HashMap<>();
        badPage.put( "slug", "NoBody" );
        // content intentionally absent (null)
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of( badPage ) ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "content must not be null" ) );
        assertTrue( text.contains( "\"failedCount\":1" ) );
    }

    @Test
    void execute_refusesSystemPagesPerPage() throws Exception {
        // Defense in depth: even though shipped system pages already exist (and the
        // "already exists" branch would catch them), an extraPatterns regex match could
        // reserve a name that's never been written. Either way, write_pages must refuse.
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "CSSRibbon" ) ).thenReturn( true );
        when( sys.isSystemPage( "RegularPage" ) ).thenReturn( false );
        when( pm.getPage( "RegularPage" ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm, sys );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "slug", "CSSRibbon",   "content", "/* steal */" ),
                Map.of( "slug", "RegularPage", "content", "fine" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "system page" ),
            "system page refusal must surface in the per-page error" );
        assertTrue( text.contains( "\"createdCount\":1" ) );
        assertTrue( text.contains( "\"failedCount\":1" ) );

        verify( helper, times( 1 ) ).saveText( eq( "RegularPage" ), anyString(), any( SaveOptions.class ) );
        verify( helper, never() ).saveText( eq( "CSSRibbon" ), anyString(), any( SaveOptions.class ) );
    }

    @Test
    void execute_capturesSaveHelperExceptionPerPage() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );
        doThrow( new RuntimeException( "disk full" ) )
            .when( helper ).saveText( anyString(), anyString(), any( SaveOptions.class ) );

        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "slug", "P", "content", "body" ) ) ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "disk full" ),
            "per-page save exception should land in the entry's error field" );
        assertTrue( text.contains( "\"failedCount\":1" ) );
    }

    private static com.google.gson.JsonObject json( final McpSchema.CallToolResult r ) {
        return com.google.gson.JsonParser.parseString( ( (McpSchema.TextContent) r.content().get( 0 ) ).text() )
            .getAsJsonObject();
    }

    @Test
    void execute_aMixedBatchReportsEachOutcomeInOrderWithExactCounts() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( "Exists" ) ).thenReturn( mock( Page.class ) );
        final WritePagesTool tool = new WritePagesTool( mock( PageSaveHelper.class ), pm, null );

        final var json = json( tool.execute( Map.of( "pages", List.of(
            Map.of( "slug", "Fresh", "content", "b" ),
            Map.of( "slug", "Exists", "content", "b" ),
            Map.of( "slug", " ", "content", "b" ),
            Map.of( "slug", "NoBody" ) ) ) ) );

        final var results = json.getAsJsonArray( "results" );
        assertEquals( 4, results.size() );
        assertTrue( results.get( 0 ).getAsJsonObject().get( "created" ).getAsBoolean() );
        assertEquals( "already exists", results.get( 1 ).getAsJsonObject().get( "error" ).getAsString() );
        assertFalse( results.get( 2 ).getAsJsonObject().get( "created" ).getAsBoolean() );
        assertEquals( "content must not be null", results.get( 3 ).getAsJsonObject().get( "error" ).getAsString() );
        final var summary = json.getAsJsonObject( "summary" );
        assertEquals( 4, summary.get( "total" ).getAsInt() );
        assertEquals( 1, summary.get( "createdCount" ).getAsInt() );
        assertEquals( 3, summary.get( "failedCount" ).getAsInt() );
    }

    @Test
    void execute_aPageWithNoMetadataIsSavedWithNullMetadataAndReplaceSemantics() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final WritePagesTool tool = new WritePagesTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );

        tool.execute( Map.of( "pages", List.of( Map.of( "slug", "Plain", "content", "just text" ) ) ) );

        final org.mockito.ArgumentCaptor< SaveOptions > opts = org.mockito.ArgumentCaptor.forClass( SaveOptions.class );
        verify( helper ).saveText( eq( "Plain" ), eq( "just text" ), opts.capture() );
        assertNull( opts.getValue().metadata() );
        assertTrue( opts.getValue().replaceMetadata() );
        assertEquals( "write_pages", opts.getValue().changeNote() );
        assertEquals( "markdown", opts.getValue().markupSyntax() );
        assertEquals( "bot", opts.getValue().author() );
    }

    @Test
    void execute_structuredMetadataIsPassedToTheSaveAndANonObjectMetadataIsIgnored() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final WritePagesTool tool = new WritePagesTool( helper, pm, null );

        tool.execute( Map.of( "pages", List.of(
            Map.of( "slug", "WithMeta", "content", "b", "metadata", Map.of( "cluster", "c1" ) ),
            Map.of( "slug", "BadMeta", "content", "b", "metadata", "not-a-map" ) ) ) );

        final org.mockito.ArgumentCaptor< SaveOptions > withMeta = org.mockito.ArgumentCaptor.forClass( SaveOptions.class );
        verify( helper ).saveText( eq( "WithMeta" ), anyString(), withMeta.capture() );
        assertEquals( "c1", withMeta.getValue().metadata().get( "cluster" ) );
        final org.mockito.ArgumentCaptor< SaveOptions > badMeta = org.mockito.ArgumentCaptor.forClass( SaveOptions.class );
        verify( helper ).saveText( eq( "BadMeta" ), anyString(), badMeta.capture() );
        assertNull( badMeta.getValue().metadata() );
    }

    @Test
    void execute_staleWarningsFromAnEarlierSaveAreNotAttachedToACreatedPage() {
        final PageManager pm = mock( PageManager.class );
        com.wikantik.api.frontmatter.schema.FrontmatterWarningSink.put( "Fresh", List.of(
            com.wikantik.api.frontmatter.schema.FieldViolation.of( "x", com.wikantik.api.frontmatter.schema.Severity.WARNING,
                "x", "stale" ) ) );
        final WritePagesTool tool = new WritePagesTool( mock( PageSaveHelper.class ), pm, null );

        final String text = ( (McpSchema.TextContent) tool.execute( Map.of( "pages", List.of(
            Map.of( "slug", "Fresh", "content", "b" ) ) ) ).content().get( 0 ) ).text();

        assertFalse( text.contains( "stale" ), text );
        assertFalse( text.contains( "frontmatterWarnings" ), text );
    }

    @Test
    void execute_saveWarningsOfTheCreatedPageAreAttachedToItsEntry() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        org.mockito.Mockito.doAnswer( inv -> {
            com.wikantik.api.frontmatter.schema.FrontmatterWarningSink.put( "Fresh", List.of(
                com.wikantik.api.frontmatter.schema.FieldViolation.of( "summary",
                    com.wikantik.api.frontmatter.schema.Severity.WARNING, "summary.length", "too short" ) ) );
            return null;
        } ).when( helper ).saveText( eq( "Fresh" ), anyString(), any( SaveOptions.class ) );

        final String text = ( (McpSchema.TextContent) new WritePagesTool( helper, pm, null ).execute( Map.of( "pages",
            List.of( Map.of( "slug", "Fresh", "content", "b" ) ) ) ).content().get( 0 ) ).text();

        assertTrue( text.contains( "frontmatterWarnings" ) && text.contains( "too short" ), text );
    }
}

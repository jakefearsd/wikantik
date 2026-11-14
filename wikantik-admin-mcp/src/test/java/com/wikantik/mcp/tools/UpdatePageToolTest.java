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
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdatePageToolTest {

    @Test
    void name_isUpdatePage() {
        assertEquals( "update_page",
            new UpdatePageTool( mock( PageSaveHelper.class ), mock( PageManager.class ), null ).name() );
    }

    @Test
    void definition_requiresOnlySlugAndHash_contentOptional() {
        final UpdatePageTool t = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final var req = ToolSchemas.required( t.definition().inputSchema() );
        assertTrue( req.contains( "slug" ) );
        assertTrue( req.contains( "expectedContentHash" ) );
        // content is now OPTIONAL — a metadata-only edit must not require re-sending the body.
        assertFalse( req.contains( "content" ) );
    }

    @Test
    void execute_updatesWhenHashMatches() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );

        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P",
            "content", "new body",
            "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertTrue( text.contains( "\"newContentHash\"" ) );
        verify( helper, times( 1 ) ).saveText(
            eq( "P" ), eq( "new body" ), any( SaveOptions.class ) );
    }

    /** #104: an access rule the wiki cannot read is refused, citing its shape, line and how to write it instead. */
    @Test
    void execute_refusesAnUnreadableAccessRuleCitingTheReason() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        doThrow( new AccessRuleShapeException( List.of( new AccessRuleShapeException.Violation(
                AccessRuleShapeException.CODE, "DENY", 3, null, "[{DENY view Bob}]",
                "The access rule on line 3 ([{DENY view Bob}]) cannot be read: access rules only allow: there is no DENY "
                        + "(DENY). To restrict the page, write `[{ALLOW …}]`; to show the syntax, put it in `code`." ) ) ) )
            .when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final McpSchema.CallToolResult result = new UpdatePageTool( helper, pm, null ).execute( Map.of(
            "slug", "P", "content", "body\n\n[{DENY view Bob}]\n", "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ), text );
        assertTrue( text.contains( "access rule shape refused" ), text );
        assertTrue( text.contains( "\"code\":\"ACCESS_RULE_SHAPE\"" ), text );
        assertTrue( text.contains( "\"shape\":\"DENY\"" ), text );
        assertTrue( text.contains( "\"line\":3" ), text );
        assertTrue( text.contains( "put it in `code`" ), text );
    }

    private static List< AccessRuleShapeException.Violation > denyViolations( final int n ) {
        return java.util.stream.IntStream.rangeClosed( 1, n ).mapToObj( i -> new AccessRuleShapeException.Violation(
                AccessRuleShapeException.CODE, "DENY", i, null, "[{deny ", "The access rule on line " + i + " cannot be read." ) )
                .toList();
    }

    /** I1: the refusal cites the first violations and the total, however many the text holds. */
    @Test
    void execute_anAccessRuleRefusalIsBoundedAndCarriesTheTotal() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );
        doThrow( new AccessRuleShapeException( denyViolations( 150_000 ) ) )
            .when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final String text = ( (McpSchema.TextContent) new UpdatePageTool( helper, pm, null ).execute( Map.of(
            "slug", "P", "content", "[{deny ", "expectedContentHash", currentHash ) ).content().get( 0 ) ).text();

        assertTrue( text.length() < 64 * 1024, "refusal of " + text.length() + " chars" );
        assertTrue( text.contains( "\"total\":150000" ), text.substring( 0, 300 ) );
        assertFalse( text.contains( "line 51 " ), "only the first 50 violations are cited" );
    }

    /** M3: a refused write leaves no warning behind in the thread's sinks, as the sibling refusals do. */
    @Test
    void execute_anAccessRuleRefusalClearsTheWarningSinks() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );
        doAnswer( inv -> {
            FrontmatterWarningSink.put( "P", List.of( FieldViolation.of(
                "status", Severity.WARNING, "status.noncanonical", "non-canonical status" ) ) );
            com.wikantik.api.content.ContentWarningSink.put( List.of( new com.wikantik.api.content.ContentViolation(
                "body", com.wikantik.api.frontmatter.schema.Severity.WARNING, "C", "a math warning", null ) ) );
            throw new AccessRuleShapeException( denyViolations( 1 ) );
        } ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        new UpdatePageTool( helper, pm, null ).execute( Map.of(
            "slug", "P", "content", "[{DENY view Bob}]", "expectedContentHash", currentHash ) );

        assertTrue( FrontmatterWarningSink.drain( "P" ).isEmpty(), "frontmatter warnings left in the sink" );
        assertTrue( com.wikantik.api.content.ContentWarningSink.drain().isEmpty(), "content warnings left in the sink" );
    }

    @Test
    void execute_refusesOnFrontmatterValidationErrorCitingViolations() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        doThrow( new FrontmatterValidationException( List.of( FieldViolation.of(
                "audience", Severity.ERROR, "audience.enum.invalid",
                "`audience: \"robots\"` is not allowed. Allowed values: humans, agents, both." ) ) ) )
            .when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P", "content", "new body", "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "frontmatter validation failed" ) );
        assertTrue( text.contains( "audience" ) );
    }

    @Test
    void execute_surfacesFrontmatterWarningsOnSuccess() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        // The save filter stashes a non-blocking warning, keyed by the page being saved, during saveText.
        doAnswer( inv -> {
            FrontmatterWarningSink.put( "P", List.of( FieldViolation.of(
                "status", Severity.WARNING, "status.noncanonical", "non-canonical status" ) ) );
            return null;
        } ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P", "content", "new body", "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertTrue( text.contains( "frontmatterWarnings" ) );
        assertTrue( text.contains( "status" ) );
    }

    @Test
    void execute_doesNotSurfaceAnotherPagesFrontmatterWarnings() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );
        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        // Saving page "P" triggers a NESTED save of a different page (e.g. a regenerated index),
        // whose validation stashes a foreign over-length-summary warning. Page "P" has none of its
        // own. The tool must drain only its own page's warnings — never the nested page's.
        // Pre-fix (a flat per-thread sink), the foreign "188" leaked into P's response.
        doAnswer( inv -> {
            FrontmatterWarningSink.put( "P", List.of() );
            FrontmatterWarningSink.put( "GeneratedIndex", List.of( FieldViolation.of(
                "summary", Severity.WARNING, "summary.length",
                "'summary' should be 50–160 characters (is 188)." ) ) );
            return null;
        } ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P", "content", "new body", "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertFalse( text.contains( "188" ), "must not leak a nested page's summary-length warning" );
        assertFalse( text.contains( "frontmatterWarnings" ), "page P has no warnings of its own" );
    }

    @Test
    void execute_preservesExistingFrontmatterWhenBodyOnlyContent() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: My Title\ntype: article\ncluster: my-cluster\n"
            + "tags:\n- a\n- b\nsummary: old summary text here\n---\n# Body\n\noriginal text";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );
        final String hash = McpToolUtils.computeContentHash( current );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        // Body-only content + a single-field metadata edit must NOT drop the other fields.
        tool.execute( Map.of( "slug", "P", "content", "# Body\n\nnew text",
            "metadata", Map.of( "summary", "a brand new summary that is over fifty characters long" ),
            "expectedContentHash", hash ) );

        verify( helper ).saveText( eq( "P" ), any(), opts.capture() );
        final Map< String, Object > saved = opts.getValue().metadata();
        assertEquals( "My Title", saved.get( "title" ), "title preserved" );
        assertEquals( "article", saved.get( "type" ), "type preserved" );
        assertEquals( "my-cluster", saved.get( "cluster" ), "cluster preserved" );
        assertEquals( List.of( "a", "b" ), saved.get( "tags" ), "tags preserved" );
        assertEquals( "a brand new summary that is over fifty characters long",
            saved.get( "summary" ), "summary overridden" );
        assertTrue( opts.getValue().replaceMetadata() );
    }

    @Test
    void execute_metadataOnlyEditKeepsBody() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 4 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: T\ncluster: c\n---\n# Heading\n\nthe original body line";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );
        final String hash = McpToolUtils.computeContentHash( current );

        final ArgumentCaptor< String > body = ArgumentCaptor.forClass( String.class );
        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        // No "content" key at all — a metadata-only edit.
        tool.execute( Map.of( "slug", "P",
            "metadata", Map.of( "summary", "added summary that comfortably exceeds fifty chars here" ),
            "expectedContentHash", hash ) );

        verify( helper ).saveText( eq( "P" ), body.capture(), opts.capture() );
        assertTrue( body.getValue().contains( "the original body line" ), "body unchanged" );
        assertEquals( "T", opts.getValue().metadata().get( "title" ), "title preserved" );
        assertEquals( "added summary that comfortably exceeds fifty chars here",
            opts.getValue().metadata().get( "summary" ), "summary added" );
    }

    // --- removeKeys: retiring a frontmatter field -------------------------------------------
    // The merge is otherwise unconditional (existing frontmatter is always the base), so
    // without this there is no way to retire a field through the MCP surface at all.

    @Test
    void execute_removeKeysDeletesTheField() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: T\ncluster: c\nhubs:\n- MLHub\n---\nbody";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.execute( Map.of( "slug", "P", "removeKeys", List.of( "hubs" ),
            "expectedContentHash", McpToolUtils.computeContentHash( current ) ) );

        verify( helper ).saveText( eq( "P" ), anyString(), opts.capture() );
        assertFalse( opts.getValue().metadata().containsKey( "hubs" ), "hubs must be gone" );
        assertEquals( "T", opts.getValue().metadata().get( "title" ), "untouched fields preserved" );
        assertEquals( "c", opts.getValue().metadata().get( "cluster" ) );
    }

    /** Removal must win over the merge, so one call can both set a field and retire another. */
    @Test
    void execute_removeKeysAppliesAfterTheMetadataMerge() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: T\ncluster: c\nhubs:\n- MLHub\n---\nbody";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.execute( Map.of( "slug", "P",
            "metadata", Map.of( "cluster", List.of( "c", "second" ) ),
            "removeKeys", List.of( "hubs" ),
            "expectedContentHash", McpToolUtils.computeContentHash( current ) ) );

        verify( helper ).saveText( eq( "P" ), anyString(), opts.capture() );
        assertFalse( opts.getValue().metadata().containsKey( "hubs" ) );
        assertEquals( List.of( "c", "second" ), opts.getValue().metadata().get( "cluster" ) );
    }

    /**
     * canonical_id is the page's rename-stable identity. Removing it would make the save-time
     * filter mint a fresh one, silently orphaning the page's history and every citation
     * pinned to it — so the tool refuses rather than letting an agent do that by accident.
     */
    @Test
    void execute_removeKeysRefusesCanonicalId() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ncanonical_id: 01HAA000000000000000000000\ntitle: T\n---\nbody";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "slug", "P",
            "removeKeys", List.of( "canonical_id" ),
            "expectedContentHash", McpToolUtils.computeContentHash( current ) ) );

        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "canonical_id" ), "the refusal must name the key: " + text );
        verify( helper, never() ).saveText( anyString(), anyString(), any() );
    }

    /** Retiring a field the page never had is a no-op, not an error. */
    @Test
    void execute_removeKeysIgnoresAbsentFields() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: T\n---\nbody";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.execute( Map.of( "slug", "P", "removeKeys", List.of( "hubs" ),
            "expectedContentHash", McpToolUtils.computeContentHash( current ) ) );

        verify( helper ).saveText( eq( "P" ), anyString(), opts.capture() );
        assertEquals( "T", opts.getValue().metadata().get( "title" ) );
    }

    /** removeKeys alone is a legitimate edit — it must not trip the "nothing to do" guard. */
    @Test
    void execute_removeKeysAloneIsAValidEdit() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        final String current = "---\ntitle: T\nhubs:\n- MLHub\n---\nbody";
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( current );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.execute( Map.of( "slug", "P", "removeKeys", List.of( "hubs" ),
            "expectedContentHash", McpToolUtils.computeContentHash( current ) ) );

        verify( helper ).saveText( eq( "P" ), anyString(), any() );
    }

    @Test
    void execute_errorsWhenNeitherContentNorMetadata() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "---\ntitle: T\n---\nbody" );
        final String hash = McpToolUtils.computeContentHash( "---\ntitle: T\n---\nbody" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P", "expectedContentHash", hash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "nothing to update" ) );
        verify( helper, never() ).saveText( anyString(), any(), any( SaveOptions.class ) );
    }

    @Test
    void execute_newContentHashIsHashOfPersistedTextNotReconstruction() throws Exception {
        // The save pipeline (StructuralSpinePageFilter injecting canonical_id,
        // frontmatter/line-ending normalization, etc.) can rewrite the stored text so
        // it differs from any tool-side reconstruction. The returned newContentHash
        // must be the hash of the ACTUAL persisted text — identical to what a later
        // read_page returns — so the agent can chain edits without a read-after-write.
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        // 1st getPureText: the pre-save optimistic-lock check. 2nd: the post-save
        // re-read of the persisted text (which the save pipeline rewrote).
        final String persisted = "---\ncanonical_id: 01ABCDEF\ntitle: P\n---\n\nnew body\n";
        when( pm.getPureText( eq( "P" ), anyInt() ) )
            .thenReturn( "current body", persisted );

        final String currentHash = McpToolUtils.computeContentHash( "current body" );
        final String persistedHash = McpToolUtils.computeContentHash( persisted );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P",
            "content", "new body",
            "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ), text );
        assertTrue( text.contains( "\"newContentHash\":\"" + persistedHash + "\"" ),
            "newContentHash must equal the hash of the actual persisted text, not a "
            + "reconstruction of the submitted body: " + text );
    }

    @Test
    void execute_failsOnHashMismatch() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "P" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "drift body" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P",
            "content", "new body",
            "expectedContentHash", "staleHashValue" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "hash mismatch" ) );
        assertTrue( text.contains( "\"currentHash\"" ) );
        // McpServerCritique2026 #3: hash-mismatch responses must include the
        // current page state so the agent can rebase without re-reading.
        assertTrue( text.contains( "\"latestContent\"" ),
            "hash-mismatch response should carry latestContent: " + text );
        assertTrue( text.contains( "drift body" ),
            "latestContent should be the actual current page text: " + text );
        verify( helper, never() ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
    }

    @Test
    void execute_failsWhenPageDoesNotExist() {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Missing" ) ).thenReturn( null );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "Missing",
            "content", "body",
            "expectedContentHash", "doesNotMatter" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "not found" ) );
    }

    @Test
    void execute_returnsErrorOnBlankPageName() {
        final UpdatePageTool tool = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "",
            "content", "body",
            "expectedContentHash", "h" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "slug is required" ) );
    }

    @Test
    void execute_returnsErrorOnBlankHash() {
        final UpdatePageTool tool = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P",
            "content", "body",
            "expectedContentHash", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "expectedContentHash required" ) );
    }

    @Test
    void execute_refusesSystemPage() throws Exception {
        // Defends shipped CSS / menu / help pages: even if the agent has a valid
        // contentHash for a system page, the tool must refuse to write.
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "CSSRibbon" ) ).thenReturn( true );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, sys );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "CSSRibbon",
            "content", ".malicious { display: none }",
            "expectedContentHash", "anything" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "system page" ),
            "refusal must explain why so the agent doesn't retry mindlessly" );
        verify( helper, never() ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
        // Refusal happens before the page lookup so we never hit pageManager.getPage either.
        verify( pm, never() ).getPage( anyString() );
    }

    @Test
    void execute_allowsMcpEditableSystemPage() throws Exception {
        // About is a system page but is MCP-editable (editorial default content),
        // so update_page must NOT refuse it — it proceeds past the system-page guard.
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "About" ) ).thenReturn( true );
        when( sys.isMcpEditable( "About" ) ).thenReturn( true );
        // Page absent → tool returns "not found", proving it got past the system-page guard.
        when( pm.getPage( "About" ) ).thenReturn( null );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, sys );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "About",
            "content", "# About\n\nupdated",
            "expectedContentHash", "anything" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "system page" ),
            "an MCP-editable system page must not be refused" );
        assertTrue( text.contains( "not found" ),
            "execution proceeds past the system-page guard to the page lookup" );
        verify( pm ).getPage( "About" );
    }

    @Test
    void execute_returnsErrorOnRuntimeExceptionFromPageManager() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( anyString() ) ).thenThrow( new RuntimeException( "DB offline" ) );
        final UpdatePageTool tool = new UpdatePageTool(
            mock( PageSaveHelper.class ), pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "slug", "P",
            "content", "body",
            "expectedContentHash", "h" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}

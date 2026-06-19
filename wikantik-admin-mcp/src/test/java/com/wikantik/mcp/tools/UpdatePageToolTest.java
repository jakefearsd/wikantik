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

import com.wikantik.api.core.Page;
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
        final var req = t.definition().inputSchema().required();
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
            "pageName", "P",
            "content", "new body",
            "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertTrue( text.contains( "\"newContentHash\"" ) );
        verify( helper, times( 1 ) ).saveText(
            eq( "P" ), eq( "new body" ), any( SaveOptions.class ) );
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
            "pageName", "P", "content", "new body", "expectedContentHash", currentHash ) );

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

        // The save filter stashes a non-blocking warning during saveText for the tool to surface.
        doAnswer( inv -> {
            FrontmatterWarningSink.put( List.of( FieldViolation.of(
                "status", Severity.WARNING, "status.noncanonical", "non-canonical status" ) ) );
            return null;
        } ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P", "content", "new body", "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertTrue( text.contains( "frontmatterWarnings" ) );
        assertTrue( text.contains( "status" ) );
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
        tool.execute( Map.of( "pageName", "P", "content", "# Body\n\nnew text",
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
        tool.execute( Map.of( "pageName", "P",
            "metadata", Map.of( "summary", "added summary that comfortably exceeds fifty chars here" ),
            "expectedContentHash", hash ) );

        verify( helper ).saveText( eq( "P" ), body.capture(), opts.capture() );
        assertTrue( body.getValue().contains( "the original body line" ), "body unchanged" );
        assertEquals( "T", opts.getValue().metadata().get( "title" ), "title preserved" );
        assertEquals( "added summary that comfortably exceeds fifty chars here",
            opts.getValue().metadata().get( "summary" ), "summary added" );
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
            "pageName", "P", "expectedContentHash", hash ) );

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
            "pageName", "P",
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
            "pageName", "P",
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
            "pageName", "Missing",
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
            "pageName", "",
            "content", "body",
            "expectedContentHash", "h" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "pageName is required" ) );
    }

    @Test
    void execute_returnsErrorOnBlankHash() {
        final UpdatePageTool tool = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P",
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
            "pageName", "CSSRibbon",
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
    void execute_returnsErrorOnRuntimeExceptionFromPageManager() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( anyString() ) ).thenThrow( new RuntimeException( "DB offline" ) );
        final UpdatePageTool tool = new UpdatePageTool(
            mock( PageSaveHelper.class ), pm, null );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P",
            "content", "body",
            "expectedContentHash", "h" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}

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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubReferenceManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bake-in guard for live values: every admin-MCP write path that reads a page and saves it again must store the
 * summary and body tokens exactly as authored, never an expanded value (a silent content-destroying defect).
 */
class LiveValuesBakeInTest {

    private static final String SUMMARY = "{{wiki:articles|round=100}} articles, {{wiki:clusters}} clusters, kept current";
    private static final String BODY = "Intro with {{wiki:pages}} pages and {{wiki:cluster.a/b.pages|else=few}}.\n\n"
            + "```\n{{wiki:articles}}\n```\n";
    private static final String RAW = "---\ntype: article\nsummary: \"" + SUMMARY + "\"\n---\n" + BODY;

    private static void assertTokensIntact( final Map< String, Object > metadata, final String body ) {
        assertEquals( SUMMARY, metadata.get( "summary" ) );
        assertEquals( BODY, body.replaceFirst( "^\n+", "" ).replaceFirst( "\n*$", "\n" ) );
    }

    @Test
    void updatePageMetadataOnlyEditKeepsTokens() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( RAW );

        new UpdatePageTool( helper, pm, null ).execute( Map.of( "slug", "P",
                "metadata", Map.of( "tags", List.of( "x" ) ),
                "expectedContentHash", McpToolUtils.computeContentHash( RAW ) ) );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final ArgumentCaptor< String > body = ArgumentCaptor.forClass( String.class );
        verify( helper ).saveText( eq( "P" ), body.capture(), opts.capture() );
        assertTokensIntact( opts.getValue().metadata(), body.getValue() );
    }

    @Test
    void writePagesOfReadBackTextKeepsTokens() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );

        new WritePagesTool( helper, pm, null ).execute( Map.of( "pages",
                List.of( Map.of( "slug", "Copy", "content", RAW ) ) ) );

        final ArgumentCaptor< SaveOptions > opts = ArgumentCaptor.forClass( SaveOptions.class );
        final ArgumentCaptor< String > body = ArgumentCaptor.forClass( String.class );
        verify( helper ).saveText( eq( "Copy" ), body.capture(), opts.capture() );
        assertTokensIntact( opts.getValue().metadata(), body.getValue() );
    }

    @Test
    void markPageVerifiedKeepsTokens() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page page = mock( Page.class );
        when( pm.getPage( "P" ) ).thenReturn( page );
        when( pm.getPureText( page ) ).thenReturn( RAW );
        final MarkPageVerifiedTool tool = new MarkPageVerifiedTool( helper, pm, null );
        tool.setDefaultAuthor( "bot" );

        tool.execute( Map.of( "slugs", List.of( "P" ) ) );

        final ArgumentCaptor< String > saved = ArgumentCaptor.forClass( String.class );
        verify( helper ).saveText( eq( "P" ), saved.capture(), any( SaveOptions.class ) );
        final ParsedPage parsed = FrontmatterParser.parse( saved.getValue() );
        assertTokensIntact( parsed.metadata(), parsed.body() );
        assertEquals( "bot", parsed.metadata().get( "verified_by" ) );
    }

    @Test
    void verifyPagesReadsRawAndWritesNothing() throws Exception {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "P", RAW );
        final StubReferenceManager refs = new StubReferenceManager();
        refs.addReferences( "P", Set.of() );

        new VerifyPagesTool( pm, refs ).execute( Map.of( "slugs", List.of( "P" ) ) );

        assertEquals( RAW, pm.getPureText( "P", -1 ) );
    }
}

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

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubPageSaveHelper;
import com.wikantik.test.StubSystemPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end MCP test exercising import, read, and query round-trip with realistic
 * article content and YAML frontmatter metadata.
 */
class FrontmatterRoundTripTest {

    private StubPageManager pm;
    private ImportContentTool importTool;
    private ReadPageTool readTool;
    private QueryMetadataTool queryTool;
    private final Gson gson = new Gson();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        final var spr = new StubSystemPageRegistry();
        importTool = new ImportContentTool( new StubPageSaveHelper( pm ), pm );
        readTool = new ReadPageTool( pm, spr );
        queryTool = new QueryMetadataTool( pm );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testImportArticleWithMetadataThenReadBack() throws IOException {
        final String pageContent = """
                ---
                type: intelligence-report
                title: "Iran Update Evening Special Report, March 12, 2026"
                author: Christopher Solomon
                source: Institute for the Study of War
                date: "2026-03-12"
                region: Middle East
                tags:
                - Iran
                - Mojtaba Khamenei
                - supreme-leader
                - geopolitics
                ---
                Iranian Supreme Leader Mojtaba Khamenei issued his first public statement on \
                March 12, 2026. This evening special report from ISW's Christopher Solomon analyzes \
                the implications of the statement for regional stability and Iranian domestic politics.""";

        Files.writeString( tempDir.resolve( "IranUpdateMarch2026.md" ), pageContent, StandardCharsets.UTF_8 );

        final McpSchema.CallToolResult importResult = importTool.execute(
                Map.of( "directory", tempDir.toString(), "changeNote", "Initial article import via MCP" ) );
        assertFalse( importResult.isError() );

        // Read the page back via ReadPageTool
        final McpSchema.CallToolResult readResult = readTool.execute( Map.of( "pageName", "IranUpdateMarch2026" ) );
        final Map< String, Object > readData = parseResult( readResult );

        assertEquals( true, readData.get( "exists" ) );
        assertEquals( "IranUpdateMarch2026", readData.get( "pageName" ) );
        assertTrue( readData.get( "content" ).toString().contains( "Mojtaba Khamenei" ) );

        final Map< String, Object > readMeta = ( Map< String, Object > ) readData.get( "metadata" );
        assertEquals( "intelligence-report", readMeta.get( "type" ) );
        assertEquals( "Christopher Solomon", readMeta.get( "author" ) );
        assertEquals( "2026-03-12", readMeta.get( "date" ) );
        assertEquals( "Middle East", readMeta.get( "region" ) );

        final List< String > tags = ( List< String > ) readMeta.get( "tags" );
        assertTrue( tags.contains( "Iran" ) );
        assertTrue( tags.contains( "Mojtaba Khamenei" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryArticleByTypeAndTags() {
        // Store two articles with different types
        pm.savePage( "IranReport",
                "---\ntype: intelligence-report\nregion: Middle East\ntags: [Iran, geopolitics]\n---\n"
                        + "Iranian Supreme Leader Mojtaba Khamenei issued his first public statement." );
        pm.savePage( "TechArticle",
                "---\ntype: article\nregion: Technology\ntags: [AI, software]\n---\n"
                        + "An article about technology." );
        pm.savePage( "PlainPage", "A page with no frontmatter at all." );

        // Query by type shortcut
        final McpSchema.CallToolResult typeResult = queryTool.execute(
                Map.of( "type", "intelligence-report" ) );
        final Map< String, Object > typeData = parseResult( typeResult );
        final List< Map< String, Object > > typePages = ( List< Map< String, Object > > ) typeData.get( "pages" );
        assertEquals( 1, typePages.size() );
        assertEquals( "IranReport", typePages.get( 0 ).get( "name" ) );

        // Query by tag value
        final Map< String, Object > tagArgs = new HashMap<>();
        tagArgs.put( "field", "tags" );
        tagArgs.put( "value", "Iran" );
        final McpSchema.CallToolResult tagResult = queryTool.execute( tagArgs );
        final Map< String, Object > tagData = parseResult( tagResult );
        final List< Map< String, Object > > tagPages = ( List< Map< String, Object > > ) tagData.get( "pages" );
        assertEquals( 1, tagPages.size() );
        assertEquals( "IranReport", tagPages.get( 0 ).get( "name" ) );

        // Query by region field existence — should find both articles
        final McpSchema.CallToolResult regionResult = queryTool.execute( Map.of( "field", "region" ) );
        final Map< String, Object > regionData = parseResult( regionResult );
        final List< Map< String, Object > > regionPages = ( List< Map< String, Object > > ) regionData.get( "pages" );
        assertEquals( 2, regionPages.size() );
    }

    @Test
    void testStoredFrontmatterParsesCorrectly() throws IOException {
        final String pageContent = "---\ntype: intelligence-report\ntags:\n- Iran\n- supreme-leader\n---\nReport body content.";

        Files.writeString( tempDir.resolve( "IranFmTest.md" ), pageContent, StandardCharsets.UTF_8 );
        importTool.execute( Map.of( "directory", tempDir.toString() ) );

        // Verify the raw stored text has proper frontmatter structure
        final String stored = pm.getPureText( "IranFmTest", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );

        assertEquals( "intelligence-report", parsed.metadata().get( "type" ) );
        assertTrue( parsed.body().contains( "Report body content." ) );
        assertFalse( parsed.body().contains( "---" ), "Frontmatter delimiters should not appear in body" );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > parseResult( final McpSchema.CallToolResult result ) {
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }
}

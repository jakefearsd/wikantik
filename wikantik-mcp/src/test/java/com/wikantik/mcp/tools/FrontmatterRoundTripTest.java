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
import com.wikantik.TestEngine;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end MCP test exercising write → read → query round-trip with realistic
 * article content and YAML frontmatter metadata.
 */
class FrontmatterRoundTripTest {

    private TestEngine engine;
    private WritePageTool writeTool;
    private ReadPageTool readTool;
    private QueryMetadataTool queryTool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        final var spr = engine.getManager( com.wikantik.content.SystemPageRegistry.class );
        writeTool = new WritePageTool( engine, spr );
        final PageManager pm = engine.getManager( PageManager.class );
        readTool = new ReadPageTool( pm, spr );
        queryTool = new QueryMetadataTool( pm );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testWriteArticleWithMetadataThenReadBack() {
        final Map< String, Object > metadata = new HashMap<>();
        metadata.put( "type", "intelligence-report" );
        metadata.put( "title", "Iran Update Evening Special Report, March 12, 2026" );
        metadata.put( "author", "Christopher Solomon" );
        metadata.put( "source", "Institute for the Study of War" );
        metadata.put( "date", "2026-03-12" );
        metadata.put( "region", "Middle East" );
        metadata.put( "tags", List.of( "Iran", "Mojtaba Khamenei", "supreme-leader", "geopolitics" ) );

        final String body = "Iranian Supreme Leader Mojtaba Khamenei issued his first public statement on "
                + "March 12, 2026. This evening special report from ISW's Christopher Solomon analyzes "
                + "the implications of the statement for regional stability and Iranian domestic politics.";

        final Map< String, Object > writeArgs = new HashMap<>();
        writeArgs.put( "pageName", "IranUpdateMarch2026" );
        writeArgs.put( "content", body );
        writeArgs.put( "metadata", metadata );
        writeArgs.put( "changeNote", "Initial article import via MCP" );

        final McpSchema.CallToolResult writeResult = writeTool.execute( writeArgs );
        final Map< String, Object > writeData = parseResult( writeResult );
        assertEquals( true, writeData.get( "success" ) );

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
    void testQueryArticleByTypeAndTags() throws Exception {
        // Store two articles with different types
        engine.saveText( "IranReport",
                "---\ntype: intelligence-report\nregion: Middle East\ntags: [Iran, geopolitics]\n---\n"
                        + "Iranian Supreme Leader Mojtaba Khamenei issued his first public statement." );
        engine.saveText( "TechArticle",
                "---\ntype: article\nregion: Technology\ntags: [AI, software]\n---\n"
                        + "An article about technology." );
        engine.saveText( "PlainPage", "A page with no frontmatter at all." );

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
    void testStoredFrontmatterParsesCorrectly() {
        final Map< String, Object > metadata = new HashMap<>();
        metadata.put( "type", "intelligence-report" );
        metadata.put( "tags", List.of( "Iran", "supreme-leader" ) );

        final Map< String, Object > writeArgs = new HashMap<>();
        writeArgs.put( "pageName", "IranFmTest" );
        writeArgs.put( "content", "Report body content." );
        writeArgs.put( "metadata", metadata );

        writeTool.execute( writeArgs );

        // Verify the raw stored text has proper frontmatter structure
        final String stored = engine.getManager( PageManager.class ).getPureText( "IranFmTest", -1 );
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

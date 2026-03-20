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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edge case and error handling integration tests for MCP tools.
 */
public class McpEdgeCasesIT extends WithMcpTestSetup {

    @Test
    public void readPageWithEmptyString() {
        final Map< String, Object > result = mcp.readPage( "" );
        // Should handle gracefully: either exists=false or an error, but not crash
        Assertions.assertNotNull( result );
    }

    @Test
    public void writePageWithVeryLongContent() {
        final String pageName = uniquePageName( "EdgeLong" );
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 1000; i++ ) {
            sb.append( "Line " ).append( i ).append( ": This is padding content to reach 100KB. " );
            sb.append( "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n" );
        }
        final String longContent = sb.toString();
        Assertions.assertTrue( longContent.length() > 100_000, "Content should exceed 100KB" );

        final Map< String, Object > writeResult = mcp.writePage( pageName, longContent );
        Assertions.assertEquals( true, writeResult.get( "success" ) );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        final String readContent = McpTestClient.normalizeCrlf( ( String ) readResult.get( "content" ) );
        Assertions.assertTrue( readContent.contains( "Line 0:" ), "Start of content should be preserved" );
        Assertions.assertTrue( readContent.contains( "Line 999:" ), "End of content should be preserved" );
    }

    @Test
    public void writePageWithSpecialMarkdown() {
        final String pageName = uniquePageName( "EdgeMarkup" );
        final String markdownContent = """
                This has [links]() and [Named Link](Target).

                ```
                preformatted code block
                int x = 42;
                ```

                | Header 1 | Header 2 |
                |----------|----------|
                | Cell 1   | Cell 2   |

                ---

                **bold** and *italic* and `monospace`""";

        final Map< String, Object > result = mcp.writePage( pageName, markdownContent );
        Assertions.assertEquals( true, result.get( "success" ) );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        final String readContent = McpTestClient.normalizeCrlf( ( String ) readResult.get( "content" ) );
        Assertions.assertTrue( readContent.contains( "[links]()" ), "Markdown links should be preserved" );
        Assertions.assertTrue( readContent.contains( "```" ), "Fenced code blocks should be preserved" );
        Assertions.assertTrue( readContent.contains( "| Header 1" ), "Tables should be preserved" );
    }

    @Test
    public void unicodeInPageName() {
        final String pageName = uniquePageName( "Edg\u00e9Caf\u00e9" );
        final Map< String, Object > result = mcp.writePage( pageName, "Unicode page name test" );
        Assertions.assertEquals( true, result.get( "success" ) );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        Assertions.assertEquals( true, readResult.get( "exists" ) );
    }

    @Test
    public void frontmatterWithNestedObjects() {
        final String pageName = uniquePageName( "EdgeNested" );
        final Map< String, Object > nested = new LinkedHashMap<>();
        nested.put( "key1", "value1" );
        nested.put( "key2", "value2" );

        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "complex" );
        metadata.put( "config", nested );

        mcp.writePage( pageName, "Nested metadata body", metadata );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMeta = ( Map< String, Object > ) readResult.get( "metadata" );
        Assertions.assertEquals( "complex", readMeta.get( "type" ) );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readConfig = ( Map< String, Object > ) readMeta.get( "config" );
        Assertions.assertNotNull( readConfig, "Nested config should be preserved" );
        Assertions.assertEquals( "value1", readConfig.get( "key1" ) );
        Assertions.assertEquals( "value2", readConfig.get( "key2" ) );
    }

    @Test
    public void frontmatterWithSpecialYamlCharacters() {
        final String pageName = uniquePageName( "EdgeYaml" );
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "description", "Contains: colons and \"quotes\" and 'single quotes'" );
        metadata.put( "note", "Value with # hash" );

        mcp.writePage( pageName, "YAML special chars body", metadata );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMeta = ( Map< String, Object > ) readResult.get( "metadata" );
        Assertions.assertTrue( ( ( String ) readMeta.get( "description" ) ).contains( "colons" ) );
        Assertions.assertTrue( ( ( String ) readMeta.get( "note" ) ).contains( "# hash" ) );
    }

    @Test
    public void readPageAfterMultipleVersions() {
        final String pageName = uniquePageName( "EdgeVersions" );
        final int versionCount = 10;

        for ( int v = 1; v <= versionCount; v++ ) {
            mcp.writePage( pageName, "Content for version " + v );
        }

        for ( int v = 1; v <= versionCount; v++ ) {
            final Map< String, Object > result = mcp.readPage( pageName, v );
            Assertions.assertEquals( true, result.get( "exists" ) );
            final String content = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
            Assertions.assertTrue( content.contains( "Content for version " + v ),
                    "Version " + v + " should have correct content" );
        }
    }

    @Test
    public void listPagesWithZeroLimit() {
        final Map< String, Object > result = mcp.listPages( null, 0 );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertNotNull( pages );
        Assertions.assertTrue( pages.isEmpty(), "Zero limit should return empty list" );
    }

    @Test
    public void recentChangesWithInvalidSinceFormat() {
        Assertions.assertThrows( McpTestClient.McpToolError.class, () ->
                        mcp.recentChanges( "not-a-date" ),
                "Invalid since format should produce an error" );
    }
}

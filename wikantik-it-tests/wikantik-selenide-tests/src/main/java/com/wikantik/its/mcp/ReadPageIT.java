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
import java.util.Map;

/**
 * Integration tests for the {@code read_page} MCP tool.
 */
public class ReadPageIT extends WithMcpTestSetup {

    @Test
    public void readExistingPage() {
        final Map< String, Object > result = mcp.readPage( "Main" );
        Assertions.assertEquals( true, result.get( "exists" ) );
        Assertions.assertEquals( "Main", result.get( "pageName" ) );
        Assertions.assertNotNull( result.get( "content" ), "Content should be present" );
        final String content = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
        Assertions.assertTrue( content.contains( "Congratulations" ), "Main page should contain 'Congratulations'" );
        Assertions.assertNotNull( result.get( "version" ), "Version should be present" );
    }

    @Test
    public void readNonExistentPage() {
        final Map< String, Object > result = mcp.readPage( "NonExistentPage_" + System.currentTimeMillis() );
        Assertions.assertEquals( false, result.get( "exists" ) );
    }

    @Test
    public void readPageWithVersion() {
        final String pageName = uniquePageName( "ReadVersioned" );

        mcp.writePage( pageName, "Version one content" );
        mcp.writePage( pageName, "Version two content" );

        final Map< String, Object > v1 = mcp.readPage( pageName, 1 );
        Assertions.assertEquals( true, v1.get( "exists" ) );
        final String v1Content = McpTestClient.normalizeCrlf( ( String ) v1.get( "content" ) );
        Assertions.assertTrue( v1Content.contains( "Version one content" ), "v1 should have original content" );

        final Map< String, Object > v2 = mcp.readPage( pageName, 2 );
        final String v2Content = McpTestClient.normalizeCrlf( ( String ) v2.get( "content" ) );
        Assertions.assertTrue( v2Content.contains( "Version two content" ), "v2 should have updated content" );
    }

    @Test
    public void readPagePreservesUnicode() {
        final String pageName = uniquePageName( "UnicodeRead" );
        final String unicodeContent = "CJK: \u4f60\u597d\u4e16\u754c Emoji: \ud83d\ude80 Accent: caf\u00e9 \u00fc\u00f1\u00ef";

        mcp.writePage( pageName, unicodeContent );
        final Map< String, Object > result = mcp.readPage( pageName );

        final String readContent = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
        Assertions.assertTrue( readContent.contains( "\u4f60\u597d\u4e16\u754c" ), "CJK characters should roundtrip" );
        Assertions.assertTrue( readContent.contains( "caf\u00e9" ), "Accented characters should roundtrip" );
    }

    @Test
    public void readPageWithFrontmatterReturnsParsedMetadata() {
        final String pageName = uniquePageName( "FrontmatterRead" );
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "howto" );
        metadata.put( "author", "test" );

        mcp.writePage( pageName, "Body content here", metadata );

        final Map< String, Object > result = mcp.readPage( pageName );
        Assertions.assertEquals( true, result.get( "exists" ) );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMetadata = ( Map< String, Object > ) result.get( "metadata" );
        Assertions.assertNotNull( readMetadata, "Metadata should be returned" );
        Assertions.assertEquals( "howto", readMetadata.get( "type" ) );
        Assertions.assertEquals( "test", readMetadata.get( "author" ) );

        final String body = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
        Assertions.assertFalse( body.contains( "---" ), "Body should not contain frontmatter delimiters" );
        Assertions.assertTrue( body.contains( "Body content here" ) );
    }
}

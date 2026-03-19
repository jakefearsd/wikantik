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
 * Integration tests for the {@code write_page} MCP tool.
 */
public class WritePageIT extends WithMcpTestSetup {

    @Test
    public void createNewPage() {
        final String pageName = uniquePageName( "WriteNew" );
        final Map< String, Object > result = mcp.writePage( pageName, "Hello from MCP IT" );

        Assertions.assertEquals( true, result.get( "success" ) );
        Assertions.assertEquals( pageName, result.get( "pageName" ) );
        Assertions.assertEquals( 1.0, result.get( "version" ) );

        final Map< String, Object > readBack = mcp.readPage( pageName );
        Assertions.assertEquals( true, readBack.get( "exists" ) );
        final String content = McpTestClient.normalizeCrlf( ( String ) readBack.get( "content" ) );
        Assertions.assertTrue( content.contains( "Hello from MCP IT" ) );
    }

    @Test
    public void updateExistingPage() {
        final String pageName = uniquePageName( "WriteUpdate" );
        mcp.writePage( pageName, "Version 1" );
        final Map< String, Object > result = mcp.writePage( pageName, "Version 2" );

        Assertions.assertEquals( true, result.get( "success" ) );
        Assertions.assertEquals( 2.0, result.get( "version" ) );

        final Map< String, Object > readBack = mcp.readPage( pageName );
        final String content = McpTestClient.normalizeCrlf( ( String ) readBack.get( "content" ) );
        Assertions.assertTrue( content.contains( "Version 2" ) );
    }

    @Test
    public void writePageWithChangeNote() {
        final String pageName = uniquePageName( "WriteChangeNote" );
        final String changeNote = "IT change note " + System.currentTimeMillis();
        mcp.writePage( pageName, "Content with note", changeNote );

        final Map< String, Object > changes = mcp.recentChanges();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changeList = ( List< Map< String, Object > > ) changes.get( "changes" );

        final boolean found = changeList.stream()
                .anyMatch( c -> pageName.equals( c.get( "pageName" ) ) && changeNote.equals( c.get( "changeNote" ) ) );
        Assertions.assertTrue( found, "Change note should appear in recent changes for page " + pageName );
    }

    @Test
    public void writePageWithFrontmatter() {
        final String pageName = uniquePageName( "WriteFM" );
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "tutorial" );
        metadata.put( "difficulty", "easy" );

        mcp.writePage( pageName, "Tutorial body", metadata );

        final Map< String, Object > readBack = mcp.readPage( pageName );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMeta = ( Map< String, Object > ) readBack.get( "metadata" );
        Assertions.assertEquals( "tutorial", readMeta.get( "type" ) );
        Assertions.assertEquals( "easy", readMeta.get( "difficulty" ) );
    }

    @Test
    public void writePageWithEmptyContent() {
        final String pageName = uniquePageName( "WriteEmpty" );
        final Map< String, Object > result = mcp.writePage( pageName, "" );
        Assertions.assertEquals( true, result.get( "success" ) );
    }

    @Test
    public void writePageAuthorIsSet() {
        final String pageName = uniquePageName( "WriteAuthor" );
        mcp.writePage( pageName, "Check author" );

        final Map< String, Object > readBack = mcp.readPage( pageName );
        final String author = ( String ) readBack.get( "author" );
        Assertions.assertNotNull( author, "Author should be set" );
        Assertions.assertFalse( author.isBlank(), "Author should not be blank" );
    }

    @Test
    public void writePageWithSpecialCharactersInName() {
        final String pageName = uniquePageName( "Write-Special_Chars" );
        final Map< String, Object > result = mcp.writePage( pageName, "Special name page" );
        Assertions.assertEquals( true, result.get( "success" ) );

        final Map< String, Object > readBack = mcp.readPage( pageName );
        Assertions.assertEquals( true, readBack.get( "exists" ) );
    }
}

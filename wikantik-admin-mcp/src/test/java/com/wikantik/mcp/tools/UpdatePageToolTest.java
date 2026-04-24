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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdatePageToolTest {

    @Test
    void name_isUpdatePage() {
        assertEquals( "update_page",
            new UpdatePageTool( mock( PageSaveHelper.class ), mock( PageManager.class ) ).name() );
    }

    @Test
    void definition_requiresPageNameContentAndHash() {
        final UpdatePageTool t = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final var req = t.definition().inputSchema().required();
        assertTrue( req.contains( "pageName" ) );
        assertTrue( req.contains( "content" ) );
        assertTrue( req.contains( "expectedContentHash" ) );
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

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
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
    void execute_failsOnHashMismatch() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "P" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "drift body" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P",
            "content", "new body",
            "expectedContentHash", "staleHashValue" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "hash mismatch" ) );
        assertTrue( text.contains( "\"currentHash\"" ) );
        verify( helper, never() ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
    }

    @Test
    void execute_failsWhenPageDoesNotExist() {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Missing" ) ).thenReturn( null );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "Missing",
            "content", "body",
            "expectedContentHash", "doesNotMatter" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "not found" ) );
    }
}

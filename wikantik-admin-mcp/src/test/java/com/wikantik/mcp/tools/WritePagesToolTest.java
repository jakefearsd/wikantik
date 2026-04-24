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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WritePagesToolTest {

    @Test
    void name_isWritePages() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ) );
        assertEquals( "write_pages", t.name() );
    }

    @Test
    void definition_requiresPagesArray() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ) );
        assertTrue( t.definition().inputSchema().required().contains( "pages" ) );
    }

    @Test
    void execute_createsNewPages() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm );
        tool.setDefaultAuthor( "test-agent" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "pageName", "NewPageA", "content", "body A", "metadata", Map.of( "cluster", "x" ) ),
                Map.of( "pageName", "NewPageB", "content", "body B" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "NewPageA" ) );
        assertTrue( text.contains( "NewPageB" ) );
        assertTrue( text.contains( "\"created\":true" ) );
        assertTrue( text.contains( "\"total\":2" ) );
        assertTrue( text.contains( "\"createdCount\":2" ) );

        verify( helper, times( 2 ) ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
    }

    @Test
    void execute_returnsFailureForExistingPage() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Exists" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPage( "Fresh" ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "pageName", "Exists", "content", "body" ),
                Map.of( "pageName", "Fresh", "content", "body" ) ) ) );

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
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pages", List.of() ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_rejectsMissingPagesKey() {
        final WritePagesTool tool = new WritePagesTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}

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
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadPageToolTest {

    private PageManager pageManager;
    private ReadPageTool tool;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        tool = new ReadPageTool( pageManager );
    }

    private static Page pageStub( final int version ) {
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( version );
        when( page.getLastModified() ).thenReturn( new Date( 0L ) );
        return page;
    }

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    @Test
    void toolNameAndDefinition() {
        assertEquals( "read_page", tool.name() );
        assertNotNull( tool.definition() );
        assertNotNull( tool.definition().inputSchema() );
    }

    @Test
    void blankPageNameIsAnError() {
        assertTrue( Boolean.TRUE.equals( tool.execute( Map.of( "slug", "  " ) ).isError() ) );
    }

    @Test
    void missingPageReportsExistsFalse() {
        final var result = tool.execute( Map.of( "slug", "NoSuchPage" ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = text( result );
        assertTrue( json.contains( "\"exists\"" ) && json.contains( "false" ) );
    }

    @Test
    void happyPath_returnsBodyVersionAndHash() {
        final Page page = pageStub( 7 );
        when( pageManager.getPageWithoutMetadata( eq( "PageA" ), anyInt() ) ).thenReturn( page );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn( "body of A" );

        final String json = text( tool.execute( Map.of( "slug", "PageA" ) ) );

        assertTrue( json.contains( "body of A" ), "body must be returned" );
        assertTrue( json.contains( "\"version\"" ) && json.contains( "7" ), "version must be returned" );
        assertTrue( json.contains( "contentHash" ), "update_page's optimistic-lock hash must be returned" );
    }

    @Test
    void usesMetadataFreeAccessor_neverTriggersAFullMarkupParse() {
        // read_page needs existence, version and lastModified — all of which come straight off the
        // provider's page info. getPage() additionally runs CachingProvider's refreshMetadata(),
        // parsing the whole page through the markup pipeline to populate variables this tool never
        // reads. The body it does return comes from getPureText(), not from that parse.
        final Page page = pageStub( 1 );
        when( pageManager.getPageWithoutMetadata( eq( "PageA" ), anyInt() ) ).thenReturn( page );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn( "body" );

        tool.execute( Map.of( "slug", "PageA" ) );

        verify( pageManager ).getPageWithoutMetadata( eq( "PageA" ), anyInt() );
        verify( pageManager, never() ).getPage( eq( "PageA" ), anyInt() );
    }
}

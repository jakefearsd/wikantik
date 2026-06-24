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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReadPagesToolTest {

    private PageManager pageManager;
    private ReadPagesTool tool;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        tool = new ReadPagesTool( pageManager, null );
    }

    @Test
    void toolNameAndDefinition() {
        assertEquals( "read_pages", tool.name() );
        assertNotNull( tool.definition() );
        assertNotNull( tool.definition().inputSchema() );
    }

    @Test
    void rejectsEmptyInput() {
        final var result = tool.execute( Map.of( "slugs", List.of() ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
    }

    @Test
    void schema_advertisesSlugsNotPageNames() {
        final var props = tool.definition().inputSchema().properties();
        assertTrue( props.containsKey( "slugs" ) );
        assertFalse( props.containsKey( "pageNames" ) );
    }

    @Test
    void acceptsPageNamesAlias() {
        // An agent that learned `pageNames` from the admin MCP should not hard-fail here.
        final var result = tool.execute( Map.of( "pageNames", List.of( "Alpha" ) ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ), "the 'pageNames' alias should resolve" );
        assertTrue( ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text().contains( "Alpha" ) );
    }

    @Test
    void rejectsOver20Slugs() {
        final List< String > tooMany = new java.util.ArrayList<>();
        for ( int i = 0; i < 21; i++ ) tooMany.add( "P" + i );
        final var result = tool.execute( Map.of( "slugs", tooMany ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
        assertTrue( result.content().toString().contains( "20" ),
                    "error message should mention the cap" );
    }

    @Test
    void happyPath_returnsContentForEachSlug() throws Exception {
        when( pageManager.getPage( eq( "PageA" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPage( eq( "PageB" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn( "body of A" );
        when( pageManager.getPureText( eq( "PageB" ), anyInt() ) ).thenReturn( "body of B" );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "PageB" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "body of A" ) );
        assertTrue( json.contains( "body of B" ) );
    }

    @Test
    void partialFailure_missingSlugReturnsErrorEntryNotCallFailure() throws Exception {
        when( pageManager.getPage( eq( "PageA" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn( "body of A" );
        when( pageManager.getPage( eq( "Missing" ), anyInt() ) ).thenReturn( null );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "Missing" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not_found" ) );
        assertTrue( json.contains( "body of A" ) );
    }

    @Test
    void partialFailure_internalErrorOnOnePageStillReturnsOthers() throws Exception {
        when( pageManager.getPage( eq( "PageA" ), anyInt() ) ).thenThrow( new RuntimeException( "boom" ) );
        when( pageManager.getPage( eq( "PageB" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( "PageB" ), anyInt() ) ).thenReturn( "body of B" );

        final var result = tool.execute( Map.of( "slugs", List.of( "PageA", "PageB" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "internal_error" ) );
        assertTrue( json.contains( "body of B" ) );
    }

    @Test
    void duplicateSlugsAreDeduplicated() throws Exception {
        when( pageManager.getPage( eq( "PageA" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( "PageA" ), anyInt() ) ).thenReturn( "body" );

        tool.execute( Map.of( "slugs", List.of( "PageA", "PageA", "PageA" ) ) );

        verify( pageManager, times( 1 ) ).getPage( eq( "PageA" ), anyInt() );
    }

    @Test
    void restrictedSlugHiddenAsNotFound_andBodyNeverLeaks() throws Exception {
        // Guest view-gate denies "Secret". The tool must hide it as not_found and must never
        // read or return its body — even though the page exists and PageManager would serve it.
        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final ReadPagesTool gated = new ReadPagesTool( pageManager, null, gate );
        when( pageManager.getPage( eq( "Secret" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( "Secret" ), anyInt() ) ).thenReturn( "TOP SECRET BODY" );

        final var result = gated.execute( Map.of( "slugs", List.of( "Secret" ) ) );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertFalse( json.contains( "TOP SECRET BODY" ),
                "restricted page body must never leak through read_pages" );
        assertTrue( json.contains( "not_found" ),
                "restricted page should be indistinguishable from a missing page" );
        verify( pageManager, never() ).getPureText( eq( "Secret" ), anyInt() );
    }
}

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
import com.wikantik.content.PageRenamer;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RenamePageToolTest {

    private TestEngine engine;
    private RenamePageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new RenamePageTool( engine, engine.getManager( PageManager.class ),
                engine.getManager( PageRenamer.class ), engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenamePageSucceeds() throws Exception {
        engine.saveText( "OldPageName", "Some content here" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "OldPageName" );
        args.put( "newName", "NewPageName" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertEquals( "OldPageName", data.get( "oldName" ) );
        assertEquals( "NewPageName", data.get( "newName" ) );

        // Verify old page is gone and new page exists
        assertNull( engine.getManager( PageManager.class ).getPage( "OldPageName" ) );
        assertNotNull( engine.getManager( PageManager.class ).getPage( "NewPageName" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenameWithoutConfirmFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "SomePage" );
        args.put( "newName", "OtherPage" );
        args.put( "confirm", false );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not confirmed" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenameNonexistentPageFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "DoesNotExist" );
        args.put( "newName", "NewName" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not found" ) || json.contains( "does not exist" ),
                "Expected error about missing page, got: " + json );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "rename_page", def.name() );
        assertTrue( def.annotations().destructiveHint() );
    }

    private static String textOf( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    private RenamePageTool toolWith( final PageRenamer renamer, final SystemPageRegistry registry ) {
        return new RenamePageTool( engine, engine.getManager( PageManager.class ), renamer, registry );
    }

    private static Map< String, Object > args( final String from, final String to, final boolean confirm ) {
        final Map< String, Object > a = new HashMap<>();
        a.put( "oldName", from );
        a.put( "newName", to );
        a.put( "confirm", confirm );
        return a;
    }

    @Test
    void anInvalidOldNameIsRefusedBeforeAnythingIsRenamed() throws Exception {
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );
        final McpSchema.CallToolResult result = toolWith( renamer, null ).execute( args( "  ", "New", true ) );

        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "oldName" ), textOf( result ) );
        org.mockito.Mockito.verifyNoInteractions( renamer );
    }

    @Test
    void anInvalidNewNameIsRefusedBeforeAnythingIsRenamed() throws Exception {
        engine.saveText( "Source", "x" );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );
        final McpSchema.CallToolResult result = toolWith( renamer, null ).execute( args( "Source", "  ", true ) );

        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "newName" ), textOf( result ) );
        org.mockito.Mockito.verifyNoInteractions( renamer );
    }

    @Test
    void renamingASystemPageIsRefusedAndTheRenamerIsNeverCalled() throws Exception {
        engine.saveText( "SysPage", "x" );
        final SystemPageRegistry registry = org.mockito.Mockito.mock( SystemPageRegistry.class );
        org.mockito.Mockito.when( registry.isSystemPage( "SysPage" ) ).thenReturn( true );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );

        final McpSchema.CallToolResult result = toolWith( renamer, registry ).execute( args( "SysPage", "Other", true ) );

        assertTrue( result.isError() );
        assertEquals( "Cannot rename system page: SysPage", gson.fromJson( textOf( result ), Map.class ).get( "error" ) );
        org.mockito.Mockito.verifyNoInteractions( renamer );
    }

    @Test
    void renamingOntoASystemPageNameIsRefusedAndTheRenamerIsNeverCalled() throws Exception {
        engine.saveText( "Plain", "x" );
        final SystemPageRegistry registry = org.mockito.Mockito.mock( SystemPageRegistry.class );
        org.mockito.Mockito.when( registry.isSystemPage( "SysTarget" ) ).thenReturn( true );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );

        final McpSchema.CallToolResult result = toolWith( renamer, registry ).execute( args( "Plain", "SysTarget", true ) );

        assertTrue( result.isError() );
        assertEquals( "Cannot rename to system page name: SysTarget",
            gson.fromJson( textOf( result ), Map.class ).get( "error" ) );
        org.mockito.Mockito.verifyNoInteractions( renamer );
    }

    @Test
    void linkUpdatingDefaultsToOnAndCanBeSwitchedOff() throws Exception {
        engine.saveText( "LinkSrc", "x" );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );
        org.mockito.Mockito.when( renamer.renamePage( org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq( "LinkSrc" ), org.mockito.ArgumentMatchers.eq( "LinkDst" ),
            org.mockito.ArgumentMatchers.anyBoolean() ) ).thenReturn( "LinkDst" );
        final RenamePageTool t = toolWith( renamer, null );

        final Map< ?, ? > defaulted = gson.fromJson( textOf( t.execute( args( "LinkSrc", "LinkDst", true ) ) ), Map.class );
        final Map< String, Object > off = args( "LinkSrc", "LinkDst", true );
        off.put( "updateLinks", false );
        final Map< ?, ? > disabled = gson.fromJson( textOf( t.execute( off ) ), Map.class );

        assertEquals( true, defaulted.get( "linksUpdated" ) );
        assertEquals( false, disabled.get( "linksUpdated" ) );
        org.mockito.Mockito.verify( renamer ).renamePage( org.mockito.ArgumentMatchers.any(), eq1( "LinkSrc" ),
            eq1( "LinkDst" ), org.mockito.ArgumentMatchers.eq( true ) );
        org.mockito.Mockito.verify( renamer ).renamePage( org.mockito.ArgumentMatchers.any(), eq1( "LinkSrc" ),
            eq1( "LinkDst" ), org.mockito.ArgumentMatchers.eq( false ) );
    }

    private static String eq1( final String v ) {
        return org.mockito.ArgumentMatchers.eq( v );
    }

    @Test
    void theResultReportsTheNameTheRenamerActuallyUsed() throws Exception {
        engine.saveText( "Raw", "x" );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );
        org.mockito.Mockito.when( renamer.renamePage( org.mockito.ArgumentMatchers.any(), eq1( "Raw" ), eq1( "Cooked" ),
            org.mockito.ArgumentMatchers.anyBoolean() ) ).thenReturn( "Cooked2" );

        final Map< ?, ? > data = gson.fromJson( textOf( toolWith( renamer, null ).execute( args( "Raw", "Cooked", true ) ) ),
            Map.class );

        assertEquals( "Cooked", data.get( "newName" ) );
        assertEquals( "Cooked2", data.get( "finalName" ) );
    }

    @Test
    void aRenamerRefusalIsReturnedAsAnErrorResultWithItsMessage() throws Exception {
        engine.saveText( "Src2", "x" );
        final PageRenamer renamer = org.mockito.Mockito.mock( PageRenamer.class );
        org.mockito.Mockito.when( renamer.renamePage( org.mockito.ArgumentMatchers.any(), eq1( "Src2" ), eq1( "Taken" ),
            org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenThrow( new com.wikantik.api.exceptions.WikiException( "Page already exists" ) {
            } );

        final McpSchema.CallToolResult result = toolWith( renamer, null ).execute( args( "Src2", "Taken", true ) );

        assertTrue( result.isError() );
        assertEquals( "Page already exists", gson.fromJson( textOf( result ), Map.class ).get( "error" ) );
    }

    @Test
    void aSuccessfulRenameIsAuditedWithBothNamesAndNoAuthor() throws Exception {
        engine.saveText( "AuditOld", "x" );
        final java.util.List< String > lines = SecurityLogCapture.during( () -> tool.execute( args( "AuditOld", "AuditNew", true ) ) );
        assertEquals( java.util.List.of( "MCP_WRITE tool=rename_page action=renamed target=AuditOld->AuditNew author=unknown" ),
            lines );
    }
}

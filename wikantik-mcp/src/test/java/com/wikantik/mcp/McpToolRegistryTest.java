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
package com.wikantik.mcp;

import com.wikantik.TestEngine;
import com.wikantik.mcp.tools.LockPageTool;
import com.wikantik.mcp.tools.McpTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistryTest {

    private TestEngine engine;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        registry = new McpToolRegistry( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testReadOnlyToolsNotEmpty() {
        final List< McpTool > readOnly = registry.readOnlyTools();
        assertNotNull( readOnly );
        assertFalse( readOnly.isEmpty() );
    }

    @Test
    void testReadOnlyToolsContainExpectedTools() {
        final Set< String > names = registry.readOnlyTools().stream()
                .map( McpTool::name )
                .collect( Collectors.toSet() );

        // Verify key read-only tools are registered
        assertTrue( names.contains( "read_page" ), "should contain read_page" );
        assertTrue( names.contains( "search_pages" ), "should contain search_pages" );
        assertTrue( names.contains( "list_pages" ), "should contain list_pages" );
        assertTrue( names.contains( "get_backlinks" ), "should contain get_backlinks" );
        assertTrue( names.contains( "recent_changes" ), "should contain recent_changes" );
        assertTrue( names.contains( "get_attachments" ), "should contain get_attachments" );
        assertTrue( names.contains( "query_metadata" ), "should contain query_metadata" );
        assertTrue( names.contains( "diff_page" ), "should contain diff_page" );
        assertTrue( names.contains( "get_outbound_links" ), "should contain get_outbound_links" );
        assertTrue( names.contains( "get_broken_links" ), "should contain get_broken_links" );
        assertTrue( names.contains( "get_orphaned_pages" ), "should contain get_orphaned_pages" );
        assertTrue( names.contains( "get_wiki_stats" ), "should contain get_wiki_stats" );
    }

    @Test
    void testAuthorConfigurableToolsNotEmpty() {
        final List< McpTool > authorTools = registry.authorConfigurableTools();
        assertNotNull( authorTools );
        assertFalse( authorTools.isEmpty() );
    }

    @Test
    void testAuthorConfigurableToolsContainExpectedTools() {
        final Set< String > names = registry.authorConfigurableTools().stream()
                .map( McpTool::name )
                .collect( Collectors.toSet() );

        assertTrue( names.contains( "write_page" ), "should contain write_page" );
        assertTrue( names.contains( "batch_write_pages" ), "should contain batch_write_pages" );
        assertTrue( names.contains( "rename_page" ), "should contain rename_page" );
        assertTrue( names.contains( "patch_page" ), "should contain patch_page" );
        assertTrue( names.contains( "update_metadata" ), "should contain update_metadata" );
    }

    @Test
    void testLockPageToolPresent() {
        final LockPageTool lockTool = registry.lockPageTool();
        assertNotNull( lockTool );
        assertEquals( "lock_page", lockTool.name() );
    }

    @Test
    void testAllToolNamesAreUnique() {
        final List< String > allNames = new java.util.ArrayList<>();
        registry.readOnlyTools().forEach( t -> allNames.add( t.name() ) );
        registry.authorConfigurableTools().forEach( t -> allNames.add( t.name() ) );
        allNames.add( registry.lockPageTool().name() );

        final Set< String > uniqueNames = new java.util.HashSet<>( allNames );
        assertEquals( allNames.size(), uniqueNames.size(), "Tool names must be unique" );
    }

    @Test
    void testTotalToolCount() {
        final int total = registry.readOnlyTools().size()
                + registry.authorConfigurableTools().size()
                + 1; // lockPageTool
        // Ensure we have at least the expected minimum number of tools
        assertTrue( total >= 35, "Expected at least 35 tools, found " + total );
    }

    @Test
    void testAllToolsHaveDefinitions() {
        for ( final McpTool tool : registry.readOnlyTools() ) {
            assertNotNull( tool.definition(), "Tool " + tool.name() + " should have a definition" );
            assertNotNull( tool.definition().name(), "Tool " + tool.name() + " definition should have a name" );
        }
        for ( final McpTool tool : registry.authorConfigurableTools() ) {
            assertNotNull( tool.definition(), "Tool " + tool.name() + " should have a definition" );
        }
        assertNotNull( registry.lockPageTool().definition() );
    }
}

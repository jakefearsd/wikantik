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

        // Wiki management tools
        assertTrue( names.contains( "get_backlinks" ), "should contain get_backlinks" );
        assertTrue( names.contains( "diff_page" ), "should contain diff_page" );
        assertTrue( names.contains( "get_outbound_links" ), "should contain get_outbound_links" );
        assertTrue( names.contains( "get_broken_links" ), "should contain get_broken_links" );
        assertTrue( names.contains( "get_orphaned_pages" ), "should contain get_orphaned_pages" );
        assertTrue( names.contains( "get_wiki_stats" ), "should contain get_wiki_stats" );
        assertTrue( names.contains( "verify_pages" ), "should contain verify_pages" );
        assertTrue( names.contains( "preview_structured_data" ), "should contain preview_structured_data" );

        // Page-level read + delete are intentionally re-registered on admin-mcp
        // alongside their /knowledge-mcp counterparts: agents authoring with the
        // admin tool surface need a single-page fetch and a bulk delete without
        // hopping endpoints. Don't reduce the duplication unless both surfaces
        // converge on a single client-facing endpoint.
        assertTrue( names.contains( "read_page" ),    "should contain read_page (admin write workflow)" );
        assertTrue( names.contains( "delete_pages" ), "should contain delete_pages (admin write workflow)" );

        // Bulk-discovery and search tools belong only on /knowledge-mcp — keep
        // the admin surface small.
        assertFalse( names.contains( "search_pages" ),   "search_pages lives on knowledge-mcp" );
        assertFalse( names.contains( "list_pages" ),     "list_pages lives on knowledge-mcp" );
        assertFalse( names.contains( "recent_changes" ), "recent_changes lives on knowledge-mcp" );
        assertFalse( names.contains( "query_metadata" ), "query_metadata lives on knowledge-mcp" );
        assertFalse( names.contains( "export_content" ), "export_content lives on knowledge-mcp" );
        assertFalse( names.contains( "preview_import" ), "preview_import lives on knowledge-mcp" );
    }

    @Test
    void testRemovedToolsAreGone() {
        final Set< String > allNames = new java.util.HashSet<>();
        registry.readOnlyTools().forEach( t -> allNames.add( t.name() ) );
        registry.authorConfigurableTools().forEach( t -> allNames.add( t.name() ) );

        // These tools were removed in favor of export/import workflow
        assertFalse( allNames.contains( "write_page" ), "write_page should be removed" );
        assertFalse( allNames.contains( "patch_page" ), "patch_page should be removed" );
        assertFalse( allNames.contains( "batch_write_pages" ), "batch_write_pages should be removed" );
        assertFalse( allNames.contains( "batch_patch_pages" ), "batch_patch_pages should be removed" );
        assertFalse( allNames.contains( "update_metadata" ), "update_metadata should be removed" );
        assertFalse( allNames.contains( "batch_update_metadata" ), "batch_update_metadata should be removed" );
        assertFalse( allNames.contains( "lock_page" ), "lock_page should be removed" );
        assertFalse( allNames.contains( "unlock_page" ), "unlock_page should be removed" );
        assertFalse( allNames.contains( "upload_attachment" ), "upload_attachment should be removed" );
        assertFalse( allNames.contains( "delete_attachment" ), "delete_attachment should be removed" );
        assertFalse( allNames.contains( "read_attachment" ), "read_attachment should be removed" );
        assertFalse( allNames.contains( "publish_cluster" ), "publish_cluster should be removed" );
        assertFalse( allNames.contains( "extend_cluster" ), "extend_cluster should be removed" );
        assertFalse( allNames.contains( "apply_audit_fixes" ), "apply_audit_fixes should be removed" );
        assertFalse( allNames.contains( "delete_page" ), "delete_page should be removed" );
        assertFalse( allNames.contains( "get_attachments" ), "get_attachments should be removed" );
        assertFalse( allNames.contains( "scan_markdown_links" ), "scan_markdown_links should be removed" );
        assertFalse( allNames.contains( "get_cluster_map" ), "get_cluster_map should be removed" );
        assertFalse( allNames.contains( "audit_cluster" ), "audit_cluster should be removed" );
        assertFalse( allNames.contains( "audit_cross_cluster" ), "audit_cross_cluster should be removed" );
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

        assertTrue( names.contains( "rename_page" ), "should contain rename_page" );
        assertTrue( names.contains( "write_pages" ), "should contain write_pages" );
        assertTrue( names.contains( "update_page" ), "should contain update_page" );

        // import_content moved to wikantik-knowledge
        assertFalse( names.contains( "import_content" ), "import_content moved to knowledge-mcp" );
    }

    @Test
    void testAllToolNamesAreUnique() {
        final List< String > allNames = new java.util.ArrayList<>();
        registry.readOnlyTools().forEach( t -> allNames.add( t.name() ) );
        registry.authorConfigurableTools().forEach( t -> allNames.add( t.name() ) );

        final Set< String > uniqueNames = new java.util.HashSet<>( allNames );
        assertEquals( allNames.size(), uniqueNames.size(), "Tool names must be unique" );
    }

    @Test
    void testTotalToolCount() {
        final int total = registry.readOnlyTools().size()
                + registry.authorConfigurableTools().size();
        // 12 read-only (10 base + read_page + delete_pages) + 3 author-configurable
        // = 15 base (+ ListProposalsTool / ProposeKnowledgeTool when KG is wired).
        // Bulk-discovery, search, recent-changes, metadata, and import/export
        // tools live on /knowledge-mcp.
        assertTrue( total >= 15, "Expected at least 15 tools, found " + total );
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
    }
}

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

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protocol-level integration tests: handshake, tool discovery, schema validation.
 */
public class McpProtocolIT extends WithMcpTestSetup {

    private static final Set< String > EXPECTED_TOOLS = Set.of(
            "read_page", "write_page", "search_pages", "list_pages",
            "get_backlinks", "recent_changes", "get_attachments", "query_metadata",
            "delete_page", "get_page_history", "diff_page", "batch_write_pages",
            "get_outbound_links", "get_broken_links", "get_orphaned_pages",
            "get_wiki_stats", "list_metadata_values", "rename_page",
            "lock_page", "unlock_page", "upload_attachment", "read_attachment",
            "delete_attachment", "patch_page", "batch_patch_pages",
            "update_metadata", "scan_markdown_links"
    );

    @Test
    public void clientCanInitializeAndPing() {
        Assertions.assertTrue( mcp.rawClient().isInitialized(), "Client should be initialized" );
        Assertions.assertDoesNotThrow( () -> mcp.ping(), "Ping should succeed" );
    }

    @Test
    public void listToolsReturnsAllTools() {
        final McpSchema.ListToolsResult result = mcp.listTools();
        final Set< String > toolNames = result.tools().stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toSet() );

        Assertions.assertEquals( EXPECTED_TOOLS, toolNames,
                "Server should expose exactly " + EXPECTED_TOOLS.size() + " tools" );
    }

    @Test
    public void listToolsContainsCorrectSchemas() {
        final McpSchema.ListToolsResult result = mcp.listTools();

        for ( final McpSchema.Tool tool : result.tools() ) {
            Assertions.assertNotNull( tool.description(), tool.name() + " should have a description" );
            Assertions.assertNotNull( tool.inputSchema(), tool.name() + " should have an input schema" );

            final McpSchema.JsonSchema schema = tool.inputSchema();
            Assertions.assertEquals( "object", schema.type(), tool.name() + " schema type should be 'object'" );
        }

        // Verify required params for key tools
        final Map< String, List< String > > toolRequiredParams = result.tools().stream()
                .collect( Collectors.toMap( McpSchema.Tool::name, t -> t.inputSchema().required() != null ? t.inputSchema().required() : List.of() ) );

        Assertions.assertTrue( toolRequiredParams.get( "read_page" ).contains( "pageName" ) );
        Assertions.assertTrue( toolRequiredParams.get( "write_page" ).contains( "pageName" ) );
        Assertions.assertTrue( toolRequiredParams.get( "write_page" ).contains( "content" ) );
        Assertions.assertTrue( toolRequiredParams.get( "search_pages" ).contains( "query" ) );
        Assertions.assertTrue( toolRequiredParams.get( "get_backlinks" ).contains( "pageName" ) );
        Assertions.assertTrue( toolRequiredParams.get( "get_attachments" ).contains( "pageName" ) );
    }

    @Test
    public void callNonExistentToolReturnsError() {
        Assertions.assertThrows( Exception.class, () ->
                mcp.callTool( "nonexistent_tool", Map.of() ),
                "Calling a non-existent tool should throw" );
    }
}

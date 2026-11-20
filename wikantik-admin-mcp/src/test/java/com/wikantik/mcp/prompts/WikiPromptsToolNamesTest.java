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
package com.wikantik.mcp.prompts;

import com.wikantik.TestEngine;
import com.wikantik.mcp.McpToolRegistry;
import com.wikantik.mcp.tools.ListRetrievalQueriesTool;
import com.wikantik.mcp.tools.McpTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the admin MCP prompts against naming tools that exist on neither MCP endpoint. Prompt
 * text once told agents to call {@code search_pages}, {@code export_content}, {@code import_content},
 * {@code preview_import} and {@code query_metadata}, none of which has been registered anywhere.
 */
class WikiPromptsToolNamesTest {

    /**
     * Tool names registered on {@code /knowledge-mcp} by {@code KnowledgeMcpInitializer}. That module
     * is not on this module's classpath (and must not be), so the list is kept here; keep it in sync
     * with the {@code TOOL_NAME} constants in {@code com.wikantik.knowledge.mcp}.
     */
    private static final Set< String > KNOWLEDGE_TOOLS = Set.of(
            "retrieve_context", "get_page", "list_pages", "list_metadata_values", "read_pages",
            "list_clusters", "list_tags", "list_pages_by_filter", "get_page_by_id", "get_page_neighbors",
            "get_page_for_agent", "assemble_bundle", "get_briefing", "get_ontology", "sparql_query",
            "list_stale_citations" );

    private static final Pattern BACKTICKED_SNAKE = Pattern.compile( "`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`" );

    /** Backticked snake_case names in prompt text that are frontmatter fields, not tools. */
    private static final Set< String > NOT_TOOLS = Set.of( "verified_at", "verified_by" );

    @Test
    void everyToolAPromptNamesExists() {
        final TestEngine engine = TestEngine.build();
        try {
            final McpToolRegistry registry = new McpToolRegistry( engine );
            final Set< String > known = new LinkedHashSet<>( KNOWLEDGE_TOOLS );
            for ( final McpTool t : registry.readOnlyTools() ) {
                known.add( t.name() );
            }
            for ( final McpTool t : registry.authorConfigurableTools() ) {
                known.add( t.name() );
            }
            known.add( ListRetrievalQueriesTool.TOOL_NAME );

            final Set< String > unknown = new LinkedHashSet<>();
            for ( final McpServerFeatures.SyncPromptSpecification spec : WikiPrompts.all() ) {
                final McpSchema.GetPromptResult result = spec.promptHandler().apply( null,
                        new McpSchema.GetPromptRequest( spec.prompt().name(), Map.of() ) );
                for ( final McpSchema.PromptMessage msg : result.messages() ) {
                    final Matcher m = BACKTICKED_SNAKE.matcher( ( (McpSchema.TextContent) msg.content() ).text() );
                    while ( m.find() ) {
                        final String tok = m.group( 1 );
                        if ( !known.contains( tok ) && !NOT_TOOLS.contains( tok ) ) {
                            unknown.add( tok + " (in prompt " + spec.prompt().name() + ")" );
                        }
                    }
                }
            }
            assertTrue( unknown.isEmpty(), "prompts name tools that exist on neither endpoint: " + unknown );
        } finally {
            engine.stop();
        }
    }
}

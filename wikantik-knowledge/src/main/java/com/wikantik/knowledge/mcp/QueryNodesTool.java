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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.ArrayList;

/**
 * MCP tool that searches for nodes by type, properties, and provenance.
 */
public class QueryNodesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( QueryNodesTool.class );
    public static final String TOOL_NAME = "query_nodes";

    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public QueryNodesTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public QueryNodesTool( final KnowledgeGraphService service,
                           final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "filters", Map.of(
                "type", "object",
                "description", "Filter criteria — keys like node_type, source_page, or any property key"
        ) );
        properties.put( "provenance_filter", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Provenance values to include (e.g. human-authored, ai-reviewed, ai-inferred)"
        ) );
        properties.put( "limit", Map.of(
                "type", "integer",
                "description", "Maximum number of results (default 50)"
        ) );
        properties.put( "offset", Map.of(
                "type", "integer",
                "description", "Offset for pagination (default 0)"
        ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Search for nodes by type, properties, and provenance. " +
                        "Use discover_schema first to understand available types and properties." +
                        " Results are filtered to nodes the entity extractor has actually found in wiki content;" +
                        " nodes present only from legacy frontmatter/link projection are hidden." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final Map< String, Object > filters = ( Map< String, Object > ) arguments.get( "filters" );
            final Set< Provenance > provenanceFilter = KnowledgeMcpUtils.parseProvenanceFilter( arguments );
            final int limit = McpToolUtils.getInt( arguments, "limit", 50 );
            final int offset = McpToolUtils.getInt( arguments, "offset", 0 );

            final List< KgNode > nodes = service.queryNodes( filters, provenanceFilter, limit, offset );
            final List< KgNode > filtered;
            if ( mentionIndex == null ) {
                filtered = nodes;
            } else {
                filtered = new ArrayList<>( nodes.size() );
                for ( final KgNode n : nodes ) {
                    if ( mentionIndex.isMentioned( n.id() ) ) filtered.add( n );
                }
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, Map.of( "results", filtered ) );
        } catch ( final Exception e ) {
            LOG.error( "Query nodes failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}

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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that returns full details for a single node: all properties,
 * all edges (inbound and outbound), source page, and provenance.
 */
public class GetNodeTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetNodeTool.class );
    public static final String TOOL_NAME = "get_node";

    private final KnowledgeGraphService service;

    public GetNodeTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "node", Map.of(
                "type", "string",
                "description", "Node name or UUID"
        ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get full details for a single node: all properties, all edges " +
                        "(inbound and outbound), source page, and provenance." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "node" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String nodeRef = McpToolUtils.getString( arguments, "node" );

            // Try by name first, then by UUID
            KgNode node = service.getNodeByName( nodeRef );
            if ( node == null ) {
                try {
                    final UUID id = UUID.fromString( nodeRef );
                    node = service.getNode( id );
                } catch ( final IllegalArgumentException ignored ) {
                    // Not a valid UUID, that's fine
                }
            }

            if ( node == null ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                        "Node not found: " + nodeRef );
            }

            final List< KgEdge > outbound = service.getEdgesForNode( node.id(), "outbound" );
            final List< KgEdge > inbound = service.getEdgesForNode( node.id(), "inbound" );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "node", node );
            result.put( "outbound_edges", outbound );
            result.put( "inbound_edges", inbound );

            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Get node failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}

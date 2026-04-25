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

import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool — returns every cluster with its hub page, article count, and freshness. */
public class ListClustersTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListClustersTool.class );
    public static final String TOOL_NAME = "list_clusters";

    private final StructuralIndexService service;

    public ListClustersTool( final StructuralIndexService service ) {
        this.service = service;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "clusters", List.of(
                        Map.of(
                                "name", "retrieval",
                                "hub_id", "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
                                "hub_slug", "HybridRetrieval",
                                "article_count", 14,
                                "last_updated", "2026-04-25T14:30:00Z"
                        ),
                        Map.of(
                                "name", "agents",
                                "hub_id", "01H8G3Z2E7FD8R1Q4V9X2T0NMP",
                                "hub_slug", "AgentMemory",
                                "article_count", 11,
                                "last_updated", "2026-04-22T10:11:00Z"
                        )
                ),
                "count", 2
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List every cluster in the wiki with its hub page, article count, " +
                        "and most-recent update time. Call this first when an agent needs a map of " +
                        "topic areas before drilling into a specific cluster." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final List< ClusterSummary > clusters = service.listClusters();
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                    Map.of( "clusters", clusters, "count", clusters.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_clusters failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}

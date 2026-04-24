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

import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool — returns the tag dictionary with counts and top pages per tag. */
public class ListTagsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListTagsTool.class );
    public static final String TOOL_NAME = "list_tags";

    private final StructuralIndexService service;
    public ListTagsTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "min_pages", Map.of(
                "type", "integer",
                "description", "Only return tags used by at least this many pages (default 1)."
        ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Return the tag dictionary for the wiki — one entry per tag, with the " +
                        "page count and a sample of canonical IDs using that tag. Useful when an agent " +
                        "wants to discover what topics the wiki covers." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final int minPages = arguments.get( "min_pages" ) instanceof Number n
                    ? Math.max( 1, n.intValue() )
                    : 1;
            final List< TagSummary > tags = service.listTags( minPages );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                    Map.of( "tags", tags, "count", tags.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_tags failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}

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

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * Shared boilerplate for the "single required {@code slug} parameter" MCP
 * tool shape used by {@link PreviewStructuredDataTool} and {@link ReadPageTool}:
 * an identical single-slug input schema + generic read-only annotations tail
 * on {@code definition()}, and the blank-slug error envelope on {@code doExecute()}.
 */
final class SlugToolSupport {

    private SlugToolSupport() {
    }

    static McpSchema.Tool singleSlugToolDefinition( final String name, final Map< String, Object > properties,
                                                      final String description,
                                                      final Map< String, Object > outputSchema ) {
        return McpSchema.Tool.builder()
                .name( name )
                .description( description )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "slug" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    static McpSchema.CallToolResult blankSlugError( final String message ) {
        return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, message );
    }
}

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

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: distinct frontmatter field values across all pages, with
 * per-value page counts. Useful for "what clusters exist?" discovery queries.
 */
public class ListMetadataValuesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListMetadataValuesTool.class );
    public static final String TOOL_NAME = "list_metadata_values";

    private final ContextRetrievalService service;

    public ListMetadataValuesTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        // D13: canonical name is `field`. Accept the legacy `key` alias.
        properties.put( "field", Map.of(
            "type", "string",
            "description", "Frontmatter key (e.g. cluster, type, tags) to enumerate.",
            "examples", List.of( "cluster" )
        ) );
        properties.put( "key", Map.of(
            "type", "string",
            "description", "Deprecated alias for `field`.",
            "examples", List.of( "cluster" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "field", "cluster",
                "values", List.of(
                        Map.of( "value", "retrieval", "count", 14 ),
                        Map.of( "value", "agents", "count", 11 ),
                        Map.of( "value", "ops", "count", 7 )
                )
        ) ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Return distinct values of a frontmatter field across all pages, " +
                "with the count of pages for each value. Results are sorted by count descending." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            // D13: accept `field` or the deprecated `key`.
            final String field = McpToolUtils.getStringAny( arguments, "field", "key" );
            if ( arguments.containsKey( "key" ) && !arguments.containsKey( "field" ) ) {
                LOG.warn( "list_metadata_values called with deprecated argument 'key'; prefer 'field'" );
            }
            if ( field == null || field.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "field must not be blank" );
            }
            final List< MetadataValue > values = service.listMetadataValues( field );
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "field", field );
            payload.put( "values", values );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final RuntimeException e ) {
            LOG.error( "list_metadata_values failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}

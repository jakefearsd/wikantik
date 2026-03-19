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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;

import java.util.*;

/**
 * MCP tool that scans all pages and returns the distinct metadata field
 * names and their values. Useful for discovering what tags, types, and
 * other frontmatter fields are in use across the wiki.
 */
public class ListMetadataValuesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListMetadataValuesTool.class );
    public static final String TOOL_NAME = "list_metadata_values";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    public ListMetadataValuesTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "field", Map.of( "type", "string", "description",
                "If provided, return values for this field only. Otherwise return all fields." ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List distinct metadata field names and their values across all wiki pages. " +
                        "Returns {fields: {fieldName: [value1, value2, ...]}}. " +
                        "Use this to discover what tags, types, and other frontmatter fields are in use " +
                        "before creating or querying pages with query_metadata." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String filterField = McpToolUtils.getString( arguments, "field" );

        try {
            final Map< String, TreeSet< String > > fieldValues = new LinkedHashMap<>();
            final Collection< Page > allPages = pageManager.getAllPages();

            for ( final Page page : allPages ) {
                final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
                if ( text == null || text.isEmpty() ) {
                    continue;
                }

                final ParsedPage parsed = FrontmatterParser.parse( text );
                if ( parsed.metadata().isEmpty() ) {
                    continue;
                }

                for ( final Map.Entry< String, Object > entry : parsed.metadata().entrySet() ) {
                    final String fieldName = entry.getKey();
                    if ( filterField != null && !filterField.equals( fieldName ) ) {
                        continue;
                    }

                    final TreeSet< String > values = fieldValues.computeIfAbsent( fieldName, k -> new TreeSet<>() );
                    final Object val = entry.getValue();
                    if ( val instanceof List ) {
                        for ( final Object item : ( List< Object > ) val ) {
                            if ( item != null ) {
                                values.add( item.toString() );
                            }
                        }
                    } else if ( val != null ) {
                        values.add( val.toString() );
                    }
                }
            }

            // Convert TreeSets to Lists for JSON serialization
            final Map< String, Object > fields = new LinkedHashMap<>();
            for ( final Map.Entry< String, TreeSet< String > > entry : fieldValues.entrySet() ) {
                fields.put( entry.getKey(), new ArrayList<>( entry.getValue() ) );
            }

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "fields", fields ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list metadata values: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

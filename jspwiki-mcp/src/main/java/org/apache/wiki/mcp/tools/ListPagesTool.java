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
package org.apache.wiki.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.pages.PageManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that lists all wiki pages with optional prefix filtering.
 */
public class ListPagesTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesTool.class );
    public static final String TOOL_NAME = "list_pages";

    private final PageManager pageManager;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public ListPagesTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "prefix", Map.of( "type", "string", "description", "Optional prefix to filter page names" ) );
        properties.put( "limit", Map.of( "type", "integer", "description", "Maximum number of pages to return (default 100)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List all wiki pages with optional prefix filtering. " +
                        "Returns {pages: [{name, lastModified, author, size}]} sorted alphabetically. Default limit is 100." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String prefix = ( String ) arguments.getOrDefault( "prefix", null );
        final int limit = arguments.containsKey( "limit" )
                ? ( ( Number ) arguments.get( "limit" ) ).intValue()
                : 100;

        try {
            Collection< Page > allPages = pageManager.getAllPages();
            List< Map< String, Object > > pages = allPages.stream()
                    .filter( p -> prefix == null || p.getName().startsWith( prefix ) )
                    .sorted( Comparator.comparing( Page::getName ) )
                    .limit( limit )
                    .map( p -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "name", p.getName() );
                        entry.put( "lastModified", p.getLastModified() != null ? p.getLastModified().toInstant().toString() : null );
                        entry.put( "author", p.getAuthor() );
                        entry.put( "size", p.getSize() );
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "pages", pages ) ) ) ) )
                    .build();
        } catch ( final Exception e ) {
            LOG.error( "Failed to list pages: {}", e.getMessage(), e );
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "error", e.getMessage() ) ) ) ) )
                    .isError( true )
                    .build();
        }
    }
}

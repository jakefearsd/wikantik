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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.managers.PageManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that lists all wiki pages with optional prefix filtering.
 */
public class ListPagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesTool.class );
    public static final String TOOL_NAME = "list_pages";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public ListPagesTool( final PageManager pageManager, final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "prefix", Map.of( "type", "string", "description", "Optional prefix to filter page names" ) );
        properties.put( "limit", Map.of( "type", "integer", "description", "Maximum number of pages to return (default 100)" ) );
        properties.put( "includeSystemPages", Map.of( "type", "boolean", "description", "Include system/template pages in results (default false)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List all wiki pages with optional prefix filtering. " +
                        "Returns {pages: [{name, lastModified, author, size, systemPage}]} sorted alphabetically. " +
                        "System/template pages are excluded by default; set includeSystemPages=true to include them. Default limit is 100." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String prefix = McpToolUtils.getString( arguments, "prefix" );
        final int limit = McpToolUtils.getInt( arguments, "limit", 100 );
        final boolean includeSystemPages = McpToolUtils.getBoolean( arguments, "includeSystemPages" );

        try {
            Collection< Page > allPages = pageManager.getAllPages();
            List< Map< String, Object > > pages = allPages.stream()
                    .filter( p -> prefix == null || p.getName().startsWith( prefix ) )
                    .filter( p -> includeSystemPages || systemPageRegistry == null || !systemPageRegistry.isSystemPage( p.getName() ) )
                    .sorted( Comparator.comparing( Page::getName ) )
                    .limit( limit )
                    .map( p -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "name", p.getName() );
                        entry.put( "lastModified", p.getLastModified() != null ? p.getLastModified().toInstant().toString() : null );
                        entry.put( "author", p.getAuthor() );
                        entry.put( "size", p.getSize() );
                        if ( systemPageRegistry != null ) {
                            entry.put( "systemPage", systemPageRegistry.isSystemPage( p.getName() ) );
                        }
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            return McpToolUtils.jsonResult( gson, Map.of( "pages", pages ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list pages: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

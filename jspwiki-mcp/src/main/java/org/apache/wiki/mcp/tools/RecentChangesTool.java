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
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.pages.PageManager;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that returns recently modified wiki pages.
 */
public class RecentChangesTool {

    private static final Logger LOG = LogManager.getLogger( RecentChangesTool.class );
    public static final String TOOL_NAME = "recent_changes";

    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    public RecentChangesTool( final PageManager pageManager, final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "since", Map.of( "type", "string", "description", "ISO date/time to filter changes after (e.g. 2026-03-01T00:00:00Z)" ) );
        properties.put( "limit", Map.of( "type", "integer", "description", "Maximum number of results (default 50)" ) );
        properties.put( "includeSystemPages", Map.of( "type", "boolean", "description", "Include system/template pages in results (default false)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get recently modified wiki pages. " +
                        "Returns {changes: [{pageName, author, lastModified, changeNote, systemPage}]} newest first. " +
                        "System/template pages are excluded by default; set includeSystemPages=true to include them. Default limit is 50." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String sinceStr = ( String ) arguments.getOrDefault( "since", null );
        final int limit = arguments.containsKey( "limit" )
                ? ( ( Number ) arguments.get( "limit" ) ).intValue()
                : 50;
        final boolean includeSystemPages = Boolean.TRUE.equals( arguments.get( "includeSystemPages" ) );

        try {
            final Date sinceDate;
            if ( sinceStr != null ) {
                sinceDate = Date.from( Instant.parse( sinceStr ) );
            } else {
                sinceDate = null;
            }

            Set< Page > recentChanges = pageManager.getRecentChanges();
            List< Map< String, Object > > changes = recentChanges.stream()
                    .filter( p -> sinceDate == null || ( p.getLastModified() != null && !p.getLastModified().before( sinceDate ) ) )
                    .filter( p -> includeSystemPages || systemPageRegistry == null || !systemPageRegistry.isSystemPage( p.getName() ) )
                    .sorted( ( a, b ) -> {
                        if ( b.getLastModified() == null ) return -1;
                        if ( a.getLastModified() == null ) return 1;
                        return b.getLastModified().compareTo( a.getLastModified() );
                    } )
                    .limit( limit )
                    .map( p -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "pageName", p.getName() );
                        entry.put( "author", p.getAuthor() );
                        entry.put( "lastModified", p.getLastModified() != null ? p.getLastModified().toInstant().toString() : null );
                        final String changeNote = p.getAttribute( Page.CHANGENOTE );
                        entry.put( "changeNote", changeNote );
                        if ( systemPageRegistry != null ) {
                            entry.put( "systemPage", systemPageRegistry.isSystemPage( p.getName() ) );
                        }
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "changes", changes ) ) ) ) )
                    .build();
        } catch ( final Exception e ) {
            LOG.error( "Failed to get recent changes: {}", e.getMessage(), e );
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "error", e.getMessage() ) ) ) ) )
                    .isError( true )
                    .build();
        }
    }
}

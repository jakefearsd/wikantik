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
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that returns the version history of a wiki page.
 */
public class GetPageHistoryTool implements McpTool {

    public static final String TOOL_NAME = "get_page_history";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    public GetPageHistoryTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get the version history of a wiki page. " +
                        "Returns {exists, pageName, versions: [{version, author, lastModified, changeNote}]} " +
                        "newest first. Check the exists field first." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );

        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of(
                    "exists", false,
                    "pageName", pageName ) );
        }

        final List< Page > history = pageManager.getVersionHistory( pageName );
        final List< Map< String, Object > > versions = new ArrayList<>();

        if ( history != null ) {
            // Reverse to show newest first
            for ( int i = history.size() - 1; i >= 0; i-- ) {
                final Page ver = history.get( i );
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "version", McpToolUtils.normalizeVersion( ver.getVersion() ) );
                entry.put( "author", ver.getAuthor() );
                entry.put( "lastModified", ver.getLastModified() != null
                        ? ver.getLastModified().toInstant().toString() : null );
                entry.put( "changeNote", ver.getAttribute( Page.CHANGENOTE ) );
                versions.add( entry );
            }
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "exists", true );
        result.put( "pageName", pageName );
        result.put( "versions", versions );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}

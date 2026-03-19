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
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.content.PageRenamer;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.pages.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that renames a wiki page, optionally updating all pages that reference it.
 * Delegates to the JSPWiki {@link PageRenamer} which handles page moves, attachment
 * migration, and referrer updates atomically.
 */
public class RenamePageTool implements AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( RenamePageTool.class );
    public static final String TOOL_NAME = "rename_page";

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    private String defaultAuthor = "MCP";

    public RenamePageTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.systemPageRegistry = systemPageRegistry;
    }

    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "oldName", Map.of( "type", "string", "description", "Current name of the page to rename" ) );
        properties.put( "newName", Map.of( "type", "string", "description", "New name for the page (CamelCase)" ) );
        properties.put( "updateLinks", Map.of( "type", "boolean", "description",
                "If true (default), update all pages that reference the old name to point to the new name" ) );
        properties.put( "confirm", Map.of( "type", "boolean", "description",
                "Must be set to true to confirm the rename. This is a safety guard." ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Rename a wiki page, moving its content and optionally updating all referencing pages. " +
                        "Requires confirm=true as a safety guard. System pages cannot be renamed. " +
                        "Returns {success, oldName, newName, finalName}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "oldName", "newName", "confirm" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String oldName = McpToolUtils.getString( arguments, "oldName" );
        final String newName = McpToolUtils.getString( arguments, "newName" );
        final boolean updateLinks = arguments.containsKey( "updateLinks" )
                ? McpToolUtils.getBoolean( arguments, "updateLinks" )
                : true;
        final boolean confirm = McpToolUtils.getBoolean( arguments, "confirm" );

        if ( !confirm ) {
            return McpToolUtils.errorResult( gson,
                    "Rename not confirmed",
                    "Set confirm=true to confirm you want to rename '" + oldName + "' to '" + newName + "'." );
        }

        if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( oldName ) ) {
            return McpToolUtils.errorResult( gson,
                    "Cannot rename system page: " + oldName,
                    "System/template pages are managed by JSPWiki and should not be renamed." );
        }

        if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( newName ) ) {
            return McpToolUtils.errorResult( gson,
                    "Cannot rename to system page name: " + newName,
                    "The target name conflicts with a system/template page." );
        }

        try {
            final PageManager pageManager = engine.getManager( PageManager.class );
            final Page page = pageManager.getPage( oldName );
            if ( page == null ) {
                return McpToolUtils.errorResult( gson,
                        "Page not found: " + oldName,
                        "Use list_pages to find existing pages." );
            }

            final Context context = Wiki.context().create( engine, page );

            final PageRenamer renamer = engine.getManager( PageRenamer.class );
            final String finalName = renamer.renamePage( context, oldName, newName, updateLinks );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "oldName", oldName );
            result.put( "newName", newName );
            result.put( "finalName", finalName );
            result.put( "linksUpdated", updateLinks );
            return McpToolUtils.jsonResult( gson, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to rename page {} to {}: {}", oldName, newName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

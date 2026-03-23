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
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.managers.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that deletes a wiki page completely, including all versions.
 */
public class DeletePageTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( DeletePageTool.class );
    public static final String TOOL_NAME = "delete_page";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;

    public DeletePageTool( final PageManager pageManager, final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to delete" ) );
        properties.put( "confirm", Map.of( "type", "boolean", "description",
                "Must be set to true to confirm the deletion. This is a safety guard to prevent accidental deletions." ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Delete a wiki page completely, including all versions. " +
                        "Requires confirm=true as a safety guard. System pages cannot be deleted. " +
                        "Returns {success, pageName}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName", "confirm" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final boolean confirm = McpToolUtils.getBoolean( arguments, "confirm" );

        if ( !confirm ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Deletion not confirmed",
                    "Set confirm=true to confirm you want to permanently delete '" + pageName + "' and all its versions." );
        }

        if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Cannot delete system page: " + pageName,
                    "System/template pages are managed by JSPWiki and should not be deleted." );
        }

        try {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Page not found: " + pageName,
                        "Use list_pages to find existing pages." );
            }

            pageManager.deletePage( pageName );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to delete page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

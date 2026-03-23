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
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.managers.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that releases an edit lock on a wiki page. Idempotent — succeeds even
 * if no lock exists.
 */
public class UnlockPageTool implements McpTool {

    public static final String TOOL_NAME = "unlock_page";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    public UnlockPageTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the page to unlock" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Release an edit lock on a wiki page. Idempotent — succeeds even if no lock exists. " +
                        "Returns {success, pageName, wasLocked}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );

        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Page not found: " + pageName,
                    "Use list_pages to find existing pages." );
        }

        final PageLock currentLock = pageManager.getCurrentLock( page );
        final boolean wasLocked = currentLock != null;

        if ( currentLock != null ) {
            pageManager.unlockPage( currentLock );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "pageName", pageName );
        result.put( "wasLocked", wasLocked );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}

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
import com.wikantik.pages.PageLock;
import com.wikantik.pages.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that acquires an edit lock on a wiki page, preventing concurrent edits.
 */
public class LockPageTool implements McpTool {

    public static final String TOOL_NAME = "lock_page";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    private String defaultUser = "MCP";

    public LockPageTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    public void setDefaultUser( final String defaultUser ) {
        this.defaultUser = defaultUser;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the page to lock" ) );
        properties.put( "user", Map.of( "type", "string", "description",
                "User acquiring the lock (defaults to the MCP client name)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Acquire an edit lock on a wiki page. If the page is already locked by another user, " +
                        "returns an error with the current lock holder. " +
                        "Returns {success, pageName, locker, expiryTime, minutesLeft}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final String user = McpToolUtils.getString( arguments, "user", defaultUser );

        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Page not found: " + pageName,
                    "Use list_pages to find existing pages." );
        }

        // Check for existing lock by another user
        final PageLock currentLock = pageManager.getCurrentLock( page );
        if ( currentLock != null && !currentLock.isExpired() && !currentLock.getLocker().equals( user ) ) {
            final Map< String, Object > error = new LinkedHashMap<>();
            error.put( "error", "Page is locked by another user" );
            error.put( "currentLocker", currentLock.getLocker() );
            error.put( "expiryTime", currentLock.getExpiryTime().toString() );
            error.put( "minutesLeft", currentLock.getTimeLeft() );
            error.put( "suggestion", "Wait for the lock to expire or contact " + currentLock.getLocker() );
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( McpToolUtils.SHARED_GSON.toJson( error ) ) ) )
                    .isError( true )
                    .build();
        }

        final PageLock lock = pageManager.lockPage( page, user );
        if ( lock == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to acquire lock on page: " + pageName,
                    "The page may be locked by another user. Use unlock_page or wait for expiry." );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "pageName", pageName );
        result.put( "locker", lock.getLocker() );
        result.put( "expiryTime", lock.getExpiryTime().toString() );
        result.put( "minutesLeft", lock.getTimeLeft() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}

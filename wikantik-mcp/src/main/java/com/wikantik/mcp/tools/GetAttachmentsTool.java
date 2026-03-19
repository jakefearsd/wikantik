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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that lists attachments for a wiki page.
 */
public class GetAttachmentsTool {

    private static final Logger LOG = LogManager.getLogger( GetAttachmentsTool.class );
    public static final String TOOL_NAME = "get_attachments";

    private final PageManager pageManager;
    private final AttachmentManager attachmentManager;
    private final Gson gson = new Gson();

    public GetAttachmentsTool( final PageManager pageManager, final AttachmentManager attachmentManager ) {
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List attachments for a wiki page. " +
                        "Returns {exists, pageName, attachments: [{name, size, lastModified}]}. " +
                        "Check the exists field first — it is false when the page does not exist." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );

        try {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return McpToolUtils.jsonResult( gson, Map.of(
                        "exists", false,
                        "pageName", pageName,
                        "attachments", List.of() ) );
            }

            final List< Attachment > attachments = attachmentManager.listAttachments( page );
            final List< Map< String, Object > > result = attachments.stream()
                    .map( att -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "name", att.getFileName() );
                        entry.put( "size", att.getSize() );
                        entry.put( "lastModified", att.getLastModified() != null ? att.getLastModified().toInstant().toString() : null );
                        return entry;
                    } )
                    .collect( Collectors.toList() );

            final Map< String, Object > response = new LinkedHashMap<>();
            response.put( "exists", true );
            response.put( "pageName", pageName );
            response.put( "attachments", result );
            return McpToolUtils.jsonResult( gson, response );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list attachments for {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

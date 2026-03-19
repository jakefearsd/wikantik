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
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that uploads a file attachment to a wiki page via base64-encoded content.
 */
public class UploadAttachmentTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( UploadAttachmentTool.class );
    public static final String TOOL_NAME = "upload_attachment";

    @Override
    public String name() {
        return TOOL_NAME;
    }
    private static final int MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final WikiEngine engine;

    private String defaultAuthor = "MCP";

    public UploadAttachmentTool( final WikiEngine engine ) {
        this.engine = engine;
    }

    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to attach to" ) );
        properties.put( "fileName", Map.of( "type", "string", "description", "File name for the attachment (e.g. diagram.png)" ) );
        properties.put( "content", Map.of( "type", "string", "description",
                "Base64-encoded file content. Maximum decoded size is 10 MB." ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for this upload (defaults to the MCP client name)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Upload a file attachment to a wiki page. Content must be base64-encoded. " +
                        "Maximum file size is 10 MB. " +
                        "Returns {success, pageName, fileName, size}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "pageName", "fileName", "content" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final String fileName = McpToolUtils.getString( arguments, "fileName" );
        final String base64Content = McpToolUtils.getString( arguments, "content" );
        final String author = McpToolUtils.getString( arguments, "author" );

        try {
            final PageManager pageManager = engine.getManager( PageManager.class );
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Page not found: " + pageName,
                        "Create the page first with write_page, then attach files to it." );
            }

            final byte[] decoded = Base64.getDecoder().decode( base64Content );
            if ( decoded.length > MAX_SIZE_BYTES ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "File too large: " + decoded.length + " bytes (max " + MAX_SIZE_BYTES + ")",
                        "Reduce the file size to under 10 MB." );
            }

            final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );
            final Attachment att = Wiki.contents().attachment( engine, pageName, fileName );
            att.setAuthor( author != null ? author : defaultAuthor );

            attachmentManager.storeAttachment( att, new ByteArrayInputStream( decoded ) );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "fileName", fileName );
            result.put( "size", decoded.length );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final IllegalArgumentException e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Invalid base64 content: " + e.getMessage(),
                    "Ensure the content field contains valid base64-encoded data." );
        } catch ( final Exception e ) {
            LOG.error( "Failed to upload attachment {} to {}: {}", fileName, pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

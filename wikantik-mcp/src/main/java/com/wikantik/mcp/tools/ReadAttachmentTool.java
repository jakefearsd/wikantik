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
import com.wikantik.api.core.Attachment;
import com.wikantik.attachment.AttachmentManager;

import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that reads a file attachment from a wiki page, returning its content as base64.
 */
public class ReadAttachmentTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ReadAttachmentTool.class );
    public static final String TOOL_NAME = "read_attachment";

    @Override
    public String name() {
        return TOOL_NAME;
    }
    private static final int MAX_INLINE_BYTES = 1024 * 1024; // 1 MB

    private final AttachmentManager attachmentManager;

    public ReadAttachmentTool( final AttachmentManager attachmentManager ) {
        this.attachmentManager = attachmentManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );
        properties.put( "fileName", Map.of( "type", "string", "description", "File name of the attachment" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Read a file attachment from a wiki page. Returns base64-encoded content. " +
                        "Files larger than 1 MB return metadata only (contentTruncated=true). " +
                        "Returns {pageName, fileName, content, size, lastModified, version}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "pageName", "fileName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final String fileName = McpToolUtils.getString( arguments, "fileName" );

        try {
            final String fullName = pageName + "/" + fileName;
            final Attachment att = attachmentManager.getAttachmentInfo( fullName );
            if ( att == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Attachment not found: " + fullName,
                        "Use get_attachments to list available attachments for the page." );
            }

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "pageName", pageName );
            result.put( "fileName", fileName );
            result.put( "size", att.getSize() );
            result.put( "lastModified", att.getLastModified() != null ? att.getLastModified().toInstant().toString() : null );
            result.put( "version", McpToolUtils.normalizeVersion( att.getVersion() ) );

            if ( att.getSize() > MAX_INLINE_BYTES ) {
                result.put( "contentTruncated", true );
                result.put( "content", null );
            } else {
                try ( final InputStream in = attachmentManager.getAttachmentStream( att ) ) {
                    result.put( "content", Base64.getEncoder().encodeToString( in.readAllBytes() ) );
                    result.put( "contentTruncated", false );
                }
            }

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to read attachment {}/{}: {}", pageName, fileName, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

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
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.attachment.AttachmentManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that deletes a file attachment from a wiki page.
 */
public class DeleteAttachmentTool {

    private static final Logger LOG = LogManager.getLogger( DeleteAttachmentTool.class );
    public static final String TOOL_NAME = "delete_attachment";

    private final AttachmentManager attachmentManager;
    private final Gson gson = new Gson();

    public DeleteAttachmentTool( final AttachmentManager attachmentManager ) {
        this.attachmentManager = attachmentManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );
        properties.put( "fileName", Map.of( "type", "string", "description", "File name of the attachment to delete" ) );
        properties.put( "confirm", Map.of( "type", "boolean", "description",
                "Must be set to true to confirm the deletion. This is a safety guard." ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Delete a file attachment from a wiki page. " +
                        "Requires confirm=true as a safety guard. " +
                        "Returns {success, pageName, fileName}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "pageName", "fileName", "confirm" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final String fileName = McpToolUtils.getString( arguments, "fileName" );
        final boolean confirm = McpToolUtils.getBoolean( arguments, "confirm" );

        if ( !confirm ) {
            return McpToolUtils.errorResult( gson,
                    "Deletion not confirmed",
                    "Set confirm=true to confirm you want to permanently delete attachment '" + fileName + "' from '" + pageName + "'." );
        }

        try {
            final String fullName = pageName + "/" + fileName;
            final Attachment att = attachmentManager.getAttachmentInfo( fullName );
            if ( att == null ) {
                return McpToolUtils.errorResult( gson,
                        "Attachment not found: " + fullName,
                        "Use get_attachments to list available attachments for the page." );
            }

            attachmentManager.deleteAttachment( att );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "fileName", fileName );
            return McpToolUtils.jsonResult( gson, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to delete attachment {}/{}: {}", pageName, fileName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

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

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.PageProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: edit an existing wiki page with optimistic locking via
 * {@code expectedContentHash}. On hash mismatch, returns {updated:false,
 * error:"hash mismatch", currentHash} so the agent can re-fetch and retry.
 */
public class UpdatePageTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( UpdatePageTool.class );
    public static final String TOOL_NAME = "update_page";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;
    private String defaultAuthor = "mcp-agent";

    public UpdatePageTool( final PageSaveHelper saveHelper, final PageManager pageManager ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public void setDefaultAuthor( final String author ) {
        if ( author != null && !author.isBlank() ) {
            this.defaultAuthor = author;
        }
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string",
            "description", "Name of the existing page to update." ) );
        properties.put( "content", Map.of( "type", "string",
            "description", "New markdown body." ) );
        properties.put( "metadata", Map.of( "type", "object",
            "description", "Optional frontmatter metadata to merge." ) );
        properties.put( "expectedContentHash", Map.of( "type", "string",
            "description", "SHA-256 of the page's current raw text, obtained from " +
                "the last get_page or retrieve_context call. Required for optimistic locking." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Edit an existing page with optimistic locking. Returns " +
                "{updated, newContentHash, newVersion} on success or " +
                "{updated:false, error:'hash mismatch', currentHash} on drift so " +
                "the agent can re-fetch and retry." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties,
                List.of( "pageName", "content", "expectedContentHash" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String pageName = McpToolUtils.getString( arguments, "pageName" );
            final String content = McpToolUtils.getString( arguments, "content" );
            final String expectedHash = McpToolUtils.getString( arguments, "expectedContentHash" );
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageName must not be blank" );
            }
            if ( content == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "content must not be null" );
            }
            if ( expectedHash == null || expectedHash.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "expectedContentHash required" );
            }

            final Page existing = pageManager.getPage( pageName );
            if ( existing == null ) {
                final Map< String, Object > notFound = new LinkedHashMap<>();
                notFound.put( "pageName", pageName );
                notFound.put( "updated", false );
                notFound.put( "error", "not found" );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, notFound );
            }

            final String currentText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final String currentHash = McpToolUtils.computeContentHash(
                currentText == null ? "" : currentText );
            if ( !expectedHash.equals( currentHash ) ) {
                final Map< String, Object > mismatch = new LinkedHashMap<>();
                mismatch.put( "pageName", pageName );
                mismatch.put( "updated", false );
                mismatch.put( "error", "hash mismatch" );
                mismatch.put( "currentHash", currentHash );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, mismatch );
            }

            @SuppressWarnings( "unchecked" )
            final Map< String, Object > metadata = arguments.get( "metadata" ) instanceof Map< ?, ? >
                ? (Map< String, Object >) arguments.get( "metadata" ) : null;

            saveHelper.saveText( pageName, content,
                SaveOptions.builder()
                    .author( defaultAuthor )
                    .changeNote( "update_page" )
                    .markupSyntax( "markdown" )
                    .metadata( metadata == null || metadata.isEmpty() ? null : metadata )
                    .replaceMetadata( metadata != null && !metadata.isEmpty() )
                    .build() );
            McpAudit.logWrite( TOOL_NAME, "updated", pageName, defaultAuthor );

            final String newHash = McpToolUtils.computeContentHash( content );
            final Map< String, Object > ok = new LinkedHashMap<>();
            ok.put( "pageName", pageName );
            ok.put( "updated", true );
            ok.put( "newContentHash", newHash );
            ok.put( "newVersion", McpToolUtils.normalizeVersion( existing.getVersion() + 1 ) );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, ok );
        } catch ( final RuntimeException e ) {
            LOG.error( "update_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "update_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

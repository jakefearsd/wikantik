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
import org.apache.wiki.WikiEngine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.pages.PageSaveHelper;
import org.apache.wiki.pages.SaveOptions;
import org.apache.wiki.pages.VersionConflictException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that creates or updates a wiki page, with optional YAML frontmatter support.
 * Supports metadata merging, custom author attribution, and optimistic locking.
 */
public class WritePageTool implements AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( WritePageTool.class );
    public static final String TOOL_NAME = "write_page";

    private final SystemPageRegistry systemPageRegistry;
    private final PageSaveHelper pageSaveHelper;
    private final Gson gson = new Gson();

    private String defaultAuthor = "MCP";

    public WritePageTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.systemPageRegistry = systemPageRegistry;
        this.pageSaveHelper = new PageSaveHelper( engine );
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to create or update" ) );
        properties.put( "content", Map.of( "type", "string", "description", "The page body content (without frontmatter)" ) );
        properties.put( "metadata", Map.of( "type", "object", "description",
                "Optional YAML frontmatter fields. Merged with existing metadata by default (caller's fields win). " +
                "Set replaceMetadata=true to replace all existing metadata instead." ) );
        properties.put( "replaceMetadata", Map.of( "type", "boolean", "description",
                "If true, replace all existing metadata instead of merging (default false)" ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for this edit (defaults to the MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description", "Optional change note for the edit" ) );
        properties.put( "expectedVersion", Map.of( "type", "integer", "description",
                "If set, the write will fail unless the current page version matches this value (optimistic locking)" ) );
        properties.put( "expectedContentHash", Map.of( "type", "string", "description",
                "If set, the write will fail unless the current page's SHA-256 content hash matches this value (from read_page's contentHash field). " +
                "Can be used alone or with expectedVersion." ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Create or update a wiki page with optional YAML frontmatter. " +
                        "content is the Markdown body (without frontmatter delimiters); metadata is an object that becomes YAML frontmatter. " +
                        "Metadata is merged with existing frontmatter by default — set replaceMetadata=true to overwrite. " +
                        "Returns {success, pageName, version}. Always provide a changeNote describing the edit." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName", "content" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final String content = McpToolUtils.getString( arguments, "content" );
        final Map< String, Object > callerMetadata = ( Map< String, Object > ) arguments.get( "metadata" );
        final boolean replaceMetadata = McpToolUtils.getBoolean( arguments, "replaceMetadata" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final int expectedVersion = McpToolUtils.getInt( arguments, "expectedVersion", -1 );
        final String expectedContentHash = McpToolUtils.getString( arguments, "expectedContentHash" );
        // MCP always saves pages as Markdown — callers cannot create .txt (JSPWiki syntax) files
        final String markupSyntax = "markdown";

        try {
            final Page saved = pageSaveHelper.saveText( pageName, content,
                    SaveOptions.builder()
                            .author( author != null ? author : defaultAuthor )
                            .changeNote( changeNote )
                            .markupSyntax( markupSyntax )
                            .expectedVersion( expectedVersion )
                            .expectedContentHash( expectedContentHash )
                            .metadata( callerMetadata )
                            .replaceMetadata( replaceMetadata )
                            .build() );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                result.put( "systemPage", true );
                result.put( "warning", "This is a system/template page. Changes may be overwritten on upgrade." );
            }

            return McpToolUtils.jsonResult( gson, result );
        } catch ( final VersionConflictException e ) {
            return McpToolUtils.errorResult( gson, e.getMessage(),
                    "Read the page again with read_page to get the current version and content, then retry." );
        } catch ( final Exception e ) {
            LOG.error( "Failed to write page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

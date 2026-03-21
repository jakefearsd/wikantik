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
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSaveHelper;
import com.wikantik.pages.SaveOptions;
import com.wikantik.pages.VersionConflictException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that updates frontmatter metadata fields without touching the page body.
 */
public class UpdateMetadataTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( UpdateMetadataTool.class );
    public static final String TOOL_NAME = "update_metadata";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final PageSaveHelper pageSaveHelper;

    private String defaultAuthor = "MCP";

    public UpdateMetadataTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.systemPageRegistry = systemPageRegistry;
        this.pageSaveHelper = new PageSaveHelper( engine );
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > opSchema = new LinkedHashMap<>();
        opSchema.put( "type", "object" );
        opSchema.put( "properties", Map.of(
                "field", Map.of( "type", "string", "description", "Frontmatter field name" ),
                "action", Map.of( "type", "string", "description",
                        "Operation: set, append_to_list, remove_from_list, delete" ),
                "value", Map.of( "description", "Value to set/append/remove (not needed for delete)" )
        ) );
        opSchema.put( "required", List.of( "field", "action" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );
        properties.put( "operations", Map.of( "type", "array", "description",
                "Array of metadata operations to apply.", "items", opSchema ) );
        properties.put( "expectedVersion", Map.of( "type", "integer", "description",
                "Optimistic locking: fail if page version doesn't match" ) );
        properties.put( "expectedContentHash", Map.of( "type", "string", "description",
                "Optimistic locking: fail if page content SHA-256 hash doesn't match" ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for this edit (defaults to the MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description", "Optional change note" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Update frontmatter metadata fields without modifying the page body. " +
                        "Supports: set (overwrite field), append_to_list (add to list, idempotent), " +
                        "remove_from_list (remove from list), delete (remove field). " +
                        "Returns {success, pageName, version, contentHash, metadata}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName", "operations" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final List< Map< String, Object > > operations = ( List< Map< String, Object > > ) arguments.get( "operations" );
        final int expectedVersion = McpToolUtils.getInt( arguments, "expectedVersion", -1 );
        final String expectedContentHash = McpToolUtils.getString( arguments, "expectedContentHash" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );

        if ( operations == null || operations.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "No operations provided",
                    "Provide at least one operation with field and action." );
        }

        try {
            final PageManager pageManager = engine.getManager( PageManager.class );

            // Check page exists
            final Page currentPage = pageManager.getPage( pageName );
            if ( currentPage == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Page not found: " + pageName,
                        "Use write_page to create new pages, or list_pages to find existing pages." );
            }

            // Read and parse
            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );

            // Apply operations
            for ( final Map< String, Object > op : operations ) {
                final String field = ( String ) op.get( "field" );
                final String action = ( String ) op.get( "action" );
                final Object value = op.get( "value" );

                final String error = MetadataOperations.apply( metadata, field, action, value );
                if ( error != null ) {
                    return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, error );
                }
            }

            // Save with modified metadata via PageSaveHelper
            final Page saved = pageSaveHelper.saveText( pageName, parsed.body(),
                    SaveOptions.builder()
                            .author( author != null ? author : defaultAuthor )
                            .changeNote( changeNote )
                            .markupSyntax( "markdown" )
                            .expectedVersion( expectedVersion )
                            .expectedContentHash( expectedContentHash )
                            .metadata( metadata )
                            .replaceMetadata( true )
                            .build() );

            final String savedText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            result.put( "contentHash", McpToolUtils.computeContentHash( savedText ) );
            result.put( "metadata", metadata );

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final VersionConflictException e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage(),
                    "Read the page again with read_page to get the current version and content, then retry." );
        } catch ( final Exception e ) {
            LOG.error( "Failed to update metadata for page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }

}

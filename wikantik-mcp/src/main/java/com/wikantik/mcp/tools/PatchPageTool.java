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
 * MCP tool that applies section/marker-based patch operations to a wiki page
 * without requiring a full read-modify-write cycle from the client.
 */
public class PatchPageTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( PatchPageTool.class );
    public static final String TOOL_NAME = "patch_page";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final PageSaveHelper pageSaveHelper;

    private String defaultAuthor = "MCP";

    public PatchPageTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
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
                "action", Map.of( "type", "string", "description",
                        "Operation type: append_to_section, insert_before, insert_after, replace_section" ),
                "section", Map.of( "type", "string", "description",
                        "Heading text of the target section (for append_to_section, replace_section)" ),
                "marker", Map.of( "type", "string", "description",
                        "Text to find in the page body (for insert_before, insert_after)" ),
                "content", Map.of( "type", "string", "description", "Content to insert/append" )
        ) );
        opSchema.put( "required", List.of( "action", "content" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to patch" ) );
        properties.put( "operations", Map.of( "type", "array", "description",
                "Array of patch operations to apply sequentially to the page body.",
                "items", opSchema ) );
        properties.put( "expectedVersion", Map.of( "type", "integer", "description",
                "Optimistic locking: fail if page version doesn't match" ) );
        properties.put( "expectedContentHash", Map.of( "type", "string", "description",
                "Optimistic locking: fail if page content SHA-256 hash doesn't match (from read_page)" ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for this edit (defaults to the MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description", "Optional change note" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Apply patch operations to a wiki page's body without rewriting the entire page. " +
                        "Supports: append_to_section (add content at end of a named section), " +
                        "insert_before/insert_after (add content relative to a marker string), " +
                        "replace_section (replace a section's content, keeping the heading). " +
                        "Metadata/frontmatter is preserved unchanged. " +
                        "Returns {success, pageName, version, contentHash}." )
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
                    "Provide at least one operation with action and content." );
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

            // Read and parse current content
            final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( rawText );

            // Apply operations to body only
            final String patchedBody = ContentPatcher.applyOperations( parsed.body(), operations );

            // Save with preserved metadata via PageSaveHelper
            final Page saved = pageSaveHelper.saveText( pageName, patchedBody,
                    SaveOptions.builder()
                            .author( author != null ? author : defaultAuthor )
                            .changeNote( changeNote )
                            .expectedVersion( expectedVersion )
                            .expectedContentHash( expectedContentHash )
                            .metadata( parsed.metadata().isEmpty() ? null : parsed.metadata() )
                            .replaceMetadata( true )
                            .build() );

            final String savedText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            result.put( "contentHash", McpToolUtils.computeContentHash( savedText ) );

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final VersionConflictException e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage(),
                    "Read the page again with read_page to get the current version and content, then retry." );
        } catch ( final PatchException e ) {
            final String msg = e.getMessage();
            return e.getSuggestion() != null
                    ? McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, msg, e.getSuggestion() )
                    : McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, msg );
        } catch ( final Exception e ) {
            LOG.error( "Failed to patch page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}

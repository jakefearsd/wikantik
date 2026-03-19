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
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSaveHelper;
import com.wikantik.pages.SaveOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that applies patch operations to multiple wiki pages in a single call (best-effort).
 */
public class BatchPatchPagesTool implements AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( BatchPatchPagesTool.class );
    public static final String TOOL_NAME = "batch_patch_pages";

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final PageSaveHelper pageSaveHelper;
    private final Gson gson = new Gson();

    private String defaultAuthor = "MCP";

    public BatchPatchPagesTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.systemPageRegistry = systemPageRegistry;
        this.pageSaveHelper = new PageSaveHelper( engine );
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > opSchema = new LinkedHashMap<>();
        opSchema.put( "type", "object" );
        opSchema.put( "properties", Map.of(
                "action", Map.of( "type", "string", "description",
                        "Operation type: append_to_section, insert_before, insert_after, replace_section" ),
                "section", Map.of( "type", "string", "description", "Heading text of the target section" ),
                "marker", Map.of( "type", "string", "description", "Text to find in the page body" ),
                "content", Map.of( "type", "string", "description", "Content to insert/append" )
        ) );
        opSchema.put( "required", List.of( "action", "content" ) );

        final Map< String, Object > pageSchema = new LinkedHashMap<>();
        pageSchema.put( "type", "object" );
        pageSchema.put( "properties", Map.of(
                "pageName", Map.of( "type", "string", "description", "Name of the wiki page to patch" ),
                "operations", Map.of( "type", "array", "description", "Patch operations for this page", "items", opSchema ),
                "changeNote", Map.of( "type", "string", "description", "Optional change note for this page" )
        ) );
        pageSchema.put( "required", List.of( "pageName", "operations" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of( "type", "array", "description",
                "Array of pages to patch. Each requires pageName and operations.",
                "items", pageSchema ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for all patches (defaults to the MCP client name)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Apply patch operations to multiple wiki pages in a single call. Best-effort: each page is patched independently, " +
                        "and failures for one page do not prevent others from being patched. " +
                        "Returns {results: [{pageName, success, version?, contentHash?, error?}]}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pages" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) arguments.get( "pages" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( pages == null || pages.isEmpty() ) {
            return McpToolUtils.errorResult( gson,
                    "No pages provided",
                    "Provide an array of {pageName, operations} objects in the pages parameter." );
        }

        final PageManager pageManager = engine.getManager( PageManager.class );
        final List< Map< String, Object > > results = new ArrayList<>();

        for ( final Map< String, Object > pageSpec : pages ) {
            final String pageName = ( String ) pageSpec.get( "pageName" );
            final List< Map< String, Object > > operations = ( List< Map< String, Object > > ) pageSpec.get( "operations" );
            final String changeNote = ( String ) pageSpec.get( "changeNote" );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            try {
                // Check page exists
                final Page currentPage = pageManager.getPage( pageName );
                if ( currentPage == null ) {
                    entry.put( "success", false );
                    entry.put( "error", "Page not found: " + pageName );
                    results.add( entry );
                    continue;
                }

                // Read and parse
                final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );

                // Apply operations
                final String patchedBody = ContentPatcher.applyOperations( parsed.body(), operations );

                // Save with preserved metadata via PageSaveHelper
                final Page saved = pageSaveHelper.saveText( pageName, patchedBody,
                        SaveOptions.builder()
                                .author( effectiveAuthor )
                                .changeNote( changeNote )
                                .metadata( parsed.metadata().isEmpty() ? null : parsed.metadata() )
                                .replaceMetadata( true )
                                .build() );

                final String savedText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                entry.put( "success", true );
                entry.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
                entry.put( "contentHash", McpToolUtils.computeContentHash( savedText ) );
            } catch ( final PatchException e ) {
                entry.put( "success", false );
                entry.put( "error", e.getMessage() );
            } catch ( final Exception e ) {
                LOG.error( "Failed to patch page {} in batch: {}", pageName, e.getMessage(), e );
                entry.put( "success", false );
                entry.put( "error", e.getMessage() );
            }

            results.add( entry );
        }

        return McpToolUtils.jsonResult( gson, Map.of( "results", results ) );
    }
}

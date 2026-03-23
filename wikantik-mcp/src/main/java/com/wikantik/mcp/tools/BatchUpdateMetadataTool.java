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
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
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
 * MCP tool that updates frontmatter metadata on multiple wiki pages in a single call (best-effort).
 * Each page is processed independently — failures for one page do not block others.
 */
public class BatchUpdateMetadataTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( BatchUpdateMetadataTool.class );
    public static final String TOOL_NAME = "batch_update_metadata";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageSaveHelper pageSaveHelper;
    private final PageManager pageManager;

    private String defaultAuthor = "MCP";

    public BatchUpdateMetadataTool( final PageSaveHelper pageSaveHelper, final PageManager pageManager ) {
        this.pageSaveHelper = pageSaveHelper;
        this.pageManager = pageManager;
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

        final Map< String, Object > pageSchema = new LinkedHashMap<>();
        pageSchema.put( "type", "object" );
        pageSchema.put( "properties", Map.of(
                "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ),
                "operations", Map.of( "type", "array", "description", "Metadata operations for this page", "items", opSchema )
        ) );
        pageSchema.put( "required", List.of( "pageName", "operations" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of( "type", "array", "description",
                "Array of pages to update. Each requires pageName and operations.",
                "items", pageSchema ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for all updates (defaults to the MCP client name)" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description",
                "Optional change note applied to all pages" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Update frontmatter metadata on multiple pages in a single call. Best-effort: each page is updated independently, " +
                        "and failures for one page do not prevent others from being updated. " +
                        "Supports: set, append_to_list (idempotent), remove_from_list, delete. " +
                        "Returns {results: [{pageName, success, version?, contentHash?, metadata?, error?}]}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pages" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) arguments.get( "pages" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( pages == null || pages.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No pages provided",
                    "Provide an array of {pageName, operations} objects in the pages parameter." );
        }

        final List< Map< String, Object > > results = new ArrayList<>();

        for ( final Map< String, Object > pageSpec : pages ) {
            final String pageName = ( String ) pageSpec.get( "pageName" );
            final List< Map< String, Object > > operations = ( List< Map< String, Object > > ) pageSpec.get( "operations" );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            try {
                final Page currentPage = pageManager.getPage( pageName );
                if ( currentPage == null ) {
                    entry.put( "success", false );
                    entry.put( "error", "Page not found: " + pageName );
                    results.add( entry );
                    continue;
                }

                final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );
                final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );

                // Apply all operations for this page
                String opError = null;
                for ( final Map< String, Object > op : operations ) {
                    final String field = ( String ) op.get( "field" );
                    final String action = ( String ) op.get( "action" );
                    final Object value = op.get( "value" );

                    opError = MetadataOperations.apply( metadata, field, action, value );
                    if ( opError != null ) {
                        break;
                    }
                }

                if ( opError != null ) {
                    entry.put( "success", false );
                    entry.put( "error", opError );
                    results.add( entry );
                    continue;
                }

                final Page saved = pageSaveHelper.saveText( pageName, parsed.body(),
                        SaveOptions.builder()
                                .author( effectiveAuthor )
                                .changeNote( changeNote )
                                .markupSyntax( "markdown" )
                                .metadata( metadata )
                                .replaceMetadata( true )
                                .build() );

                final String savedText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                entry.put( "success", true );
                entry.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
                entry.put( "contentHash", McpToolUtils.computeContentHash( savedText ) );
                entry.put( "metadata", metadata );
            } catch ( final Exception e ) {
                LOG.error( "Failed to update metadata for page {} in batch: {}", pageName, e.getMessage(), e );
                entry.put( "success", false );
                entry.put( "error", e.getMessage() );
            }

            results.add( entry );
        }

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "results", results ) );
    }
}

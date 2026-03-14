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
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.frontmatter.FrontmatterParser;
import org.apache.wiki.frontmatter.FrontmatterWriter;
import org.apache.wiki.frontmatter.ParsedPage;
import org.apache.wiki.pages.PageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that writes multiple wiki pages in a single call (best-effort, not transactional).
 */
public class BatchWritePagesTool {

    private static final Logger LOG = LogManager.getLogger( BatchWritePagesTool.class );
    public static final String TOOL_NAME = "batch_write_pages";

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    private String defaultAuthor = "MCP";

    public BatchWritePagesTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.systemPageRegistry = systemPageRegistry;
    }

    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > pageSchema = new LinkedHashMap<>();
        pageSchema.put( "type", "object" );
        pageSchema.put( "properties", Map.of(
                "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ),
                "content", Map.of( "type", "string", "description", "The page body content (without frontmatter)" ),
                "metadata", Map.of( "type", "object", "description", "Optional YAML frontmatter fields" ),
                "changeNote", Map.of( "type", "string", "description", "Optional change note" )
        ) );
        pageSchema.put( "required", List.of( "pageName", "content" ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of( "type", "array", "description",
                "Array of pages to write. Each requires pageName and content.",
                "items", pageSchema ) );
        properties.put( "author", Map.of( "type", "string", "description",
                "Author name for all writes (defaults to the MCP client name)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Write multiple wiki pages in a single call. Best-effort: each page is written independently, " +
                        "and failures for one page do not prevent others from being written. Metadata is merged with existing " +
                        "frontmatter by default. Returns {results: [{pageName, success, version?, error?}]}." )
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
                    "Provide an array of {pageName, content} objects in the pages parameter." );
        }

        final PageManager pageManager = engine.getManager( PageManager.class );
        final List< Map< String, Object > > results = new ArrayList<>();

        for ( final Map< String, Object > pageSpec : pages ) {
            final String pageName = ( String ) pageSpec.get( "pageName" );
            final String content = ( String ) pageSpec.get( "content" );
            final Map< String, Object > callerMetadata = ( Map< String, Object > ) pageSpec.get( "metadata" );
            final String changeNote = ( String ) pageSpec.get( "changeNote" );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            try {
                // Merge metadata with existing
                final Map< String, Object > effectiveMetadata;
                if ( callerMetadata != null ) {
                    effectiveMetadata = mergeMetadata( pageManager, pageName, callerMetadata );
                } else {
                    effectiveMetadata = null;
                }

                final String fullText = FrontmatterWriter.write( effectiveMetadata, content );

                final Page page = Wiki.contents().page( engine, pageName );
                page.setAuthor( effectiveAuthor );
                page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
                if ( changeNote != null ) {
                    page.setAttribute( Page.CHANGENOTE, changeNote );
                }
                final Context context = Wiki.context().create( engine, page );
                pageManager.saveText( context, fullText );

                final Page saved = pageManager.getPage( pageName );
                entry.put( "success", true );
                entry.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            } catch ( final Exception e ) {
                LOG.error( "Failed to write page {} in batch: {}", pageName, e.getMessage(), e );
                entry.put( "success", false );
                entry.put( "error", e.getMessage() );
            }

            results.add( entry );
        }

        return McpToolUtils.jsonResult( gson, Map.of( "results", results ) );
    }

    private Map< String, Object > mergeMetadata( final PageManager pageManager,
                                                  final String pageName,
                                                  final Map< String, Object > callerMetadata ) {
        final String existingText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        if ( existingText == null || existingText.isEmpty() ) {
            return callerMetadata;
        }

        final ParsedPage parsed = FrontmatterParser.parse( existingText );
        if ( parsed.metadata().isEmpty() ) {
            return callerMetadata;
        }

        final Map< String, Object > merged = new LinkedHashMap<>( parsed.metadata() );
        merged.putAll( callerMetadata );
        return merged;
    }
}

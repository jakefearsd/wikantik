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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that creates or updates a wiki page, with optional YAML frontmatter support.
 * Supports metadata merging, custom author attribution, and optimistic locking.
 */
public class WritePageTool {

    private static final Logger LOG = LogManager.getLogger( WritePageTool.class );
    public static final String TOOL_NAME = "write_page";

    private final WikiEngine engine;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    private String defaultAuthor = "MCP";

    public WritePageTool( final WikiEngine engine, final SystemPageRegistry systemPageRegistry ) {
        this.engine = engine;
        this.systemPageRegistry = systemPageRegistry;
    }

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

        try {
            final PageManager pageManager = engine.getManager( PageManager.class );

            // Optimistic locking: check current version if expectedVersion is set
            if ( expectedVersion > 0 ) {
                final Page currentPage = pageManager.getPage( pageName );
                if ( currentPage != null ) {
                    final int currentVersion = McpToolUtils.normalizeVersion( currentPage.getVersion() );
                    if ( currentVersion != expectedVersion ) {
                        return McpToolUtils.errorResult( gson,
                                "Version conflict: page '" + pageName + "' is at version " + currentVersion +
                                        " but expectedVersion was " + expectedVersion,
                                "Read the page again with read_page to get the current version and content, then retry your write." );
                    }
                }
            }

            // Merge metadata: read existing frontmatter and overlay caller's fields
            final Map< String, Object > effectiveMetadata;
            if ( replaceMetadata || callerMetadata == null ) {
                effectiveMetadata = callerMetadata;
            } else {
                effectiveMetadata = mergeMetadata( pageManager, pageName, callerMetadata );
            }

            final String fullText = FrontmatterWriter.write( effectiveMetadata, content );

            final Page page = Wiki.contents().page( engine, pageName );
            page.setAuthor( author != null ? author : defaultAuthor );
            page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
            if ( changeNote != null ) {
                page.setAttribute( Page.CHANGENOTE, changeNote );
            }
            final Context context = Wiki.context().create( engine, page );
            pageManager.saveText( context, fullText );

            final Page saved = pageManager.getPage( pageName );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                result.put( "systemPage", true );
                result.put( "warning", "This is a system/template page. Changes may be overwritten on upgrade." );
            }

            return McpToolUtils.jsonResult( gson, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to write page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
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

        // Start with existing metadata, overlay caller's fields
        final Map< String, Object > merged = new LinkedHashMap<>( parsed.metadata() );
        merged.putAll( callerMetadata );
        return merged;
    }
}

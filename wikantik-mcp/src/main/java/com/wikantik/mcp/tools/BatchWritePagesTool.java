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
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that writes multiple wiki pages in a single call (best-effort, not transactional).
 */
public class BatchWritePagesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( BatchWritePagesTool.class );
    public static final String TOOL_NAME = "batch_write_pages";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageSaveHelper pageSaveHelper;

    private String defaultAuthor = "MCP";

    public BatchWritePagesTool( final PageSaveHelper pageSaveHelper ) {
        this.pageSaveHelper = pageSaveHelper;
    }

    @Override
    public void setDefaultAuthor( final String defaultAuthor ) {
        this.defaultAuthor = defaultAuthor;
    }

    @Override
    public McpSchema.Tool definition() {
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
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) arguments.get( "pages" );
        final String author = McpToolUtils.getString( arguments, "author" );
        final String effectiveAuthor = author != null ? author : defaultAuthor;

        if ( pages == null || pages.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "No pages provided",
                    "Provide an array of {pageName, content} objects in the pages parameter." );
        }

        final List< Map< String, Object > > results = new ArrayList<>();

        for ( final Map< String, Object > pageSpec : pages ) {
            final String pageName = ( String ) pageSpec.get( "pageName" );
            final String content = ( String ) pageSpec.get( "content" );
            final Map< String, Object > callerMetadata = ( Map< String, Object > ) pageSpec.get( "metadata" );
            final String changeNote = ( String ) pageSpec.get( "changeNote" );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            final McpSchema.CallToolResult contentCheck = McpToolUtils.checkForSerializedResponse( content );
            if ( contentCheck != null ) {
                entry.put( "success", false );
                entry.put( "error", "Content appears to be a serialized JSON response — pass only the Markdown body" );
                results.add( entry );
                continue;
            }

            try {
                final Page saved = pageSaveHelper.saveText( pageName, content,
                        SaveOptions.builder()
                                .author( effectiveAuthor )
                                .changeNote( changeNote )
                                .markupSyntax( "markdown" )
                                .metadata( callerMetadata )
                                .build() );

                entry.put( "success", true );
                entry.put( "version", saved != null ? McpToolUtils.normalizeVersion( saved.getVersion() ) : 1 );
            } catch ( final Exception e ) {
                LOG.error( "Failed to write page {} in batch: {}", pageName, e.getMessage(), e );
                entry.put( "success", false );
                entry.put( "error", e.getMessage() );
            }

            results.add( entry );
        }

        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "results", results ) );
    }
}

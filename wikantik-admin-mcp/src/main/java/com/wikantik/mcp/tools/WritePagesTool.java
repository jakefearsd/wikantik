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
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: batch-create new wiki pages. Fails individual pages that already
 * exist (callers should use {@code update_page} for those). Best-effort —
 * one page's failure doesn't stop the rest.
 */
public class WritePagesTool extends DefaultAuthorTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( WritePagesTool.class );
    public static final String TOOL_NAME = "write_pages";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;

    public WritePagesTool( final PageSaveHelper saveHelper, final PageManager pageManager ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > pageSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "pageName", Map.of( "type", "string" ),
                "content",  Map.of( "type", "string" ),
                "metadata", Map.of( "type", "object" ) ),
            "required", List.of( "pageName", "content" ) );
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of(
            "type", "array",
            "items", pageSchema,
            "description", "Pages to create. Each item: {pageName, content, metadata?}." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Batch-create new wiki pages. Fails individual pages that already exist " +
                "— use update_page for those. Per-page {created, error?} results let the agent " +
                "retry only the failures." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "pages" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Object rawPages = arguments.get( "pages" );
        if ( !( rawPages instanceof List< ? > ) || ( (List< ? >) rawPages ).isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                "pages must be a non-empty array" );
        }
        final List< Map< String, Object > > pages = (List< Map< String, Object > >) rawPages;

        final List< Map< String, Object > > results = new ArrayList<>();
        int createdCount = 0;
        int failedCount = 0;
        for ( final Map< String, Object > p : pages ) {
            final String pageName = asString( p.get( "pageName" ) );
            final String content = asString( p.get( "content" ) );
            final Map< String, Object > metadata = asMap( p.get( "metadata" ) );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );
            if ( pageName == null || pageName.isBlank() ) {
                entry.put( "created", false );
                entry.put( "error", "pageName must not be blank" );
                results.add( entry );
                failedCount++;
                continue;
            }
            if ( content == null ) {
                entry.put( "created", false );
                entry.put( "error", "content must not be null" );
                results.add( entry );
                failedCount++;
                continue;
            }
            final Page existing = pageManager.getPage( pageName );
            if ( existing != null ) {
                entry.put( "created", false );
                entry.put( "error", "already exists" );
                results.add( entry );
                failedCount++;
                continue;
            }
            try {
                saveHelper.saveText( pageName, content,
                    SaveOptions.builder()
                        .author( defaultAuthor )
                        .changeNote( "write_pages" )
                        .markupSyntax( "markdown" )
                        .metadata( metadata == null || metadata.isEmpty() ? null : metadata )
                        .replaceMetadata( true )
                        .build() );
                McpAudit.logWrite( TOOL_NAME, "created", pageName, defaultAuthor );
                entry.put( "created", true );
                results.add( entry );
                createdCount++;
            } catch ( final Exception e ) {
                LOG.error( "write_pages failed for '{}': {}", pageName, e.getMessage(), e );
                entry.put( "created", false );
                entry.put( "error", e.getMessage() );
                results.add( entry );
                failedCount++;
            }
        }

        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "results", results );
        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "total", pages.size() );
        summary.put( "createdCount", createdCount );
        summary.put( "failedCount", failedCount );
        payload.put( "summary", summary );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, payload );
    }

    private static String asString( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > asMap( final Object o ) {
        if ( o instanceof Map< ?, ? > ) return (Map< String, Object >) o;
        return null;
    }
}

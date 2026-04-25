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
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.managers.SystemPageRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: batch-delete wiki pages.
 *
 * <p>Refuses to delete system pages. Requires {@code confirm=true} as a safety
 * guard. If {@code allowWithBacklinks} is not true and a page still has inbound
 * references, that page is skipped with a reason — the caller can re-invoke with
 * the flag set once they've reviewed the referrers.</p>
 */
public class DeletePagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( DeletePagesTool.class );
    public static final String TOOL_NAME = "delete_pages";

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;
    private final SystemPageRegistry systemPageRegistry;

    public DeletePagesTool( final PageManager pageManager,
                            final ReferenceManager referenceManager,
                            final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageNames", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Names of pages to delete (without .md extension).",
                "examples", List.of( List.of( "AbandonedDraft", "OldRunbook" ) )
        ) );
        properties.put( "confirm", Map.of(
                "type", "boolean",
                "description", "Must be true to actually delete. Safety guard.",
                "examples", List.of( true )
        ) );
        properties.put( "allowWithBacklinks", Map.of(
                "type", "boolean",
                "description", "If true, delete pages even if other pages link to them. " +
                        "Default false — pages with inbound refs are skipped so the caller can review.",
                "examples", List.of( false )
        ) );
        properties.put( "changeNote", Map.of(
                "type", "string",
                "description", "Optional note recorded in the audit log.",
                "examples", List.of( "removing dead drafts after 90-day audit" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "results", List.of(
                        Map.of( "pageName", "AbandonedDraft", "deleted", true ),
                        Map.of(
                                "pageName", "OldRunbook",
                                "deleted", false,
                                "error", "skipped: has backlinks; pass allowWithBacklinks=true to override",
                                "backlinks", List.of( "Main", "AgentMemory" )
                        )
                ),
                "summary", Map.of(
                        "deletedCount", 1,
                        "skippedCount", 1,
                        "failedCount", 0
                )
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Batch-delete wiki pages. Refuses system pages. Requires confirm=true. " +
                        "Pages with inbound backlinks are skipped unless allowWithBacklinks=true. " +
                        "Per-page {pageName, deleted, error?, backlinks?} results let the caller " +
                        "retry only the skipped items." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "pageNames", "confirm" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "pageNames" );
        if ( !( raw instanceof List< ? > ) || ( (List< ? >) raw ).isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageNames must be a non-empty array of strings" );
        }
        final boolean confirm = McpToolUtils.getBoolean( arguments, "confirm" );
        if ( !confirm ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Delete not confirmed",
                    "Set confirm=true to actually delete the listed pages." );
        }
        final boolean allowWithBacklinks = McpToolUtils.getBoolean( arguments, "allowWithBacklinks" );
        final String changeNote = McpToolUtils.getString( arguments, "changeNote" );
        final List< String > pageNames = (List< String >) raw;

        final List< Map< String, Object > > results = new ArrayList<>();
        int deletedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for ( final Object o : pageNames ) {
            final String pageName = o == null ? null : o.toString().trim();
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );

            if ( pageName == null || pageName.isBlank() ) {
                entry.put( "deleted", false );
                entry.put( "error", "blank pageName" );
                results.add( entry );
                failedCount++;
                continue;
            }

            if ( systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName ) ) {
                entry.put( "deleted", false );
                entry.put( "error", "system page — refusing to delete" );
                results.add( entry );
                skippedCount++;
                continue;
            }

            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                entry.put( "deleted", false );
                entry.put( "error", "page not found" );
                results.add( entry );
                skippedCount++;
                continue;
            }

            if ( !allowWithBacklinks && referenceManager != null ) {
                final Collection< String > backlinks = referenceManager.findReferrers( pageName );
                if ( backlinks != null && !backlinks.isEmpty() ) {
                    entry.put( "deleted", false );
                    entry.put( "error", "has " + backlinks.size() + " inbound backlinks — "
                            + "set allowWithBacklinks=true to delete anyway" );
                    entry.put( "backlinks", new ArrayList<>( backlinks ) );
                    results.add( entry );
                    skippedCount++;
                    continue;
                }
            }

            try {
                pageManager.deletePage( pageName );
                McpAudit.logWrite( TOOL_NAME, "deleted",
                        pageName + ( changeNote == null ? "" : " note=" + changeNote ), null );
                entry.put( "deleted", true );
                results.add( entry );
                deletedCount++;
            } catch ( final Exception e ) {
                LOG.error( "delete_pages failed for '{}': {}", pageName, e.getMessage(), e );
                entry.put( "deleted", false );
                entry.put( "error", e.getMessage() );
                results.add( entry );
                failedCount++;
            }
        }

        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "results", results );
        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "total", pageNames.size() );
        summary.put( "deletedCount", deletedCount );
        summary.put( "skippedCount", skippedCount );
        summary.put( "failedCount", failedCount );
        payload.put( "summary", summary );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, payload );
    }
}

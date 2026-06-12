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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: batched raw-markdown reads for up to 20 pages per call. Per-page
 * failures (not_found, internal_error) come back as data on a 200 response;
 * the call only fails on input validation (empty slugs, over cap).
 *
 * <p>Mirrors the single-page {@code read_page} tool on /wikantik-admin-mcp,
 * but lives on /knowledge-mcp as part of the agent-grade content surface.</p>
 */
public class ReadPagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ReadPagesTool.class );
    public static final String TOOL_NAME = "read_pages";
    private static final int MAX_SLUGS = 20;

    private final PageManager pageManager;
    private final ReadPagesMetrics metrics;

    public ReadPagesTool( final PageManager pageManager, final ReadPagesMetrics metrics ) {
        this.pageManager = pageManager;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > slugsProp = new LinkedHashMap<>();
        slugsProp.put( "type", "array" );
        slugsProp.put( "items", Map.of( "type", "string" ) );
        slugsProp.put( "minItems", 1 );
        slugsProp.put( "maxItems", MAX_SLUGS );
        slugsProp.put( "description", "Page slugs to read in one batch (max " + MAX_SLUGS + ")." );
        slugsProp.put( "examples", List.of( List.of( "HybridRetrieval", "AgentGradeContentDesign" ) ) );

        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "slugs", slugsProp );

        final Map< String, Object > missingEntry = new LinkedHashMap<>();
        missingEntry.put( "slug", "Missing" );
        missingEntry.put( "content", null );
        missingEntry.put( "error", "not_found" );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "pages", List.of(
                        Map.of( "slug", "HybridRetrieval", "content", "---\ntitle: Hybrid Retrieval\n---\n# Hybrid Retrieval\n…" ),
                        missingEntry
                ) ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Batched raw-markdown read for up to " + MAX_SLUGS + " pages. " +
                              "Per-page failures (not_found, internal_error) are returned as data on " +
                              "a 200 response; the call only fails on input validation. Use " +
                              "get_page for a single-page metadata fetch when you only need the summary." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "slugs" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    /** First list-valued argument among {@code keys}, or null. Lets the tool accept page-id aliases. */
    private static Object firstListArg( final Map< String, Object > args, final String... keys ) {
        if ( args == null ) { return null; }
        for ( final String k : keys ) {
            if ( args.get( k ) instanceof List< ? > l ) { return l; }
        }
        return null;
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        // Input validation. Canonical key is `slugs`; accept synonyms an agent may carry over from
        // other tools (e.g. admin MCP `pageNames`) so a reasonable call doesn't hard-fail.
        final Object raw = firstListArg( arguments, "slugs", "pageNames", "names", "pages" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "a page-id list is required and must contain at least one entry (one of: slugs, pageNames, names)" );
        }
        if ( rawList.size() > MAX_SLUGS ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "slugs exceeds cap of " + MAX_SLUGS + " entries" );
        }

        // Dedupe preserving input order
        final LinkedHashSet< String > slugs = new LinkedHashSet<>();
        for ( final Object o : rawList ) {
            if ( o == null ) continue;
            final String s = o.toString().trim();
            if ( !s.isEmpty() ) slugs.add( s );
        }
        if ( slugs.isEmpty() ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "slugs is required and must contain at least one entry" );
        }

        final List< Map< String, Object > > out = new ArrayList<>( slugs.size() );
        for ( final String slug : slugs ) {
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "slug", slug );
            try {
                final Page page = pageManager.getPage( slug, PageProvider.LATEST_VERSION );
                if ( page == null ) {
                    entry.put( "content", null );
                    entry.put( "error", "not_found" );
                    if ( metrics != null ) metrics.recordPartialFailure( "not_found" );
                } else {
                    final String text = pageManager.getPureText( slug, PageProvider.LATEST_VERSION );
                    entry.put( "content", text == null ? "" : text );
                }
            } catch ( final Exception e ) {
                LOG.warn( "read_pages: lookup failed for {}: {}", slug, e.getMessage() );
                entry.put( "content", null );
                entry.put( "error", "internal_error" );
                if ( metrics != null ) metrics.recordPartialFailure( "internal_error" );
            }
            out.add( entry );
        }

        return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, Map.of( "pages", out ) );
    }
}

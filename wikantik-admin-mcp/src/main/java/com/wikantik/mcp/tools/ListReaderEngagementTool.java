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

import com.wikantik.insights.readers.PageReaderStats;
import com.wikantik.insights.readers.ReaderReport;
import com.wikantik.insights.readers.ReaderReportView;
import com.wikantik.mcp.AbstractMcpTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only agent view of reader engagement: per page, how many people read it, how long they were
 * actively engaged, how far they scrolled, where they came from and whether they stayed. Read-back
 * numbers from the analytics rollups; an empty {@code pages} list means no data, not no readers.
 */
public class ListReaderEngagementTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "list_reader_engagement";
    private static final Logger LOG = LogManager.getLogger( ListReaderEngagementTool.class );

    static final int DEFAULT_DAYS = 28;
    static final int MAX_DAYS = 90;
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    /** Null when no datasource is configured; the tool then refuses at call time. */
    private final ReaderReport report;
    private final String siteHost;

    public ListReaderEngagementTool( final ReaderReport report, final String siteHost ) {
        this.report = report;
        this.siteHost = siteHost;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "days", Map.of( "type", "integer",
                "description", "Window in days (default 28, max 90)" ) );
        properties.put( "limit", Map.of( "type", "integer",
                "description", "Max pages, most-viewed first (default 25, max 200)" ) );
        properties.put( "cluster", Map.of( "type", "string",
                "description", "Only pages whose cluster is exactly this path" ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        final Map< String, Object > examplePage = new LinkedHashMap<>();
        examplePage.put( "canonical_id", "01H8X0000000000000000000AB" );
        examplePage.put( "slug", "HybridRetrieval" );
        examplePage.put( "views", 120 );
        examplePage.put( "visitors", 90 );
        examplePage.put( "typical_active_s", 38.0 );
        examplePage.put( "scroll75_rate", 0.42 );
        examplePage.put( "actions_per_view", 0.3 );
        examplePage.put( "landings", Map.of( "organic-search", 60, "direct", 20 ) );
        examplePage.put( "continuation_rate", 0.35 );
        examplePage.put( "returning_this_month_rate", 0.1 );
        examplePage.put( "lcp_p75_ms", 1800.0 );
        examplePage.put( "inp_p75_ms", null );
        examplePage.put( "cls_p75", 0.02 );
        final Map< String, Object > example = new LinkedHashMap<>();
        example.put( "pages", List.of( examplePage ) );
        example.put( "count", 1 );
        example.put( "days", 28 );
        example.put( "refreshedAt", "2026-11-24T05:00:00Z" );
        outputSchema.put( "examples", List.of( example ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List per-page reader engagement from the analytics rollups, most-viewed first: "
                        + "views, visitors, typical_active_s (exit-weighted median of daily medians), scroll "
                        + "depth, landings by channel, continuation and returning-this-month rates, web vitals. "
                        + "A null value means no data, not zero. An empty list with refreshedAt null means "
                        + "analytics is off or has not been read back yet, not that nobody read the wiki." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        if ( report == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Reader analytics is not available",
                    "It needs a configured datasource and the analytics read-back enabled." );
        }
        final int days = clamp( intArg( arguments, "days", DEFAULT_DAYS ), 1, MAX_DAYS );
        final int limit = clamp( intArg( arguments, "limit", DEFAULT_LIMIT ), 1, MAX_LIMIT );
        final Object c = arguments.get( "cluster" );
        final String cluster = c == null || c.toString().isBlank() ? null : c.toString().strip();

        final List< PageReaderStats > pages;
        final Instant refreshed;
        try {
            pages = report.pages( siteHost, days, limit, cluster );
            refreshed = report.refreshedAt( siteHost );
        } catch ( final RuntimeException e ) {
            LOG.warn( "list_reader_engagement failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to read reader engagement",
                    "Check that the database is reachable and migration V097 has been applied." );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pages", ReaderReportView.pages( pages ) );
        result.put( "count", pages.size() );
        result.put( "days", days );
        result.put( "refreshedAt", refreshed == null ? null : refreshed.toString() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private static int clamp( final int v, final int lo, final int hi ) {
        return Math.min( hi, Math.max( lo, v ) );
    }

    private static int intArg( final Map< String, Object > args, final String key, final int dflt ) {
        final Object v = args.get( key );
        if ( v instanceof Number n ) {
            return n.intValue();
        }
        if ( v != null ) {
            try {
                return Integer.parseInt( v.toString().strip() );
            } catch ( final NumberFormatException e ) {
                LOG.debug( "Argument {}='{}' is not an integer; using default {}: {}", key, v, dflt, e.getMessage() );
            }
        }
        return dflt;
    }
}

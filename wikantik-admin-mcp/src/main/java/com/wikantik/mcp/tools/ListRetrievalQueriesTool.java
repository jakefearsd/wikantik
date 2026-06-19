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

import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import com.wikantik.api.querylog.SourceSurface;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool over the retrieval query log. Returns real queries (deduped, ranked by
 * frequency) so content work can target what people actually search for; {@code max_avg_results}
 * surfaces under-served queries (low/zero result counts). The agent judges true "misses" by
 * running candidates through the assemble_bundle MCP tool — this tool intentionally bakes in no relevance judgment.
 */
public class ListRetrievalQueriesTool implements McpTool {

    public static final String TOOL_NAME = "list_retrieval_queries";
    private static final Logger LOG = LogManager.getLogger( ListRetrievalQueriesTool.class );

    private final QueryLogReader reader;

    public ListRetrievalQueriesTool( final QueryLogReader reader ) {
        this.reader = reader;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "since_days", Map.of( "type", "integer",
                "description", "Look back this many days (default 30)" ) );
        properties.put( "actor", Map.of( "type", "string",
                "description", "Filter by actor: human | agent | unknown (default: all)" ) );
        properties.put( "surface", Map.of( "type", "string",
                "description", "Filter by surface: api_bundle | api_search | mcp_assemble_bundle | tools_search_wiki (default: all)" ) );
        properties.put( "max_avg_results", Map.of( "type", "integer",
                "description", "Only return queries whose average result count is <= this (find under-served queries)" ) );
        properties.put( "min_occurrences", Map.of( "type", "integer",
                "description", "Only return queries seen at least this many times (default 1)" ) );
        properties.put( "limit", Map.of( "type", "integer",
                "description", "Max distinct queries to return (default 50)" ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of( "queries", List.of(
                Map.of( "query", "how do I deploy locally", "occurrences", 3,
                        "avgResultCount", 0.67, "zeroResultCount", 2, "lastSeen", "2026-06-19T10:00:00Z" ) ) ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List real retrieval queries from the query log, deduped and ranked by frequency. "
                        + "Use max_avg_results to find under-served queries the corpus answers poorly, then "
                        + "run candidates through the assemble_bundle MCP tool to confirm the right section surfaces." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final int sinceDays = intArg( arguments, "since_days", 30 );
        final int minOccurrences = intArg( arguments, "min_occurrences", 1 );
        final int limit = intArg( arguments, "limit", 50 );
        final Integer maxAvg = arguments.get( "max_avg_results" ) == null
                ? null : intArg( arguments, "max_avg_results", 0 );

        ActorType actor = null;
        final Object actorArg = arguments.get( "actor" );
        if ( actorArg != null && !actorArg.toString().isBlank() ) {
            actor = ActorType.fromWire( actorArg.toString().strip() );
            if ( actor == ActorType.UNKNOWN && !"unknown".equalsIgnoreCase( actorArg.toString().strip() ) ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Unknown actor: " + actorArg,
                        "Use one of: human, agent, unknown." );
            }
        }

        SourceSurface surface = null;
        final Object surfaceArg = arguments.get( "surface" );
        if ( surfaceArg != null && !surfaceArg.toString().isBlank() ) {
            try {
                surface = SourceSurface.fromWire( surfaceArg.toString().strip() );
            } catch ( final IllegalArgumentException e ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Unknown surface: " + surfaceArg,
                        "Use one of: api_bundle, api_search, mcp_assemble_bundle, tools_search_wiki." );
            }
        }

        final QueryLogQuery query = new QueryLogQuery(
                Instant.now().minus( sinceDays, ChronoUnit.DAYS ),
                actor, surface, maxAvg, minOccurrences, limit );

        final List< AggregatedQuery > rows;
        try {
            rows = reader.topQueries( query );
        } catch ( final RuntimeException e ) {
            LOG.warn( "list_retrieval_queries read failed: {}", e.getMessage() );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to read the retrieval query log",
                    "Check that the database is reachable and V041 has been applied." );
        }

        final List< Map< String, Object > > rendered = new ArrayList<>();
        for ( final AggregatedQuery r : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "query", r.queryText() );
            m.put( "occurrences", r.occurrences() );
            m.put( "avgResultCount", r.avgResultCount() );
            m.put( "zeroResultCount", r.zeroResultCount() );
            m.put( "lastSeen", r.lastSeen() == null ? null : r.lastSeen().toString() );
            rendered.add( m );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "queries", rendered );
        result.put( "count", rendered.size() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
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
                return dflt;
            }
        }
        return dflt;
    }
}

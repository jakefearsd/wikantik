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

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MCP tool — filtered page listing by type, cluster, tag(s), and freshness. */
public class ListPagesByFilterTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesByFilterTool.class );
    public static final String TOOL_NAME = "list_pages_by_filter";

    private final StructuralIndexService service;
    public ListPagesByFilterTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "type",           Map.of( "type", "string",
                "description", "One of hub, article, reference, runbook, design.",
                "examples", List.of( "runbook" ) ) );
        props.put( "cluster",        Map.of( "type", "string",
                "description", "Cluster name; limits results to pages with that cluster frontmatter.",
                "examples", List.of( "retrieval" ) ) );
        props.put( "tag",            Map.of( "type", "array", "items", Map.of( "type", "string" ),
                "description", "All listed tags must appear on the page (AND semantics).",
                "examples", List.of( List.of( "agents", "ops" ) ) ) );
        props.put( "updated_since",  Map.of( "type", "string",
                "description", "ISO-8601 timestamp; include pages modified on or after this instant.",
                "examples", List.of( "2026-01-01T00:00:00Z" ) ) );
        props.put( "limit",          Map.of( "type", "integer",
                "description", "Maximum number of pages (default 100, max 1000).",
                "examples", List.of( 50 ) ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "pages", List.of( Map.of(
                        "id", "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
                        "slug", "HandlingEmbeddingServiceOutages",
                        "title", "Handling Embedding Service Outages",
                        "type", "runbook",
                        "cluster", "retrieval",
                        "tags", List.of( "agents", "ops" ),
                        "updated", "2026-04-22T10:11:00Z"
                ) ),
                "count", 1
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List wiki pages matching a structural filter (type, cluster, tags, freshness). " +
                        "Returns {pages: [PageDescriptor], count}. Prefer this over search_knowledge for " +
                        "structural queries — it reads the canonical page index directly and does not depend " +
                        "on the retrieval pipeline." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final StructuralFilter filter = new StructuralFilter(
                    Optional.ofNullable( (String) arguments.get( "type" ) ).map( PageType::fromFrontmatter ),
                    Optional.ofNullable( (String) arguments.get( "cluster" ) ),
                    coerceStringList( arguments.get( "tag" ) ),
                    Optional.ofNullable( (String) arguments.get( "updated_since" ) ).map( Instant::parse ),
                    arguments.get( "limit" ) instanceof Number n ? n.intValue() : 100,
                    Optional.ofNullable( (String) arguments.get( "cursor" ) ) );
            final List< PageDescriptor > pages = service.listPagesByFilter( filter );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                    Map.of( "pages", pages, "count", pages.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_pages_by_filter failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    private static List< String > coerceStringList( final Object o ) {
        if ( o == null ) return List.of();
        if ( o instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object x : list ) if ( x != null ) out.add( x.toString() );
            return out;
        }
        return List.of( o.toString() );
    }
}

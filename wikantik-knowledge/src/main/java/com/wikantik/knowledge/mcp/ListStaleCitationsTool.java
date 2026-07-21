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

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool — returns citations whose status is not {@link CitationStatus#CURRENT}
 * (i.e. stale or target_missing).
 *
 * <p>When {@code page} is given, queries by source (outbound), by target (inbound),
 * or both directions depending on the {@code direction} parameter. When {@code page}
 * is omitted, returns all stale + target_missing citations across the corpus, capped
 * at {@code limit}.</p>
 */
public class ListStaleCitationsTool extends AbstractKnowledgeMcpTool {

    public static final String TOOL_NAME = "list_stale_citations";

    private final CitationRepository repo;

    public ListStaleCitationsTool( final CitationRepository repo ) {
        this.repo = repo;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "page", Map.of(
                "type", "string",
                "description", "Optional canonical_id of the page to scope the query. "
                        + "When omitted, returns stale citations corpus-wide."
        ) );
        props.put( "direction", Map.of(
                "type", "string",
                "enum", List.of( "outbound", "inbound", "both" ),
                "description", "Direction to query when 'page' is given: "
                        + "'outbound' (citations made by the page), "
                        + "'inbound' (citations pointing at the page), "
                        + "or 'both' (default)."
        ) );
        props.put( "limit", Map.of(
                "type", "integer",
                "description", "Max citations to return (default 50). Applies to corpus-wide queries.",
                "examples", List.of( 50 )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "citations", List.of(
                        Map.of(
                                "sourceCanonicalId", "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
                                "targetCanonicalId", "01H8G3Z2E7FD8R1Q4V9X2T0NMP",
                                "targetHeadingPath", "## Background",
                                "spanText", "see [Foo]",
                                "claimText", "Foo is a prerequisite",
                                "status", "stale",
                                "pinnedTargetVersion", 3
                        )
                ),
                "count", 1
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Return citations whose status is 'stale' or 'target_missing'. "
                        + "Scope to a single page with 'page' + 'direction', or omit 'page' for a "
                        + "corpus-wide listing capped at 'limit'. "
                        + "Useful for finding drift in the wiki's citation graph." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( READ_ONLY_ANNOTATIONS )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        final int limit = arguments.get( "limit" ) instanceof Number n ? n.intValue() : 50;
        final String page = arguments.get( "page" ) instanceof String s ? s : null;
        final String direction = arguments.get( "direction" ) instanceof String d ? d : "both";

        final List< Map< String, Object > > refs;
        if ( page != null ) {
            refs = collectForPage( page, direction );
        } else {
            refs = collectCorpusWide( limit );
        }

        final List< Map< String, Object > > result = refs.size() > limit ? refs.subList( 0, limit ) : refs;
        return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                Map.of( "citations", result, "count", result.size() ) );
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private List< Map< String, Object > > collectForPage( final String page, final String direction ) {
        final List< CitationRow > rows = new ArrayList<>();
        if ( "outbound".equals( direction ) || "both".equals( direction ) ) {
            for ( final CitationRow r : repo.findBySource( page ) ) {
                if ( r.status() != CitationStatus.CURRENT ) rows.add( r );
            }
        }
        if ( "inbound".equals( direction ) || "both".equals( direction ) ) {
            for ( final CitationRow r : repo.findByTarget( page ) ) {
                if ( r.status() != CitationStatus.CURRENT ) rows.add( r );
            }
        }
        return toMaps( rows );
    }

    private List< Map< String, Object > > collectCorpusWide( final int limit ) {
        final List< CitationRow > rows = new ArrayList<>();
        rows.addAll( repo.findByStatus( CitationStatus.STALE ) );
        rows.addAll( repo.findByStatus( CitationStatus.TARGET_MISSING ) );
        final List< CitationRow > capped = rows.size() > limit ? rows.subList( 0, limit ) : rows;
        return toMaps( capped );
    }

    /**
     * Converts rows to wire-format maps. Status is serialized as its wire string
     * (e.g. {@code "stale"}) rather than the enum name, so the JSON is consistent
     * with the rest of the citation API.
     */
    private static List< Map< String, Object > > toMaps( final List< CitationRow > rows ) {
        final List< Map< String, Object > > out = new ArrayList<>( rows.size() );
        for ( final CitationRow r : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "sourceCanonicalId",   r.sourceCanonicalId() );
            m.put( "targetCanonicalId",   r.targetCanonicalId() );
            m.put( "targetHeadingPath",   r.targetHeadingPath() );
            m.put( "spanText",            r.spanText() );
            m.put( "claimText",           r.claimText() );
            m.put( "status",              r.status().wire() );
            m.put( "pinnedTargetVersion", r.pinnedTargetVersion() );
            out.add( m );
        }
        return out;
    }
}

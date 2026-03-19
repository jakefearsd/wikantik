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
import org.apache.wiki.api.search.SearchResult;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.search.SearchManager;

import java.util.*;

/**
 * MCP tool that performs full-text search across wiki pages.
 */
public class SearchPagesTool {

    private static final Logger LOG = LogManager.getLogger( SearchPagesTool.class );
    public static final String TOOL_NAME = "search_pages";

    private final WikiEngine engine;
    private final Gson gson = new Gson();

    public SearchPagesTool( final WikiEngine engine ) {
        this.engine = engine;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "query", Map.of( "type", "string", "description", "Full-text search query" ) );
        properties.put( "maxResults", Map.of( "type", "integer", "description", "Maximum number of results (default 20)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Full-text search across wiki pages. " +
                        "Returns {results: [{pageName, score, contexts}]} ordered by relevance score. " +
                        "For metadata-based queries (e.g. by type or tags), use query_metadata instead." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "query" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String query = McpToolUtils.getString( arguments, "query" );
        final int maxResults = McpToolUtils.getInt( arguments, "maxResults", 20 );

        try {
            final Page dummyPage = Wiki.contents().page( engine, "Main" );
            final Context context = Wiki.context().create( engine, dummyPage );
            final Collection< SearchResult > searchResults =
                    engine.getManager( SearchManager.class ).findPages( query, context );

            final List< Map< String, Object > > results = new ArrayList<>();
            if ( searchResults == null ) {
                return McpToolUtils.jsonResult( gson, Map.of( "results", results ) );
            }
            int count = 0;
            for ( final SearchResult sr : searchResults ) {
                if ( count >= maxResults ) {
                    break;
                }
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "pageName", sr.getPage().getName() );
                entry.put( "score", sr.getScore() );
                entry.put( "contexts", sr.getContexts() );
                results.add( entry );
                count++;
            }

            return McpToolUtils.jsonResult( gson, Map.of( "results", results ) );
        } catch ( final Exception e ) {
            LOG.error( "Search failed for query '{}': {}", query, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }
}

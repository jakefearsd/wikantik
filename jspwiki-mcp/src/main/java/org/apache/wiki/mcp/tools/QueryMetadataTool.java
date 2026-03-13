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
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.frontmatter.FrontmatterParser;
import org.apache.wiki.frontmatter.ParsedPage;
import org.apache.wiki.pages.PageManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that queries pages by YAML frontmatter metadata fields.
 */
public class QueryMetadataTool {

    private static final Logger LOG = LogManager.getLogger( QueryMetadataTool.class );
    public static final String TOOL_NAME = "query_metadata";

    private final PageManager pageManager;
    private final Gson gson = new Gson();

    public QueryMetadataTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "field", Map.of( "type", "string", "description", "Frontmatter field name to query" ) );
        properties.put( "value", Map.of( "type", "string", "description", "Value to match (optional -- if omitted, returns pages that have the field)" ) );
        properties.put( "type", Map.of( "type", "string", "description", "Shortcut for field=type, value=<type>" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Query pages by YAML frontmatter metadata fields" )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        String field = ( String ) arguments.getOrDefault( "field", null );
        String value = ( String ) arguments.getOrDefault( "value", null );
        final String type = ( String ) arguments.getOrDefault( "type", null );

        // "type" is a shortcut for field=type, value=<type>
        if ( type != null && field == null ) {
            field = "type";
            value = type;
        }

        if ( field == null ) {
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent(
                            gson.toJson( Map.of( "error", "Either 'field' or 'type' parameter is required" ) ) ) ) )
                    .isError( true )
                    .build();
        }

        try {
            final Collection< Page > allPages = pageManager.getAllPages();
            final List< Map< String, Object > > results = new ArrayList<>();
            final String matchField = field;
            final String matchValue = value;

            for ( final Page page : allPages ) {
                final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( text );

                if ( parsed.metadata().isEmpty() ) {
                    continue;
                }

                final Object fieldVal = parsed.metadata().get( matchField );
                if ( fieldVal == null ) {
                    continue;
                }

                if ( matchValue == null || matchesValue( fieldVal, matchValue ) ) {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "metadata", parsed.metadata() );
                    results.add( entry );
                }
            }

            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "pages", results ) ) ) ) )
                    .build();
        } catch ( final Exception e ) {
            LOG.error( "Metadata query failed: {}", e.getMessage(), e );
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "error", e.getMessage() ) ) ) ) )
                    .isError( true )
                    .build();
        }
    }

    private boolean matchesValue( final Object fieldVal, final String matchValue ) {
        if ( fieldVal instanceof List ) {
            return ( ( List< ? > ) fieldVal ).stream()
                    .anyMatch( item -> matchValue.equals( String.valueOf( item ) ) );
        }
        return matchValue.equals( String.valueOf( fieldVal ) );
    }
}

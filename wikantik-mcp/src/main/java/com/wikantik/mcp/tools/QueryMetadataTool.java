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
import com.wikantik.api.providers.PageProvider;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool that queries pages by YAML frontmatter metadata fields.
 */
public class QueryMetadataTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( QueryMetadataTool.class );
    public static final String TOOL_NAME = "query_metadata";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final PageManager pageManager;

    public QueryMetadataTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "field", Map.of( "type", "string", "description", "Frontmatter field name to query (for single-field queries)" ) );
        properties.put( "value", Map.of( "type", "string", "description", "Value to match (optional -- if omitted, returns pages that have the field)" ) );
        properties.put( "type", Map.of( "type", "string", "description", "Shortcut for field=type, value=<type>" ) );
        properties.put( "filters", Map.of( "type", "array", "description",
                "Array of {field, value} objects for AND queries across multiple fields. " +
                "Each filter must match for a page to be included. Omit value in a filter to match field existence.",
                "items", Map.of( "type", "object" ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Query pages by YAML frontmatter metadata fields. " +
                        "Returns {pages: [{name, metadata}]}. Use the type parameter as a shortcut for field=type. " +
                        "value matches inside lists too. Omit value to find pages that have the field regardless of its value. " +
                        "Use filters for AND queries across multiple fields." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        String field = McpToolUtils.getString( arguments, "field" );
        String value = McpToolUtils.getString( arguments, "value" );
        final String type = McpToolUtils.getString( arguments, "type" );
        final List< Map< String, Object > > filters = ( List< Map< String, Object > > ) arguments.get( "filters" );

        // "type" is a shortcut for field=type, value=<type>
        if ( type != null && field == null ) {
            field = "type";
            value = type;
        }

        // Build the list of filter criteria
        final List< FilterCriterion > criteria = new ArrayList<>();
        if ( field != null ) {
            criteria.add( new FilterCriterion( field, value ) );
        }
        if ( filters != null ) {
            for ( final Map< String, Object > f : filters ) {
                final String fField = ( String ) f.get( "field" );
                final String fValue = f.get( "value" ) != null ? String.valueOf( f.get( "value" ) ) : null;
                if ( fField != null ) {
                    criteria.add( new FilterCriterion( fField, fValue ) );
                }
            }
        }

        if ( criteria.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Either 'field', 'type', or 'filters' parameter is required",
                    "Use type='report' for a simple query, or filters=[{field:'type',value:'report'},{field:'tags',value:'ai'}] for compound queries." );
        }

        try {
            final Collection< Page > allPages = pageManager.getAllPages();
            final List< Map< String, Object > > results = new ArrayList<>();

            for ( final Page page : allPages ) {
                final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( text );

                if ( parsed.metadata().isEmpty() ) {
                    continue;
                }

                if ( matchesAllCriteria( parsed.metadata(), criteria ) ) {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "metadata", parsed.metadata() );
                    results.add( entry );
                }
            }

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "pages", results ) );
        } catch ( final Exception e ) {
            LOG.error( "Metadata query failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }

    private boolean matchesAllCriteria( final Map< String, Object > metadata, final List< FilterCriterion > criteria ) {
        for ( final FilterCriterion criterion : criteria ) {
            final Object fieldVal = metadata.get( criterion.field );
            if ( fieldVal == null ) {
                return false;
            }
            if ( criterion.value != null && !matchesValue( fieldVal, criterion.value ) ) {
                return false;
            }
        }
        return true;
    }

    private record FilterCriterion( String field, String value ) {
    }

    private boolean matchesValue( final Object fieldVal, final String matchValue ) {
        if ( fieldVal instanceof List ) {
            return ( ( List< ? > ) fieldVal ).stream()
                    .anyMatch( item -> matchValue.equals( String.valueOf( item ) ) );
        }
        return matchValue.equals( String.valueOf( fieldVal ) );
    }
}

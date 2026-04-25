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
import com.wikantik.api.managers.ReferenceManager;

import java.util.*;

/**
 * MCP tool that finds all broken links across the wiki — pages that are
 * referenced but do not exist. For each broken link, reports which
 * existing pages reference it.
 */
public class GetBrokenLinksTool implements McpTool {

    public static final String TOOL_NAME = "get_broken_links";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    private final ReferenceManager referenceManager;

    public GetBrokenLinksTool( final ReferenceManager referenceManager ) {
        this.referenceManager = referenceManager;
    }

    @Override
    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Find all broken links across the wiki — pages that are referenced " +
                        "but do not exist. Returns {brokenLinks: [{pageName, referencedBy: [...]}], count}. " +
                        "Use this for wiki maintenance to identify pages that need to be created or " +
                        "links that need to be fixed." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Collection< String > uncreated = referenceManager.findUncreated();
        final List< String > sorted = new ArrayList<>( uncreated );
        Collections.sort( sorted );

        // D16: filter out targets that aren't valid wiki page names. Source-file paths
        // (e.g. "../wikantik-main/src/main/java/.../JDBCGroupDatabase.java") leaked
        // into the link graph and surfaced as broken-link false positives. A real wiki
        // page name never contains a '/' or '\\' and never starts with '..'.
        sorted.removeIf( GetBrokenLinksTool::isNonWikiTarget );

        final List< Map< String, Object > > brokenLinks = new ArrayList<>();
        for ( final String pageName : sorted ) {
            final Set< String > referrers = referenceManager.findReferrers( pageName );
            final List< String > sortedReferrers = new ArrayList<>( referrers );
            Collections.sort( sortedReferrers );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );
            entry.put( "referencedBy", sortedReferrers );
            brokenLinks.add( entry );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "brokenLinks", brokenLinks );
        result.put( "count", brokenLinks.size() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    /**
     * D16: returns true when {@code target} cannot be a wiki page name. A valid wiki
     * page name is non-blank and contains no path separators or relative-path
     * indicators. Anything else is a stray source-file or external-URL reference
     * that bled into the reference graph.
     */
    static boolean isNonWikiTarget( final String target ) {
        if ( target == null || target.isBlank() ) {
            return true;
        }
        if ( target.startsWith( ".." ) || target.startsWith( "./" ) ) {
            return true;
        }
        if ( target.indexOf( '/' ) >= 0 || target.indexOf( '\\' ) >= 0 ) {
            return true;
        }
        // schemes (http://, https://, file://) — already excluded by the slash check,
        // but explicitly exclude file extensions that would never be a wiki page.
        if ( target.endsWith( ".java" ) || target.endsWith( ".sql" )
                || target.endsWith( ".xml" ) || target.endsWith( ".html" ) ) {
            return true;
        }
        return false;
    }
}

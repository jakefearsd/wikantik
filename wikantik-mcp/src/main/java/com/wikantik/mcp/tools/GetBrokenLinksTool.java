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
import com.wikantik.references.ReferenceManager;

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
}

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

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.references.ReferenceManager;

import java.util.*;

/**
 * MCP tool that finds all pages a given page links to (outbound links).
 * Complement of {@link GetBacklinksTool} which finds inbound links.
 */
public class GetOutboundLinksTool {

    public static final String TOOL_NAME = "get_outbound_links";

    private final ReferenceManager referenceManager;
    private final Gson gson = new Gson();

    public GetOutboundLinksTool( final ReferenceManager referenceManager ) {
        this.referenceManager = referenceManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the page to find outbound links for" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Find all pages that a given page links to (outbound links). " +
                        "Returns {pageName, links: [names]} sorted alphabetically. " +
                        "Use get_backlinks for the reverse direction (who links to this page)." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );

        final Collection< String > refersTo = referenceManager.findRefersTo( pageName );
        final List< String > links = new ArrayList<>( refersTo );
        Collections.sort( links );

        return McpToolUtils.jsonResult( gson, Map.of( "pageName", pageName, "links", links ) );
    }
}

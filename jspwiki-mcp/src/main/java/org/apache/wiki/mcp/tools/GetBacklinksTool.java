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
import org.apache.wiki.references.ReferenceManager;

import java.util.*;

/**
 * MCP tool that finds pages linking to a given page (backlinks).
 */
public class GetBacklinksTool {

    public static final String TOOL_NAME = "get_backlinks";

    private final ReferenceManager referenceManager;
    private final Gson gson = new Gson();

    public GetBacklinksTool( final ReferenceManager referenceManager ) {
        this.referenceManager = referenceManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the page to find backlinks for" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Find pages that link to a given page" )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = ( String ) arguments.get( "pageName" );

        final Set< String > referrers = referenceManager.findReferrers( pageName );
        final List< String > backlinks = referrers != null
                ? new ArrayList<>( referrers )
                : new ArrayList<>();
        Collections.sort( backlinks );

        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent(
                        gson.toJson( Map.of( "pageName", pageName, "backlinks", backlinks ) ) ) ) )
                .build();
    }
}

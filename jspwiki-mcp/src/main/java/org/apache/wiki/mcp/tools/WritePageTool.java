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
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.mcp.frontmatter.FrontmatterWriter;
import org.apache.wiki.pages.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that creates or updates a wiki page, with optional YAML frontmatter support.
 */
public class WritePageTool {

    private static final Logger LOG = LogManager.getLogger( WritePageTool.class );
    public static final String TOOL_NAME = "write_page";

    private final WikiEngine engine;
    private final Gson gson = new Gson();

    public WritePageTool( final WikiEngine engine ) {
        this.engine = engine;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to create or update" ) );
        properties.put( "content", Map.of( "type", "string", "description", "The page body content (without frontmatter)" ) );
        properties.put( "metadata", Map.of( "type", "object", "description", "Optional YAML frontmatter fields to prepend" ) );
        properties.put( "changeNote", Map.of( "type", "string", "description", "Optional change note for the edit" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Create or update a wiki page with optional YAML frontmatter" )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName", "content" ), null, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = ( String ) arguments.get( "pageName" );
        final String content = ( String ) arguments.get( "content" );
        final Map< String, Object > metadata = ( Map< String, Object > ) arguments.get( "metadata" );
        final String changeNote = ( String ) arguments.get( "changeNote" );

        final String fullText = FrontmatterWriter.write( metadata, content );

        try {
            final Page page = Wiki.contents().page( engine, pageName );
            page.setAuthor( "MCP" );
            if ( changeNote != null ) {
                page.setAttribute( Page.CHANGENOTE, changeNote );
            }
            final Context context = Wiki.context().create( engine, page );
            engine.getManager( PageManager.class ).saveText( context, fullText );

            final Page saved = engine.getManager( PageManager.class ).getPage( pageName );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "pageName", pageName );
            result.put( "version", saved != null ? saved.getVersion() : 1 );

            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( result ) ) ) )
                    .build();
        } catch ( final Exception e ) {
            LOG.error( "Failed to write page {}: {}", pageName, e.getMessage(), e );
            final Map< String, Object > error = Map.of( "success", false, "error", e.getMessage() );
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( gson.toJson( error ) ) ) )
                    .isError( true )
                    .build();
        }
    }
}

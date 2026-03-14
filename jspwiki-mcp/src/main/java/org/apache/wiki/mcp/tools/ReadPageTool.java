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
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.frontmatter.FrontmatterParser;
import org.apache.wiki.frontmatter.ParsedPage;
import org.apache.wiki.pages.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that reads a wiki page's content and metadata.
 */
public class ReadPageTool {

    public static final String TOOL_NAME = "read_page";

    private final PageManager pageManager;
    private final SystemPageRegistry systemPageRegistry;
    private final Gson gson = new Gson();

    public ReadPageTool( final PageManager pageManager, final SystemPageRegistry systemPageRegistry ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to read" ) );
        properties.put( "version", Map.of( "type", "integer", "description", "Page version to read (omit for latest)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Read a wiki page's content and metadata. Returns {exists, pageName, content, metadata, version, author, lastModified}. " +
                        "content is the Markdown body without frontmatter; metadata is the parsed YAML frontmatter as an object. " +
                        "Check the exists field first — it is false when the page does not exist." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .outputSchema( Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "exists", Map.of( "type", "boolean" ),
                                "pageName", Map.of( "type", "string" ),
                                "content", Map.of( "type", "string" ),
                                "metadata", Map.of( "type", "object" ),
                                "version", Map.of( "type", "integer" ),
                                "author", Map.of( "type", "string" ),
                                "lastModified", Map.of( "type", "string" ),
                                "systemPage", Map.of( "type", "boolean" )
                        ),
                        "required", List.of( "exists", "pageName" )
                ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final int version = McpToolUtils.getInt( arguments, "version", PageProvider.LATEST_VERSION );

        final Page page = pageManager.getPage( pageName, version );
        final Map< String, Object > result = new LinkedHashMap<>();

        if ( page == null ) {
            result.put( "exists", false );
            result.put( "pageName", pageName );
            return McpToolUtils.jsonResult( gson, result );
        }

        final String rawText = pageManager.getPureText( pageName, version );
        final ParsedPage parsed = FrontmatterParser.parse( rawText );

        result.put( "exists", true );
        result.put( "pageName", page.getName() );
        result.put( "content", parsed.body() );
        result.put( "metadata", parsed.metadata() );
        result.put( "version", McpToolUtils.normalizeVersion( page.getVersion() ) );
        result.put( "author", page.getAuthor() );
        result.put( "lastModified", page.getLastModified() != null ? page.getLastModified().toInstant().toString() : null );
        if ( systemPageRegistry != null ) {
            result.put( "systemPage", systemPageRegistry.isSystemPage( page.getName() ) );
        }

        return McpToolUtils.jsonResult( gson, result );
    }
}

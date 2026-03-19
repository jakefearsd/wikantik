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
import org.apache.wiki.diff.DifferenceManager;
import org.apache.wiki.pages.PageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that returns a diff between two versions of a wiki page.
 */
public class DiffPageTool {

    private static final Logger LOG = LogManager.getLogger( DiffPageTool.class );
    public static final String TOOL_NAME = "diff_page";

    private final WikiEngine engine;
    private final Gson gson = new Gson();

    public DiffPageTool( final WikiEngine engine ) {
        this.engine = engine;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page" ) );
        properties.put( "version1", Map.of( "type", "integer", "description", "First (older) version number" ) );
        properties.put( "version2", Map.of( "type", "integer", "description", "Second (newer) version number" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get a diff between two versions of a wiki page. " +
                        "Returns {pageName, version1, version2, diff} where diff is the textual difference. " +
                        "Use get_page_history first to find available version numbers." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "pageName", "version1", "version2" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );
        final int version1 = McpToolUtils.getInt( arguments, "version1", 1 );
        final int version2 = McpToolUtils.getInt( arguments, "version2", 1 );

        try {
            final PageManager pageManager = engine.getManager( PageManager.class );
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return McpToolUtils.errorResult( gson,
                        "Page not found: " + pageName,
                        "Use list_pages to find existing pages." );
            }

            final String text1 = pageManager.getPureText( pageName, version1 );
            final String text2 = pageManager.getPureText( pageName, version2 );

            final Page contextPage = Wiki.contents().page( engine, pageName );
            final Context context = Wiki.context().create( engine, contextPage );
            final DifferenceManager diffMgr = engine.getManager( DifferenceManager.class );
            final String htmlDiff = diffMgr.makeDiff( context, text1, text2 );

            // Strip HTML tags to return a plain-text diff suitable for AI consumption
            final String plainDiff = stripHtml( htmlDiff );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "pageName", pageName );
            result.put( "version1", version1 );
            result.put( "version2", version2 );
            result.put( "diff", plainDiff );
            return McpToolUtils.jsonResult( gson, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to diff page {}: {}", pageName, e.getMessage(), e );
            return McpToolUtils.errorResult( gson, e.getMessage() );
        }
    }

    static String stripHtml( final String html ) {
        if ( html == null || html.isEmpty() ) {
            return "";
        }
        // Replace <br/> and block elements with newlines, then strip remaining tags
        return html
                .replaceAll( "<br\\s*/?>", "\n" )
                .replaceAll( "</(p|div|tr|li)>", "\n" )
                .replaceAll( "<[^>]+>", "" )
                .replaceAll( "&lt;", "<" )
                .replaceAll( "&gt;", ">" )
                .replaceAll( "&amp;", "&" )
                .replaceAll( "&quot;", "\"" )
                .replaceAll( "&#39;", "'" )
                .replaceAll( "\n{3,}", "\n\n" )
                .strip();
    }
}

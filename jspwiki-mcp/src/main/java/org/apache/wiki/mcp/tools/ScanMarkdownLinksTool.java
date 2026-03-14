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
import org.apache.wiki.pages.PageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tool that scans a wiki page for Markdown-style links and classifies them
 * as external, anchor, or local (wiki page) links.
 */
public class ScanMarkdownLinksTool {

    public static final String TOOL_NAME = "scan_markdown_links";
    private static final Pattern LINK_PATTERN = Pattern.compile( "\\[([^\\]]*)\\]\\(([^)]+)\\)" );

    private final PageManager pageManager;
    private final Gson gson = new Gson();

    public ScanMarkdownLinksTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    public McpSchema.Tool toolDefinition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string", "description", "Name of the wiki page to scan for Markdown links" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Scan a wiki page for Markdown-style links [text](target) and classify them. " +
                        "Returns {pageName, links: [{target, text, type}], localLinks: [\"PageName\", ...]}. " +
                        "type is 'external' (http/https/ftp), 'anchor' (#fragment), or 'local' (wiki page reference). " +
                        "localLinks lists just the unique local page names, matching the format of get_outbound_links." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "pageName" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String pageName = McpToolUtils.getString( arguments, "pageName" );

        final Page page = pageManager.getPage( pageName );
        if ( page == null ) {
            return McpToolUtils.errorResult( gson,
                    "Page not found: " + pageName,
                    "Use list_pages to find existing pages." );
        }

        final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        final Matcher matcher = LINK_PATTERN.matcher( rawText );

        final List< Map< String, String > > links = new ArrayList<>();
        final Set< String > localLinks = new LinkedHashSet<>();

        while ( matcher.find() ) {
            final String text = matcher.group( 1 );
            final String target = matcher.group( 2 );
            final String type = classifyLink( target );

            final Map< String, String > link = new LinkedHashMap<>();
            link.put( "target", target );
            link.put( "text", text );
            link.put( "type", type );
            links.add( link );

            if ( "local".equals( type ) ) {
                localLinks.add( target );
            }
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pageName", pageName );
        result.put( "links", links );
        result.put( "localLinks", new ArrayList<>( localLinks ) );

        return McpToolUtils.jsonResult( gson, result );
    }

    private static String classifyLink( final String target ) {
        if ( target.startsWith( "http://" ) || target.startsWith( "https://" ) || target.startsWith( "ftp://" ) ) {
            return "external";
        }
        if ( target.startsWith( "#" ) ) {
            return "anchor";
        }
        return "local";
    }
}

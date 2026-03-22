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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * MCP tool that notifies search engines about content changes. Supports
 * Google Sitemap Ping and IndexNow. Agents decide when to notify, typically
 * after a cluster publish.
 */
public class PingSearchEnginesTool implements McpTool {

    public static final String TOOL_NAME = "ping_search_engines";

    private static final Duration TIMEOUT = Duration.ofSeconds( 10 );

    private final String baseUrl;
    private final String indexNowApiKey;
    private final HttpClient httpClient;

    public PingSearchEnginesTool( final String baseUrl, final String indexNowApiKey,
                                   final HttpClient httpClient ) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith( "/" )
                ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl;
        this.indexNowApiKey = indexNowApiKey;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "service", Map.of( "type", "string",
                "enum", List.of( "indexnow", "google_ping", "all" ),
                "description", "Which search engine notification service to use" ) );
        properties.put( "urls", Map.of( "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Specific page URLs to submit (IndexNow). If omitted, submits sitemap URL." ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Notify search engines about content changes. Supports Google Sitemap Ping " +
                        "(submits sitemap URL) and IndexNow (submits specific page URLs). " +
                        "Use after publishing a cluster or making significant content updates." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "service" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String service = McpToolUtils.getString( arguments, "service" );
        if ( service == null || service.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Missing required parameter: service" );
        }

        final List< String > urls = ( List< String > ) arguments.get( "urls" );
        final List< Map< String, Object > > results = new ArrayList<>();

        if ( "google_ping".equals( service ) || "all".equals( service ) ) {
            results.add( pingGoogle() );
        }
        if ( "indexnow".equals( service ) || "all".equals( service ) ) {
            results.add( pingIndexNow( urls ) );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "results", results );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private Map< String, Object > pingGoogle() {
        final Map< String, Object > entry = new LinkedHashMap<>();
        entry.put( "service", "google_ping" );

        try {
            final String sitemapUrl = baseUrl + "/sitemap.xml";
            final String pingUrl = "https://www.google.com/ping?sitemap="
                    + URLEncoder.encode( sitemapUrl, StandardCharsets.UTF_8 );

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( pingUrl ) )
                    .timeout( TIMEOUT )
                    .GET()
                    .build();

            final HttpResponse< String > response = httpClient.send( request,
                    HttpResponse.BodyHandlers.ofString() );

            entry.put( "success", response.statusCode() >= 200 && response.statusCode() < 300 );
            entry.put( "status", response.statusCode() );
            entry.put( "sitemapUrl", sitemapUrl );
        } catch ( final Exception e ) {
            entry.put( "success", false );
            entry.put( "error", "Google ping failed: " + e.getMessage() );
        }

        return entry;
    }

    private Map< String, Object > pingIndexNow( final List< String > urls ) {
        final Map< String, Object > entry = new LinkedHashMap<>();
        entry.put( "service", "indexnow" );

        if ( indexNowApiKey == null || indexNowApiKey.isBlank() ) {
            entry.put( "success", false );
            entry.put( "error", "IndexNow apiKey not configured. Set wikantik.indexnow.apiKey " +
                    "in wikantik-custom.properties to enable IndexNow notifications." );
            return entry;
        }

        try {
            final List< String > submitUrls;
            if ( urls != null && !urls.isEmpty() ) {
                submitUrls = urls;
            } else {
                submitUrls = List.of( baseUrl + "/sitemap.xml" );
            }

            // Build JSON body for IndexNow batch API
            final StringBuilder body = new StringBuilder();
            body.append( "{\"host\":\"" ).append( extractHost( baseUrl ) ).append( "\"," );
            body.append( "\"key\":\"" ).append( indexNowApiKey ).append( "\"," );
            body.append( "\"urlList\":[" );
            for ( int i = 0; i < submitUrls.size(); i++ ) {
                if ( i > 0 ) body.append( ',' );
                body.append( '"' ).append( submitUrls.get( i ) ).append( '"' );
            }
            body.append( "]}" );

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( "https://api.indexnow.org/indexnow" ) )
                    .timeout( TIMEOUT )
                    .header( "Content-Type", "application/json" )
                    .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) )
                    .build();

            final HttpResponse< String > response = httpClient.send( request,
                    HttpResponse.BodyHandlers.ofString() );

            entry.put( "success", response.statusCode() >= 200 && response.statusCode() < 300 );
            entry.put( "status", response.statusCode() );
            entry.put( "urlsSubmitted", submitUrls.size() );
        } catch ( final IOException | InterruptedException e ) {
            entry.put( "success", false );
            entry.put( "error", "IndexNow ping failed: " + e.getMessage() );
        }

        return entry;
    }

    private static String extractHost( final String url ) {
        if ( url == null ) return "localhost";
        try {
            return URI.create( url ).getHost();
        } catch ( final Exception e ) {
            return "localhost";
        }
    }
}

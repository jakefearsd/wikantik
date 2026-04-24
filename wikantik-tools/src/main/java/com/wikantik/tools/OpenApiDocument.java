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
package com.wikantik.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the OpenAPI 3.1 document advertised at {@code GET /tools/openapi.json}.
 *
 * <p>The document is OpenWebUI-compatible: each operation exposes an {@code operationId}
 * (used by the LLM as the tool name) and a rich {@code description} that the model
 * reads as its tool prompt. Descriptions are deliberately verbose — they are the
 * entirety of the tool's instructions to the caller.</p>
 */
final class OpenApiDocument {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private OpenApiDocument() {}

    static String render( final HttpServletRequest request, final ToolsConfig config ) {
        final String serverUrl = resolveServerUrl( request, config );

        final Map< String, Object > doc = new LinkedHashMap<>();
        doc.put( "openapi", "3.1.0" );
        doc.put( "info", info() );
        doc.put( "servers", List.of( Map.of( "url", serverUrl, "description", "Wikantik tool server" ) ) );
        doc.put( "components", components() );
        doc.put( "security", List.of( Map.of( "bearerAuth", List.of() ) ) );
        doc.put( "paths", paths() );
        return GSON.toJson( doc );
    }

    private static String resolveServerUrl( final HttpServletRequest request, final ToolsConfig config ) {
        final String publicBase = config.publicBaseUrl();
        if ( publicBase != null && !publicBase.isBlank() ) {
            final String trimmed = publicBase.endsWith( "/" )
                    ? publicBase.substring( 0, publicBase.length() - 1 )
                    : publicBase;
            return trimmed + "/tools";
        }
        if ( request != null ) {
            final String scheme = request.getScheme();
            final String host = request.getServerName();
            final int port = request.getServerPort();
            final boolean defaultPort = ( "http".equals( scheme ) && port == 80 )
                    || ( "https".equals( scheme ) && port == 443 );
            final String authority = defaultPort ? host : host + ":" + port;
            return scheme + "://" + authority + "/tools";
        }
        return "/tools";
    }

    private static Map< String, Object > info() {
        final Map< String, Object > info = new LinkedHashMap<>();
        info.put( "title", "Wikantik Tool Server" );
        info.put( "version", "0.9.1" );
        info.put( "description", """
                OpenAPI tool server exposing Wikantik's hybrid search and page retrieval as LLM-callable tools.
                Designed for OpenWebUI and other OpenAPI 3.1 tool clients.

                Every endpoint requires a Bearer token. Tokens are generated via the admin API-keys page and
                are bound to a Wikantik principal; the tool server runs requests under that principal so page
                ACLs and JAAS permissions apply exactly as they would for that user's interactive session.""" );
        return info;
    }

    private static Map< String, Object > components() {
        final Map< String, Object > securitySchemes = new LinkedHashMap<>();
        securitySchemes.put( "bearerAuth", Map.of(
                "type", "http",
                "scheme", "bearer",
                "description", "DB-backed API key issued via the admin API-keys page; bound to a Wikantik principal" ) );

        final Map< String, Object > schemas = new LinkedHashMap<>();
        schemas.put( "ContributingChunk", Map.of(
                "type", "object",
                "description", "One chunk of wiki content that contributed to the page's score",
                "properties", orderedProps(
                        Map.entry( "headingPath", Map.of(
                                "type", "array",
                                "items", Map.of( "type", "string" ),
                                "description", "Section breadcrumb (top to leaf)" ) ),
                        prop( "text", "string", "Chunk body text" ),
                        prop( "chunkScore", "number", "Retriever-specific chunk score; treat as ordinal" ) ) ) );

        schemas.put( "RelatedPageHint", Map.of(
                "type", "object",
                "description", "A page connected via knowledge-graph mention co-occurrence",
                "properties", orderedProps(
                        prop( "name", "string", "Wiki page name" ),
                        prop( "reason", "string", "Short human-readable explanation of the link" ) ) ) );

        schemas.put( "SearchResult", Map.of(
                "type", "object",
                "properties", orderedProps(
                        prop( "name", "string", "Wiki page name that matched the query" ),
                        prop( "url", "string", "Absolute URL to the page (use verbatim in citations)" ),
                        prop( "score", "number", "Relevance score; higher is better" ),
                        prop( "summary", "string", "Page summary from frontmatter, when present" ),
                        prop( "tags", "array", "Page tags from frontmatter, when present" ),
                        prop( "cluster", "string", "Knowledge-graph cluster label, when present" ),
                        Map.entry( "contributingChunks", Map.of(
                                "type", "array",
                                "items", Map.of( "$ref", "#/components/schemas/ContributingChunk" ),
                                "description", "Top chunks that drove this page's rank, when available" ) ),
                        Map.entry( "relatedPages", Map.of(
                                "type", "array",
                                "items", Map.of( "$ref", "#/components/schemas/RelatedPageHint" ),
                                "description", "Pages linked via KG-mention co-occurrence" ) ),
                        prop( "snippet", "string", "Leading excerpt from the top contributing chunk (legacy field — use contributingChunks for richer context)" ),
                        prop( "lastModified", "string", "ISO-8601 timestamp of the last edit" ),
                        prop( "author", "string", "Last author login, when recorded" ) ) ) );

        schemas.put( "PageContent", Map.of(
                "type", "object",
                "properties", orderedProps(
                        prop( "name", "string", "Wiki page name as stored" ),
                        prop( "url", "string", "Absolute URL to the page (use verbatim in citations)" ),
                        prop( "summary", "string", "Page summary from frontmatter, when present" ),
                        prop( "tags", "array", "Page tags from frontmatter, when present" ),
                        prop( "text", "string", "Raw Markdown body (frontmatter stripped)" ),
                        prop( "truncated", "boolean", "True when body was trimmed to maxChars" ),
                        prop( "totalChars", "integer", "Original body length, only present when truncated" ),
                        prop( "truncatedAt", "integer", "Character cap applied, only present when truncated" ),
                        prop( "lastModified", "string", "ISO-8601 timestamp of the last edit" ),
                        prop( "author", "string", "Last author login, when recorded" ) ) ) );

        final Map< String, Object > comp = new LinkedHashMap<>();
        comp.put( "securitySchemes", securitySchemes );
        comp.put( "schemas", schemas );
        return comp;
    }

    private static Map< String, Object > paths() {
        final Map< String, Object > paths = new LinkedHashMap<>();
        paths.put( "/search_wiki", Map.of( "post", searchOperation() ) );
        paths.put( "/page/{name}", Map.of( "get", getPageOperation() ) );
        return paths;
    }

    private static Map< String, Object > searchOperation() {
        final Map< String, Object > bodySchema = new LinkedHashMap<>();
        bodySchema.put( "type", "object" );
        bodySchema.put( "required", List.of( "query" ) );
        bodySchema.put( "properties", orderedProps(
                prop( "query", "string", "Full-text search query. Supports Lucene syntax "
                        + "(e.g. quotes for phrases, + for required terms)." ),
                prop( "maxResults", "integer",
                        "Maximum result rows (1-25). Default 10 when omitted." ) ) );

        final Map< String, Object > op = new LinkedHashMap<>();
        op.put( "operationId", "search_wiki" );
        op.put( "summary", "Search the wiki by keyword" );
        op.put( "description", """
                Run a full-text search across all wiki pages. Results combine BM25 relevance with
                dense-vector hybrid retrieval when available, and each result includes a citation URL
                that the model should echo back verbatim when quoting the page.

                When to use: the user asks a factual question that the wiki likely answers, or
                references a topic by name. Prefer this over raw recall.

                When NOT to use: the user already specified an exact page name (use get_page instead).""" );
        op.put( "requestBody", Map.of(
                "required", true,
                "content", Map.of( "application/json", Map.of( "schema", bodySchema ) ) ) );

        final Map< String, Object > responseSchema = new LinkedHashMap<>();
        responseSchema.put( "type", "object" );
        responseSchema.put( "properties", orderedProps(
                prop( "query", "string", "Echo of the original query" ),
                Map.entry( "results", Map.of(
                        "type", "array",
                        "items", Map.of( "$ref", "#/components/schemas/SearchResult" ),
                        "description", "Results ordered best-first" ) ),
                prop( "total", "integer", "Number of results in this response" ) ) );
        op.put( "responses", Map.of(
                "200", Map.of(
                        "description", "Search results",
                        "content", Map.of( "application/json", Map.of( "schema", responseSchema ) ) ),
                "400", Map.of( "description", "Missing or blank query" ),
                "403", Map.of( "description", "Invalid or missing Bearer token" ),
                "429", Map.of( "description", "Rate limit exceeded" ),
                "503", Map.of( "description", "Tool server misconfigured or unavailable" ) ) );
        return op;
    }

    private static Map< String, Object > getPageOperation() {
        final Map< String, Object > nameParam = new LinkedHashMap<>();
        nameParam.put( "name", "name" );
        nameParam.put( "in", "path" );
        nameParam.put( "required", true );
        nameParam.put( "description", "Wiki page name, URL-encoded. Case sensitive." );
        nameParam.put( "schema", Map.of( "type", "string" ) );

        final Map< String, Object > maxCharsParam = new LinkedHashMap<>();
        maxCharsParam.put( "name", "maxChars" );
        maxCharsParam.put( "in", "query" );
        maxCharsParam.put( "required", false );
        maxCharsParam.put( "description", "Override the body truncation limit (hard cap 20000)." );
        maxCharsParam.put( "schema", Map.of( "type", "integer" ) );

        final Map< String, Object > op = new LinkedHashMap<>();
        op.put( "operationId", "get_page" );
        op.put( "summary", "Fetch a single wiki page by name" );
        op.put( "description", """
                Return the Markdown body of the named page with frontmatter stripped. The response
                includes a citation URL to echo verbatim when quoting the page, plus summary and tags
                from frontmatter when available. Bodies over ~6000 characters are truncated to keep
                context budgets predictable; the truncated flag signals this to the caller.

                When to use: the user referenced a specific page by name, or a prior search_wiki call
                produced a result the model wants to read in full.""" );
        op.put( "parameters", List.of( nameParam, maxCharsParam ) );
        op.put( "responses", Map.of(
                "200", Map.of(
                        "description", "Page contents",
                        "content", Map.of( "application/json", Map.of(
                                "schema", Map.of( "$ref", "#/components/schemas/PageContent" ) ) ) ),
                "403", Map.of( "description", "Invalid or missing Bearer token" ),
                "404", Map.of( "description", "Page does not exist" ),
                "429", Map.of( "description", "Rate limit exceeded" ),
                "503", Map.of( "description", "Tool server misconfigured or unavailable" ) ) );
        return op;
    }

    private static Map< String, Object > prop( final String name, final String type, final String description ) {
        final Map< String, Object > holder = new LinkedHashMap<>();
        holder.put( "__name__", name );
        holder.put( "type", type );
        holder.put( "description", description );
        return holder;
    }

    private static Map< String, Object > orderedProps( final Object... propsOrEntries ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        for ( final Object item : propsOrEntries ) {
            if ( item instanceof Map.Entry< ?, ? > entry ) {
                out.put( entry.getKey().toString(), entry.getValue() );
            } else if ( item instanceof Map< ?, ? > map && map.containsKey( "__name__" ) ) {
                @SuppressWarnings( "unchecked" )
                final Map< String, Object > typed = ( Map< String, Object > ) map;
                final String name = typed.remove( "__name__" ).toString();
                out.put( name, typed );
            }
        }
        return out;
    }
}

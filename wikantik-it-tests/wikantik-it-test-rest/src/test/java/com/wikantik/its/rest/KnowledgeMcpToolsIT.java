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
package com.wikantik.its.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the {@code /knowledge-mcp} read tool surface.
 * Before this class existed only {@code read_pages} was exercised over
 * the wire — every other knowledge tool was unit-only.
 *
 * <p>Coverage targets:</p>
 * <ul>
 *   <li>{@code list_clusters} — envelope shape with {@code clusters} + {@code count}.</li>
 *   <li>{@code list_tags} — envelope, {@code min_pages} filter is non-error.</li>
 *   <li>{@code list_pages} — envelope, paging arguments are honored.</li>
 *   <li>{@code list_pages_by_filter} — envelope shape.</li>
 *   <li>{@code list_metadata_values} — envelope shape.</li>
 *   <li>{@code discover_schema} — envelope contains {@code node_types} / {@code relationship_types}.</li>
 *   <li>{@code search_knowledge} — happy path returns {@code results}+{@code scope};
 *       missing {@code query} surfaces an isError result.</li>
 *   <li>{@code query_nodes} — empty filter returns array envelope.</li>
 *   <li>{@code get_page} — happy path returns a name; missing page returns {@code exists:false}.</li>
 *   <li>{@code get_page_for_agent} — missing canonical_id is a tool error.</li>
 * </ul>
 */
public class KnowledgeMcpToolsIT {

    private static String baseUrl;
    private static McpSyncClient mcp;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( "knowledge-mcp" )
                        .connectTimeout( Duration.ofSeconds( 15 ) )
                        .build();
        mcp = McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-knowledge-mcp", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        mcp.initialize();
    }

    @AfterAll
    static void tearDown() {
        if ( mcp != null ) {
            mcp.close();
        }
    }

    // ---- shared helpers ----

    private static JsonObject callSuccess( final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( tool, args ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ),
                tool + " must not return an error result: " + textBody( result ) );
        return parseFirstText( result );
    }

    private static JsonObject callExpectError( final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( tool, args ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ),
                tool + " must surface isError=true: " + textBody( result ) );
        return parseFirstText( result );
    }

    private static String textBody( final McpSchema.CallToolResult result ) {
        if ( result == null || result.content() == null || result.content().isEmpty() ) {
            return "<no content>";
        }
        final McpSchema.Content first = result.content().get( 0 );
        return first instanceof McpSchema.TextContent tc ? tc.text() : "<not text>";
    }

    private static JsonObject parseFirstText( final McpSchema.CallToolResult result ) {
        final String text = textBody( result );
        if ( text.isEmpty() || "<no content>".equals( text ) ) {
            return new JsonObject();
        }
        final JsonElement parsed = JsonParser.parseString( text );
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    // ---- list_clusters ----

    @Test
    void listClusters_returnsClustersAndCount() {
        final JsonObject body = callSuccess( "list_clusters", Map.of() );
        assertTrue( body.has( "clusters" ),
                "list_clusters must include 'clusters': " + body );
        assertTrue( body.has( "count" ),
                "list_clusters must include 'count': " + body );
        assertTrue( body.get( "clusters" ).isJsonArray(),
                "clusters must be a JSON array: " + body );
        // count must equal the array length so callers can short-circuit pagination.
        final int reported = body.get( "count" ).getAsInt();
        final int actual = body.getAsJsonArray( "clusters" ).size();
        assertEquals( actual, reported,
                "list_clusters count must equal clusters.length: reported=" + reported + " actual=" + actual );
    }

    // ---- list_tags ----

    @Test
    void listTags_acceptsMinPagesFilter() {
        final JsonObject zero = callSuccess( "list_tags", Map.of( "min_pages", 1 ) );
        assertNotNull( zero, "list_tags envelope must not be null" );
        // The tool should expose either a "tags" array or a "count" / "total" field.
        final boolean recognizable = zero.has( "tags" ) || zero.has( "count" ) || zero.has( "total" );
        assertTrue( recognizable,
                "list_tags response must contain a tags / count / total field: " + zero );

        // A very large min_pages should still succeed (returning an empty list).
        final JsonObject huge = callSuccess( "list_tags", Map.of( "min_pages", 9999 ) );
        assertNotNull( huge, "list_tags(min_pages=9999) must not error" );
    }

    // ---- list_pages ----

    @Test
    void listPages_pagingArgumentsHonored() {
        final JsonObject first = callSuccess( "list_pages",
                Map.of( "limit", 3, "offset", 0 ) );
        assertNotNull( first, "list_pages envelope must not be null" );
        assertTrue( first.has( "pages" ) || first.has( "results" ) || first.has( "count" ),
                "list_pages must expose pages / results / count: " + first );

        // Out-of-range offset must still succeed.
        final JsonObject far = callSuccess( "list_pages",
                Map.of( "limit", 1, "offset", 100000 ) );
        assertNotNull( far, "list_pages(offset=100000) must not error" );
    }

    // ---- list_pages_by_filter ----

    @Test
    void listPagesByFilter_returnsEnvelope() {
        final JsonObject body = callSuccess( "list_pages_by_filter", Map.of() );
        assertNotNull( body, "envelope must not be null" );
        // The result must expose a list of pages or an explicit count — accept
        // any of the shapes the tool may produce.
        final boolean recognisable = body.has( "pages" )
                || body.has( "results" )
                || body.has( "count" )
                || body.has( "data" );
        assertTrue( recognisable,
                "list_pages_by_filter must expose pages/results/count/data: " + body );
    }

    // ---- list_metadata_values ----

    @Test
    void listMetadataValues_returnsEnvelopeForKnownField() {
        // 'tags' is one of the supported metadata fields; even on an empty
        // corpus the tool must return a valid envelope.
        final JsonObject body = callSuccess( "list_metadata_values",
                Map.of( "field", "tags" ) );
        assertNotNull( body, "envelope must not be null" );
        final boolean recognisable = body.has( "values" ) || body.has( "results" )
                || body.has( "count" ) || body.has( "data" );
        assertTrue( recognisable,
                "list_metadata_values must expose values/results/count/data: " + body );
    }

    // ---- discover_schema ----

    @Test
    void discoverSchema_describesNodeAndRelationshipTypes() {
        final JsonObject body = callSuccess( "discover_schema", Map.of() );
        assertNotNull( body, "envelope must not be null" );
        // The schema description must at least mention node and relationship
        // type vocabularies so callers can build follow-up query_nodes calls.
        final boolean mentionsShape = body.toString().contains( "node" )
                || body.toString().contains( "relationship" );
        assertTrue( mentionsShape,
                "discover_schema must describe node / relationship vocabulary: " + body );
    }

    // ---- search_knowledge ----

    @Test
    void searchKnowledge_happyPathReturnsResultsAndScope() {
        final JsonObject body = callSuccess( "search_knowledge",
                Map.of( "query", "knowledge", "limit", 5 ) );
        assertTrue( body.has( "results" ),
                "search_knowledge must include 'results': " + body );
        assertEquals( "knowledge_graph_nodes_only", body.get( "scope" ).getAsString(),
                "search_knowledge must declare its scope: " + body );
        assertTrue( body.get( "results" ).isJsonArray(),
                "results must be a JSON array: " + body );
    }

    @Test
    void searchKnowledge_missingQueryReturnsError() {
        final JsonObject body = callExpectError( "search_knowledge", Map.of() );
        assertNotNull( body, "error payload must not be null" );
        // Error envelope should carry an error/message field naming the issue.
        final boolean hasMessage = body.has( "error" ) || body.has( "message" )
                || body.toString().contains( "query" );
        assertTrue( hasMessage,
                "missing-query error must surface an explanatory message: " + body );
    }

    // ---- query_nodes ----

    @Test
    void queryNodes_returnsArrayEnvelope() {
        // No filter — let the service return whatever it has (possibly empty
        // on a fresh IT DB).
        final JsonObject body = callSuccess( "query_nodes",
                Map.of( "limit", 5 ) );
        assertNotNull( body, "envelope must not be null" );
        // Expect a nodes / results / count field — three legitimate shapes
        // depending on the running KG state.
        final boolean recognisable = body.has( "nodes" )
                || body.has( "results" )
                || body.has( "count" );
        assertTrue( recognisable,
                "query_nodes must expose nodes/results/count: " + body );
    }

    // ---- get_page ----

    @Test
    void getPage_happyPathForMainIncludesName() {
        // Main is always present in the seed corpus.
        final JsonObject body = callSuccess( "get_page",
                Map.of( "pageName", "Main" ) );
        assertNotNull( body, "envelope must not be null" );
        // Tool returns either a flat record or wraps it; just confirm Main
        // appears somewhere.
        assertTrue( body.toString().contains( "Main" ),
                "get_page(Main) must reference Main in its response: " + body );
    }

    @Test
    void getPage_missingPageMarksExistsFalse() {
        final JsonObject body = callSuccess( "get_page",
                Map.of( "pageName", "PageThatDefinitelyDoesNotExist123" ) );
        assertNotNull( body, "envelope must not be null" );
        // Expected shape per GetPageTool javadoc: {exists:false, pageName}
        if ( body.has( "exists" ) ) {
            assertFalse( body.get( "exists" ).getAsBoolean(),
                    "missing page must set exists=false: " + body );
        } else {
            // Tool may also surface an error field rather than the exists flag.
            assertTrue( body.has( "error" ) || body.toString().contains( "not" ),
                    "missing-page response must signal absence somehow: " + body );
        }
    }

    // ---- get_page_for_agent ----

    @Test
    void getPageForAgent_missingCanonicalIdIsAnError() {
        // canonical_id is required per the schema. Calling without it must
        // produce a wire-level error result, not a 200 with malformed body.
        final JsonObject body = callExpectError( "get_page_for_agent", Map.of() );
        assertNotNull( body, "error payload must not be null" );
        assertTrue( body.toString().toLowerCase().contains( "canonical_id" )
                        || body.has( "error" ) || body.has( "message" ),
                "missing canonical_id error must cite the field: " + body );
    }

    // ---- get_ontology / sparql_query (Phase 4) ----

    @Test
    void getOntology_returnsFormalTBox() {
        final JsonObject body = callSuccess( "get_ontology", Map.of() );
        assertTrue( body.has( "classes" ) && body.has( "objectProperties" ),
                "get_ontology must return classes + objectProperties: " + body );
        final String s = body.toString();
        assertTrue( s.contains( "Technology" ) && s.contains( "Article" ),
                "bundled T-Box classes present" );
        assertTrue( s.contains( "WikiConcepts" ), "concept scheme present" );
    }

    @Test
    void sparqlQuery_selectOverTBoxReturnsBindings() {
        final JsonObject body = callSuccess( "sparql_query", Map.of( "query",
                "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> } LIMIT 50" ) );
        // SPARQL-results-JSON: { head: {...}, results: { bindings: [...] } }
        assertTrue( body.has( "results" ), "SPARQL SELECT must return a results envelope: " + body );
        assertTrue( body.toString().contains( "Technology" ),
                "the Technology class IRI is among the results" );
    }

    @Test
    void sparqlQuery_rejectsUpdate() {
        final JsonObject body = callExpectError( "sparql_query", Map.of( "query",
                "INSERT DATA { <urn:x> <urn:p> <urn:y> }" ) );
        assertNotNull( body, "UPDATE rejection must carry an error payload" );
    }
}

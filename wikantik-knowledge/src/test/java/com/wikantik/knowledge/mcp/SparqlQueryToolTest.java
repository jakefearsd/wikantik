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
package com.wikantik.knowledge.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.wikantik.ontology.OntologyModelManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

class SparqlQueryToolTest {

    private OntologyModelManager loadedManager() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        return mgr;
    }

    private String text( final McpSchema.CallToolResult r ) {
        return ( (McpSchema.TextContent) r.content().get( 0 ) ).text();
    }

    @Test
    void selectReturnsSparqlJson() {
        // Every wk: class is an owl:Class in the T-Box — a SELECT must return bindings.
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> }" ) );
        assertFalse( result.isError() );
        final String json = text( result );
        assertTrue( json.contains( "\"results\"" ) && json.contains( "\"bindings\"" ),
                "SPARQL-results-JSON shape: " + json );
        assertTrue( json.contains( "Technology" ), "the Technology class IRI is a result" );
    }

    @Test
    void compactFormatFlattensSelect() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of(
                "query", "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> } LIMIT 3",
                "format", "compact" ) );
        assertFalse( result.isError() );
        final String json = text( result );
        assertTrue( json.trim().startsWith( "[" ), "compact = flat array, got: " + json );
        assertFalse( json.contains( "\"bindings\"" ), "compact omits the W3C envelope, got: " + json );
        assertTrue( json.contains( "\"c\"" ) && json.contains( "ns/wikantik#" ),
                "compact still carries the var key + binding value: " + json );
    }

    @Test
    void standardFormatStaysW3CByDefault() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of(
                "query", "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> } LIMIT 3" ) );
        assertTrue( text( result ).contains( "\"bindings\"" ), "default stays standard W3C SPARQL-JSON" );
    }

    @Test
    void askReturnsBoolean() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "ASK { <https://wiki.wikantik.com/ns/wikantik#Technology> a "
                        + "<http://www.w3.org/2002/07/owl#Class> }" ) );
        assertFalse( result.isError() );
        assertTrue( text( result ).contains( "true" ), "ASK should be true" );
    }

    @Test
    void updateIsRejected() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "INSERT DATA { <urn:x> <urn:p> <urn:y> }" ) );
        assertTrue( result.isError(), "SPARQL UPDATE must be rejected (read-only endpoint)" );
    }

    @Test
    void malformedQueryIsAnErrorNotACrash() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query", "SELECT ??? broken" ) );
        assertTrue( result.isError() );
    }

    @Test
    void missingQueryIsAnError() {
        assertTrue( new SparqlQueryTool( loadedManager() ).execute( Map.of() ).isError() );
    }
}

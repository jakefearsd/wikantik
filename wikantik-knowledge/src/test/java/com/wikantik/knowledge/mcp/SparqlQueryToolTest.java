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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue( json.contains( "Article" ), "the Article class IRI is a result" );
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
                "ASK { <https://wiki.wikantik.com/ns/wikantik#Article> a "
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

    @Test
    void nameIsSparqlQuery() {
        assertEquals( "sparql_query", new SparqlQueryTool( loadedManager() ).name() );
    }

    @Test
    void constructReturnsTurtle() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "CONSTRUCT { ?c a <http://www.w3.org/2002/07/owl#Class> } "
                        + "WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> } LIMIT 1" ) );
        assertFalse( result.isError() );
        final String turtle = text( result );
        assertTrue( turtle.contains( "PREFIX" ) && turtle.contains( "rdf:type" ),
                "CONSTRUCT should render Turtle: " + turtle );
    }

    @Test
    void serviceClauseIsRejectedAsSsrf() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "SELECT * WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }" ) );
        assertTrue( result.isError(), "SERVICE (federation/SSRF) must be rejected" );
        assertTrue( text( result ).contains( "SERVICE" ), text( result ) );
    }

    private OntologyModelManager managerWithRows( final int rows ) {
        final OntologyModelManager mgr = loadedManager();
        final org.apache.jena.rdf.model.Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        final org.apache.jena.rdf.model.Property p = m.createProperty( "urn:test:p" );
        for ( int i = 0; i < rows; i++ ) {
            m.add( m.createResource( "urn:test:s" + i ), p, "v" + i );
        }
        mgr.replaceNamedGraph( "urn:test:graph", m );
        return mgr;
    }

    private int compactRowCount( final OntologyModelManager mgr, final String query ) {
        final var result = new SparqlQueryTool( mgr ).execute( Map.of( "query", query, "format", "compact" ) );
        assertFalse( result.isError(), text( result ) );
        return com.google.gson.JsonParser.parseString( text( result ) ).getAsJsonArray().size();
    }

    @Test
    void aQueryWithoutALimitIsCappedAtTenThousandRows() {
        final OntologyModelManager mgr = managerWithRows( 10_005 );
        assertEquals( 10_000, compactRowCount( mgr, "SELECT ?s WHERE { ?s <urn:test:p> ?o }" ) );
    }

    @Test
    void aLimitAboveTheCapIsLoweredToTheCap() {
        final OntologyModelManager mgr = managerWithRows( 10_005 );
        assertEquals( 10_000, compactRowCount( mgr, "SELECT ?s WHERE { ?s <urn:test:p> ?o } LIMIT 10001" ) );
    }

    @Test
    void aLimitBelowTheCapIsHonoured() {
        final OntologyModelManager mgr = managerWithRows( 20 );
        assertEquals( 5, compactRowCount( mgr, "SELECT ?s WHERE { ?s <urn:test:p> ?o } LIMIT 5" ) );
    }

    @Test
    void compactFormatReturnsLiteralsAsLexicalFormAndResourcesAsIris() {
        final OntologyModelManager mgr = managerWithRows( 1 );
        final var result = new SparqlQueryTool( mgr ).execute( Map.of(
                "query", "SELECT ?s ?o WHERE { ?s <urn:test:p> ?o }", "format", "COMPACT" ) );
        final com.google.gson.JsonObject row = com.google.gson.JsonParser.parseString( text( result ) )
                .getAsJsonArray().get( 0 ).getAsJsonObject();
        assertEquals( "urn:test:s0", row.get( "s" ).getAsString() );
        assertEquals( "v0", row.get( "o" ).getAsString() );
    }

    @Test
    void compactFormatOmitsUnboundVariablesFromTheRow() {
        final OntologyModelManager mgr = managerWithRows( 1 );
        final var result = new SparqlQueryTool( mgr ).execute( Map.of(
                "query", "SELECT ?s ?missing WHERE { ?s <urn:test:p> ?o OPTIONAL { ?s <urn:test:none> ?missing } }",
                "format", "compact" ) );
        final com.google.gson.JsonObject row = com.google.gson.JsonParser.parseString( text( result ) )
                .getAsJsonArray().get( 0 ).getAsJsonObject();
        assertTrue( row.has( "s" ) );
        assertFalse( row.has( "missing" ) );
    }

    @Test
    void compactFormatDoesNotChangeAskResults() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "ASK { <https://wiki.wikantik.com/ns/wikantik#Article> a <http://www.w3.org/2002/07/owl#Class> }",
                "format", "compact" ) );
        assertTrue( text( result ).contains( "\"boolean\"" ), text( result ) );
    }

    @Test
    void describeReturnsTurtleAboutTheResource() {
        final var result = new SparqlQueryTool( managerWithRows( 1 ) ).execute( Map.of( "query",
                "DESCRIBE <urn:test:s0>" ) );
        assertFalse( result.isError(), text( result ) );
        assertTrue( text( result ).contains( "urn:test:s0" ) && text( result ).contains( "v0" ), text( result ) );
    }

    @Test
    void missingOrBlankQuerySaysWhichArgumentIsNeeded() {
        for ( final Map< String, Object > args : java.util.List.< Map< String, Object > >of( Map.of(), Map.of( "query", "  " ) ) ) {
            final var result = new SparqlQueryTool( loadedManager() ).execute( args );
            assertTrue( result.isError() );
            assertTrue( text( result ).contains( "missing " ) && text( result ).contains( "query" ), text( result ) );
        }
    }

    @Test
    void javaFunctionIrisAreRejectedBeforeAnythingRuns() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query",
                "SELECT ?x WHERE { BIND( <java:java.lang.System.getenv>( \"HOME\" ) AS ?x ) }" ) );
        assertTrue( result.isError(), text( result ) );
    }

    @Test
    void anUnparseableQueryExplainsThatUpdateIsNotAllowed() {
        final var result = new SparqlQueryTool( loadedManager() ).execute( Map.of( "query", "INSERT DATA { <urn:x> <urn:p> <urn:y> }" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "UPDATE is not allowed" ), text( result ) );
    }
}

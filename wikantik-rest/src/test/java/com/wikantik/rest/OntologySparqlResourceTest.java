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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OntologySparqlResource} — the public read-only SPARQL endpoint.
 *
 * <p>Also exercises {@link PublicRdfServletBase#modelManager()} via the page-graph
 * subsystem snapshot.</p>
 */
class OntologySparqlResourceTest {

    private static final String NS = "http://example.org/";

    private OntologyRebuildCoordinator coordinator;
    private OntologyModelManager       modelManager;
    private WikiEngine                 engine;
    private OntologySparqlResource     resource;
    private HttpServletRequest         req;
    private HttpServletResponse        resp;
    private ByteArrayOutputStream      bytes;
    private StringWriter               writerBody;

    /** Minimal {@link ServletOutputStream} that just accumulates bytes. */
    private static final class CapturingOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream sink;
        CapturingOutputStream( final ByteArrayOutputStream sink ) { this.sink = sink; }
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener( final WriteListener listener ) { /* not used */ }
        @Override public void write( final int b ) { sink.write( b ); }
    }

    /** Minimal {@link ServletInputStream} over a fixed body. */
    private static final class FixedInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;
        FixedInputStream( final byte[] data ) { this.delegate = new ByteArrayInputStream( data ); }
        @Override public boolean isFinished() { return delegate.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener( final ReadListener listener ) { /* not used */ }
        @Override public int read() { return delegate.read(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        coordinator  = mock( OntologyRebuildCoordinator.class );
        modelManager = mock( OntologyModelManager.class );
        when( coordinator.modelManager() ).thenReturn( modelManager );
        when( modelManager.inferenceSnapshot() ).thenReturn( sampleModel() );

        engine = mock( WikiEngine.class );
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, coordinator, null, null, null ) );

        resource = new OntologySparqlResource();
        resource.setEngine( engine );

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );

        bytes      = new ByteArrayOutputStream();
        writerBody = new StringWriter();
        when( resp.getOutputStream() ).thenReturn( new CapturingOutputStream( bytes ) );
        when( resp.getWriter() ).thenReturn( new PrintWriter( writerBody ) );
    }

    /** Three subjects so LIMIT behaviour is observable. */
    private static Model sampleModel() {
        final Model m = ModelFactory.createDefaultModel();
        for ( final String local : new String[] { "a", "b", "c" } ) {
            m.add( m.createResource( NS + local ),
                   m.createProperty( NS, "label" ),
                   m.createLiteral( local.toUpperCase( java.util.Locale.ROOT ) ) );
        }
        return m;
    }

    private String out() { return bytes.toString( StandardCharsets.UTF_8 ); }

    private JsonObject errorJson() {
        return JsonParser.parseString( writerBody.toString() ).getAsJsonObject();
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void missingQuery_returns400() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertEquals( "missing 'query'", errorJson().get( "message" ).getAsString() );
    }

    @Test
    void blankQuery_returns400() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "   " );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void ontologyUnavailable_returns503() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "SELECT * WHERE { ?s ?p ?o }" );
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, null, null, null, null ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertEquals( "ontology service not available", errorJson().get( "message" ).getAsString() );
    }

    @Test
    void unparseableQuery_returns400() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "SELEKT * WHERE {" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( errorJson().get( "message" ).getAsString().startsWith( "invalid SPARQL query:" ) );
    }

    @Test
    void sparqlUpdateIsRejectedAsUnparseable() throws Exception {
        when( req.getParameter( "query" ) )
                .thenReturn( "INSERT DATA { <" + NS + "x> <" + NS + "label> \"X\" }" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( errorJson().get( "message" ).getAsString().contains( "invalid SPARQL query" ) );
    }

    // ------------------------------------------------------------------ query forms

    @Test
    void selectQuery_returnsSparqlResultsJson() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "SELECT ?s WHERE { ?s ?p ?o }" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/sparql-results+json" );
        final JsonObject json = JsonParser.parseString( out() ).getAsJsonObject();
        assertEquals( 3, json.getAsJsonObject( "results" ).getAsJsonArray( "bindings" ).size() );
    }

    @Test
    void explicitLimitBelowCapIsPreserved() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1" );

        resource.doGet( req, resp );

        final JsonObject json = JsonParser.parseString( out() ).getAsJsonObject();
        assertEquals( 1, json.getAsJsonObject( "results" ).getAsJsonArray( "bindings" ).size(),
                "a caller-supplied LIMIT under the cap must not be widened" );
    }

    @Test
    void askQuery_returnsBooleanJson() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "ASK { ?s ?p ?o }" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/sparql-results+json" );
        assertTrue( JsonParser.parseString( out() ).getAsJsonObject().get( "boolean" ).getAsBoolean() );
    }

    @Test
    void askQuery_onNoMatch_returnsFalse() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "ASK { <" + NS + "zzz> ?p ?o }" );

        resource.doGet( req, resp );

        assertFalse( JsonParser.parseString( out() ).getAsJsonObject().get( "boolean" ).getAsBoolean() );
    }

    @Test
    void constructQuery_defaultsToTurtle() throws Exception {
        when( req.getParameter( "query" ) )
                .thenReturn( "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }" );
        when( req.getHeader( "Accept" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "text/turtle" );
        assertTrue( out().contains( NS ), out() );
    }

    @Test
    void constructQuery_withJsonAccept_returnsJsonLd() throws Exception {
        when( req.getParameter( "query" ) )
                .thenReturn( "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }" );
        when( req.getHeader( "Accept" ) ).thenReturn( "application/ld+json" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/ld+json" );
        assertTrue( out().trim().startsWith( "{" ) || out().trim().startsWith( "[" ), out() );
    }

    @Test
    void describeQuery_returnsRdf() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "DESCRIBE <" + NS + "a>" );
        when( req.getHeader( "Accept" ) ).thenReturn( "text/turtle" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "text/turtle" );
        assertTrue( out().contains( "A" ), out() );
    }

    // ------------------------------------------------------------------ POST

    @Test
    void post_readsQueryParameterWhenPresent() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "ASK { ?s ?p ?o }" );

        resource.doPost( req, resp );

        assertTrue( JsonParser.parseString( out() ).getAsJsonObject().get( "boolean" ).getAsBoolean() );
    }

    @Test
    void post_readsSparqlQueryBodyWhenParameterAbsent() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( null );
        when( req.getContentType() ).thenReturn( "application/sparql-query; charset=UTF-8" );
        when( req.getInputStream() ).thenReturn(
                new FixedInputStream( "SELECT ?s WHERE { ?s ?p ?o }".getBytes( StandardCharsets.UTF_8 ) ) );

        resource.doPost( req, resp );

        final JsonObject json = JsonParser.parseString( out() ).getAsJsonObject();
        assertEquals( 3, json.getAsJsonObject( "results" ).getAsJsonArray( "bindings" ).size() );
    }

    @Test
    void post_withUnrelatedContentTypeAndNoParameter_returns400() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( null );
        when( req.getContentType() ).thenReturn( "application/x-www-form-urlencoded" );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertEquals( "missing 'query'", errorJson().get( "message" ).getAsString() );
    }

    // ------------------------------------------------------------------ execution failure

    @Test
    void executionFailure_isReportedAs400RatherThanPropagating() throws Exception {
        when( req.getParameter( "query" ) ).thenReturn( "SELECT ?s WHERE { ?s ?p ?o }" );
        when( resp.getOutputStream() ).thenThrow( new IllegalStateException( "stream already closed" ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertTrue( errorJson().get( "message" ).getAsString().startsWith( "query execution failed:" ) );
    }
}

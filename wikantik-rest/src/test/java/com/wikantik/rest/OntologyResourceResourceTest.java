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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link OntologyResourceResource} — per-resource RDF dereferencing at GET /id/{type}/{id}. */
class OntologyResourceResourceTest {

    private static final String NS = "https://wiki.wikantik.com/id/";

    private OntologyModelManager       modelManager;
    private OntologyResourceResource   resource;
    private HttpServletRequest         req;
    private HttpServletResponse        resp;
    private ByteArrayOutputStream      bytes;
    private StringWriter               writerBody;

    private static final class CapturingOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream sink;
        CapturingOutputStream( final ByteArrayOutputStream sink ) { this.sink = sink; }
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener( final WriteListener listener ) { /* not used */ }
        @Override public void write( final int b ) { sink.write( b ); }
    }

    @BeforeEach
    void setUp() throws Exception {
        modelManager = OntologyModelManager.inMemory();

        final OntologyRebuildCoordinator coordinator = mock( OntologyRebuildCoordinator.class );
        when( coordinator.modelManager() ).thenReturn( modelManager );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, coordinator, null, null, null, null ) );

        resource = new OntologyResourceResource();
        resource.setEngine( engine );

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );

        bytes      = new ByteArrayOutputStream();
        writerBody = new StringWriter();
        when( resp.getOutputStream() ).thenReturn( new CapturingOutputStream( bytes ) );
        when( resp.getWriter() ).thenReturn( new PrintWriter( writerBody ) );
    }

    private String out() { return bytes.toString( StandardCharsets.UTF_8 ); }

    private JsonObject errorJson() {
        return JsonParser.parseString( writerBody.toString() ).getAsJsonObject();
    }

    @Test
    void modelManagerUnavailable_returns503() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, null, null, null, null, null ) );
        resource.setEngine( engine );
        when( req.getPathInfo() ).thenReturn( "/page/abc123" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertEquals( "ontology service not available", errorJson().get( "message" ).getAsString() );
    }

    @Test
    void missingPathInfo_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        assertEquals( "expected /id/{type}/{id}", errorJson().get( "message" ).getAsString() );
    }

    @Test
    void tooShortPathInfo_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void unknownResource_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/page/does-not-exist" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        assertTrue( errorJson().get( "message" ).getAsString().contains( NS + "page/does-not-exist" ) );
    }

    @Test
    void knownResource_defaultsToTurtle() throws Exception {
        final String iri = NS + "page/abc123";
        modelManager.replaceNamedGraph( iri, sampleModelFor( iri ) );
        when( req.getPathInfo() ).thenReturn( "/page/abc123" );
        when( req.getHeader( "Accept" ) ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "text/turtle" );
        assertTrue( out().contains( "abc123" ), out() );
    }

    @Test
    void knownResource_withJsonAccept_returnsJsonLd() throws Exception {
        final String iri = NS + "page/abc123";
        modelManager.replaceNamedGraph( iri, sampleModelFor( iri ) );
        when( req.getPathInfo() ).thenReturn( "/page/abc123" );
        when( req.getHeader( "Accept" ) ).thenReturn( "application/ld+json" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/ld+json" );
        assertTrue( out().trim().startsWith( "{" ) || out().trim().startsWith( "[" ), out() );
    }

    private static Model sampleModelFor( final String subjectIri ) {
        final Model m = ModelFactory.createDefaultModel();
        m.add( m.createResource( subjectIri ),
               m.createProperty( "https://wiki.wikantik.com/ns/wikantik#", "title" ),
               m.createLiteral( "Sample page" ) );
        return m;
    }
}

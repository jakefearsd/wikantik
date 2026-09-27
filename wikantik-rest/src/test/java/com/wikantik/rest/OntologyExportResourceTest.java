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

/** {@link OntologyExportResource} — bulk RDF dumps at /export/ontology.ttl and /export/graph.nt. */
class OntologyExportResourceTest {

    private OntologyModelManager     modelManager;
    private OntologyExportResource   resource;
    private HttpServletRequest       req;
    private HttpServletResponse      resp;
    private ByteArrayOutputStream    bytes;
    private StringWriter             writerBody;

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
        modelManager.loadTBox();

        final OntologyRebuildCoordinator coordinator = mock( OntologyRebuildCoordinator.class );
        when( coordinator.modelManager() ).thenReturn( modelManager );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, coordinator, null, null, null, null ) );

        resource = new OntologyExportResource();
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
        when( req.getPathInfo() ).thenReturn( "/ontology.ttl" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertEquals( "ontology service not available", errorJson().get( "message" ).getAsString() );
    }

    @Test
    void ontologyTtlDumpsTheTBoxAsTurtle() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/ontology.ttl" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "text/turtle" );
        assertTrue( out().contains( "wikantik" ), out() );
    }

    @Test
    void graphNtDumpsTheUnionAsNTriples() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/graph.nt" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/n-triples" );
    }

    @Test
    void unrecognizedPathReturns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nonsense.xml" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
        assertTrue( errorJson().get( "message" ).getAsString().contains( "ontology.ttl" ) );
    }
}

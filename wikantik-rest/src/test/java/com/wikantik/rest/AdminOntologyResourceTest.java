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
import com.wikantik.ontology.runtime.OntologyRebuildStatus;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminOntologyResource} — {@code GET /admin/ontology/status},
 * {@code GET /admin/ontology/violations} and {@code POST /admin/ontology/rebuild}.
 */
class AdminOntologyResourceTest {

    private OntologyRebuildCoordinator coordinator;
    private WikiEngine                 engine;
    private AdminOntologyResource      resource;
    private HttpServletRequest         req;
    private HttpServletResponse        resp;
    private StringWriter               body;

    @BeforeEach
    void setUp() throws Exception {
        coordinator = mock( OntologyRebuildCoordinator.class );
        engine      = mock( WikiEngine.class );
        wireCoordinator( coordinator );

        resource = new AdminOntologyResource();
        resource.setEngine( engine );

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    /** Publishes {@code svc} through the page-graph subsystem snapshot the resource reads. */
    private void wireCoordinator( final OntologyRebuildCoordinator svc ) {
        when( engine.getPageGraphSubsystem() ).thenReturn(
                new PageGraphSubsystem.Services( null, null, null, null, svc, null, null, null ) );
    }

    private JsonObject json() {
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    private static OntologyRebuildStatus status( final String state, final boolean enabled,
                                                 final long graphs, final String lastError ) {
        return new OntologyRebuildStatus( state, enabled, graphs, lastError );
    }

    // ------------------------------------------------------------------ status

    @Test
    void status_returnsCoordinatorSnapshot() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( coordinator.status() ).thenReturn( status( "IDLE", true, 42, null ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertEquals( "IDLE", out.get( "state" ).getAsString() );
        assertTrue( out.get( "enabled" ).getAsBoolean() );
        assertEquals( 42, out.get( "graphCount" ).getAsLong() );
        assertEquals( "", out.get( "lastError" ).getAsString(), "null lastError is normalised to empty" );
    }

    @Test
    void status_surfacesLastError() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( coordinator.status() ).thenReturn( status( "IDLE", true, -1, "projector blew up" ) );

        resource.doGet( req, resp );

        assertEquals( "projector blew up", json().get( "lastError" ).getAsString() );
    }

    @Test
    void status_withoutCoordinator_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        wireCoordinator( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void unknownGetAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    // ------------------------------------------------------------------ violations

    @Test
    void violations_onConformantModel_returnsEmptyList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/violations" );
        final OntologyModelManager mgr = mock( OntologyModelManager.class );
        when( mgr.inferenceSnapshot() ).thenReturn( ModelFactory.createDefaultModel() );
        when( coordinator.modelManager() ).thenReturn( mgr );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertEquals( 0, out.get( "count" ).getAsInt() );
        assertEquals( 0, out.getAsJsonArray( "violations" ).size() );
    }

    @Test
    void violations_withoutModelManager_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/violations" );
        when( coordinator.modelManager() ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // ------------------------------------------------------------------ rebuild

    @Test
    void rebuild_returns202WithSnapshot() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/rebuild" );
        when( coordinator.triggerRebuild() ).thenReturn( status( "STARTING", true, 7, null ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 202 );
        assertEquals( "STARTING", json().get( "state" ).getAsString() );
    }

    @Test
    void rebuild_whenAlreadyRunning_returns409WithCurrentStatus() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/rebuild" );
        when( coordinator.triggerRebuild() )
                .thenThrow( new OntologyRebuildCoordinator.ConflictException( "already RUNNING" ) );
        when( coordinator.status() ).thenReturn( status( "RUNNING", true, 3, null ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_CONFLICT );
        assertEquals( "RUNNING", json().get( "state" ).getAsString() );
    }

    @Test
    void rebuild_whenDisabled_returns503NamingTheFlag() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/rebuild" );
        when( coordinator.triggerRebuild() )
                .thenThrow( new OntologyRebuildCoordinator.DisabledException() );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        final JsonObject out = json();
        assertEquals( "ontology disabled", out.get( "error" ).getAsString() );
        assertEquals( "wikantik.ontology.enabled", out.get( "flag" ).getAsString() );
    }

    @Test
    void rebuild_withoutCoordinator_returns503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/rebuild" );
        wireCoordinator( null );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void unknownPostAction_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );   // status is GET-only

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }
}

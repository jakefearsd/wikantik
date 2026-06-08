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

import java.io.IOException;
import java.util.Map;

import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import com.wikantik.ontology.runtime.OntologyRebuildStatus;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Admin endpoint: POST /admin/ontology/rebuild (trigger), GET /admin/ontology/status. */
public class AdminOntologyResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminOntologyResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "status".equals( action ) ) {
            handleStatus( response );
        } else if ( "violations".equals( action ) ) {
            handleViolations( response );
        } else {
            sendNotFound( response, "Unknown ontology endpoint: " + action );
        }
    }

    /** Loaded once: parses the bundled SHACL shapes. */
    private static final com.wikantik.ontology.OntologyShaclValidator SHACL_VALIDATOR =
            new com.wikantik.ontology.OntologyShaclValidator();

    private void handleViolations( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildCoordinator svc = service();
        if ( svc == null || svc.modelManager() == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final java.util.List< com.wikantik.ontology.OntologyShaclValidator.Violation > violations =
                SHACL_VALIDATOR.validate( svc.modelManager().inferenceSnapshot() );
        sendJsonWithStatus( response, 200, java.util.Map.of(
                "violations", violations,
                "count", violations.size() ) );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "rebuild".equals( action ) ) {
            handleRebuild( response );
        } else {
            sendNotFound( response, "Unknown ontology endpoint: " + action );
        }
    }

    private OntologyRebuildCoordinator service() {
        return getSubsystems().pageGraph().ontologyRebuildCoordinator();
    }

    private void handleStatus( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildCoordinator svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        sendJsonWithStatus( response, 200, toMap( svc.status() ) );
    }

    private void handleRebuild( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildCoordinator svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        try {
            final OntologyRebuildStatus snap = svc.triggerRebuild();
            LOG.info( "Ontology rebuild triggered" );
            sendJsonWithStatus( response, 202, toMap( snap ) );
        } catch ( final OntologyRebuildCoordinator.ConflictException e ) {
            LOG.warn( "Ontology rebuild rejected — already running: {}", e.getMessage() );
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT, toMap( svc.status() ) );
        } catch ( final OntologyRebuildCoordinator.DisabledException e ) {
            LOG.warn( "Ontology rebuild rejected — disabled" );
            sendJsonWithStatus( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    Map.of( "error", "ontology disabled", "flag", "wikantik.ontology.enabled" ) );
        }
    }

    private Map< String, Object > toMap( final OntologyRebuildStatus s ) {
        return Map.of( "state", s.state(), "enabled", s.enabled(),
                "graphCount", s.graphCount(), "lastError", s.lastError() == null ? "" : s.lastError() );
    }

    private void sendJsonWithStatus( final HttpServletResponse response, final int status, final Object payload )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( new com.google.gson.GsonBuilder().serializeNulls().create().toJson( payload ) );
    }
}

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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class KnowledgeGraphResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( KnowledgeGraphResource.class );

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = findSession( engine, request );
        // D27: knowledge graph reads are now public to match the rest of /api/structure/*.
        // The graph contains only canonical ids and relationship types — no page bodies
        // or ACL-restricted content — so anonymous readers and agents can use it freely.

        final Tier minTier;
        final String paramRaw = request.getParameter( "min_tier" );
        if ( paramRaw == null || paramRaw.isBlank() ) {
            minTier = defaultMinTier( engine );
        } else {
            try {
                minTier = Tier.fromWire( paramRaw );
            } catch ( final IllegalArgumentException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "min_tier must be 'human' or 'machine'" );
                return;
            }
            LOG.info( "knowledge-graph snapshot min_tier={} principal={}",
                minTier.wireName(), principalName( session ) );
        }

        try {
            final KnowledgeGraphService svc =
                    engine.getManager( KnowledgeGraphService.class );
            final GraphSnapshot snapshot = svc.snapshotGraph( session, minTier );
            sendJson( response, snapshot );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to build knowledge-graph snapshot", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Failed to build graph snapshot" );
        }
    }

    private Tier defaultMinTier( final Engine engine ) {
        final String defaultStr = engine.getWikiProperties()
            .getProperty( "wikantik.kg.read.default_min_tier", "machine" );
        try {
            return Tier.fromWire( defaultStr );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid wikantik.kg.read.default_min_tier='{}', falling back to MACHINE", defaultStr );
            return Tier.MACHINE;
        }
    }

    /** Visible-for-testing seam so unit tests can bypass the static Wiki.session() chain. */
    protected Session findSession( final Engine engine, final HttpServletRequest request ) {
        return Wiki.session().find( engine, request );
    }

    private static String principalName( final Session session ) {
        try {
            return session != null && session.getUserPrincipal() != null
                ? session.getUserPrincipal().getName()
                : "anonymous";
        } catch ( final Exception e ) {
            return "anonymous";
        }
    }
}

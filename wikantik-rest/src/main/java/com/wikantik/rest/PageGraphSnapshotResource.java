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
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Serves the Page Graph snapshot consumed by the {@code /page-graph} React
 * route. Reads pages + wikilinks via {@link PageGraphService}; ACL redaction
 * is applied per-viewer inside the service. Public read — the snapshot
 * carries graph topology only and any restricted node has its identifying
 * fields nulled out.
 *
 * <p>Counterpart to {@code KnowledgeGraphResource} which serves the
 * Knowledge Graph (LLM-extracted entities + typed predicates).</p>
 */
public class PageGraphSnapshotResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageGraphSnapshotResource.class );

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        try {
            final PageGraphService svc = engine.getManager( PageGraphService.class );
            if ( svc == null ) {
                LOG.warn( "PageGraphService not registered — page graph snapshot unavailable" );
                sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                           "Page Graph service is not available" );
                return;
            }
            final PageGraphSnapshot snapshot = svc.snapshot( session );
            sendJson( response, snapshot );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to build page-graph snapshot", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Failed to build page graph snapshot" );
        }
    }
}

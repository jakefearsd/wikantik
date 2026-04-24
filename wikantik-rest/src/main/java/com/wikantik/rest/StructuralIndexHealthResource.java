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
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/** {@code GET /api/health/structural-index} — liveness + staleness summary. */
public class StructuralIndexHealthResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( StructuralIndexHealthResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"status\":\"DOWN\",\"error\":\"service not registered\"}" );
            return;
        }
        final IndexHealth h = svc.health();
        final var snap = svc.snapshot();
        final JsonObject body = new JsonObject();
        body.addProperty( "status", h.status().name() );
        body.addProperty( "pages", h.pages() );
        body.addProperty( "clusters", snap.clusterCount() );
        body.addProperty( "tags", snap.tagCount() );
        body.addProperty( "unclaimed_canonical_ids", h.unclaimedCanonicalIds() );
        body.addProperty( "lag_seconds", h.lagSeconds() );
        body.addProperty( "last_rebuild_duration_ms", h.lastRebuildDurationMillis() );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( h.status() == IndexHealth.Status.UP ? 200 : 503 );
        resp.getWriter().write( GSON.toJson( body ) );
    }
}

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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.StructuralConflict;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * {@code GET /admin/structural-conflicts} — admin-only listing of pages the
 * structural-index rebuild flagged as needing attention. The structural-spine
 * Phase 4 enforcement filter prevents new pages from joining this list, but
 * existing rows surface here until an author resolves them.
 */
public class AdminStructuralConflictsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminStructuralConflictsResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"structural index unavailable\"}" );
            return;
        }
        final List< StructuralConflict > conflicts = svc.conflicts();
        final String kindFilter = req.getParameter( "kind" );

        final JsonArray arr = new JsonArray();
        int missingIds = 0;
        int relationIssues = 0;
        for ( final StructuralConflict c : conflicts ) {
            if ( kindFilter != null && !kindFilter.isBlank()
                    && !c.kind().name().equalsIgnoreCase( kindFilter ) ) {
                continue;
            }
            switch ( c.kind() ) {
                case MISSING_CANONICAL_ID -> missingIds++;
                case RELATION_ISSUE       -> relationIssues++;
            }
            final JsonObject o = new JsonObject();
            o.addProperty( "slug", c.slug() );
            if ( c.canonicalId() != null ) {
                o.addProperty( "canonical_id", c.canonicalId() );
            }
            o.addProperty( "kind", c.kind().name() );
            o.addProperty( "detail", c.detail() );
            arr.add( o );
        }

        final JsonObject data = new JsonObject();
        data.add( "conflicts", arr );
        data.addProperty( "count", arr.size() );
        data.addProperty( "missing_canonical_id_count", missingIds );
        data.addProperty( "relation_issue_count", relationIssues );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }
}

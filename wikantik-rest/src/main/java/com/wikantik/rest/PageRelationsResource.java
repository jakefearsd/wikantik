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
import com.wikantik.api.structure.RelationDirection;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TraversalSpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /api/relations/{canonical_id}} — typed-edge query for the
 * structural-spine relation graph. Mounted under {@code /api/relations/*} to
 * avoid colliding with the existing {@code /api/pages/*} servlet mappings;
 * the path's first segment after the mount is the source canonical_id.
 * Query params: {@code direction=out|in|both} (default out),
 * {@code type=part-of|...} (optional, single type), {@code depth=N} (1..5,
 * default 1).
 */
public class PageRelationsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageRelationsResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        if ( pathInfo.length() <= 1 ) {
            writeError( resp, 400, "canonical_id required in path" );
            return;
        }
        // Expected: /{canonical_id}
        final String canonicalId = pathInfo.startsWith( "/" )
                ? pathInfo.substring( 1 )
                : pathInfo;
        if ( canonicalId.isBlank() || canonicalId.contains( "/" ) ) {
            writeError( resp, 400, "expected /api/relations/{canonical_id}" );
            return;
        }

        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            writeError( resp, 503, "structural index unavailable" );
            return;
        }

        final RelationDirection direction = RelationDirection.fromString( req.getParameter( "direction" ) );
        final Optional< RelationType > typeFilter = Optional.ofNullable( req.getParameter( "type" ) )
                .flatMap( RelationType::fromWire );
        final int depth = parseIntOr( req.getParameter( "depth" ), 1 );

        final List< RelationEdge > edges;
        try {
            if ( depth <= 1 && direction != RelationDirection.BOTH ) {
                edges = direction == RelationDirection.IN
                        ? svc.incomingRelations( canonicalId, typeFilter )
                        : svc.outgoingRelations( canonicalId, typeFilter );
            } else {
                edges = svc.traverse( canonicalId, new TraversalSpec( direction, typeFilter, depth ) );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "/api/pages/{}/relations failed: {}", canonicalId, e.getMessage(), e );
            writeError( resp, 500, e.getMessage() );
            return;
        }

        final JsonArray arr = new JsonArray();
        for ( final RelationEdge e : edges ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "source_id", e.sourceId() );
            if ( e.sourceSlug() != null ) o.addProperty( "source_slug", e.sourceSlug() );
            o.addProperty( "target_id", e.targetId() );
            if ( e.targetSlug() != null )  o.addProperty( "target_slug",  e.targetSlug() );
            if ( e.targetTitle() != null ) o.addProperty( "target_title", e.targetTitle() );
            o.addProperty( "type",  e.type().wireName() );
            o.addProperty( "depth", e.depth() );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.addProperty( "canonical_id", canonicalId );
        data.addProperty( "direction", direction.name().toLowerCase() );
        data.addProperty( "depth",     Math.max( 1, Math.min( 5, depth ) ) );
        if ( typeFilter.isPresent() ) {
            data.addProperty( "type", typeFilter.get().wireName() );
        }
        data.add( "edges", arr );
        data.addProperty( "count", edges.size() );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private void writeError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json; charset=UTF-8" );
        final JsonObject err = new JsonObject();
        err.addProperty( "error", message );
        resp.getWriter().write( GSON.toJson( err ) );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) { return fallback; }
    }
}

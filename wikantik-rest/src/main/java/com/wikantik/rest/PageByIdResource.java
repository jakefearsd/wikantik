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
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code GET /api/pages/by-id/{canonical_id}} — resolves a canonical_id to the
 * page descriptor. Callers that want the full body should follow up with
 * {@code GET /api/pages/{slug}} using the returned {@code slug}.
 */
public class PageByIdResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageByIdResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        if ( pathInfo.length() < 2 ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"canonical_id required in path\"}" );
            return;
        }
        final String canonicalId = pathInfo.substring( 1 );

        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"structural index unavailable\"}" );
            return;
        }

        final Optional< PageDescriptor > found = svc.getByCanonicalId( canonicalId );
        if ( found.isEmpty() ) {
            resp.setStatus( 404 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"no page for canonical_id " + canonicalId + "\"}" );
            return;
        }

        final PageDescriptor p = found.get();
        final JsonObject data = new JsonObject();
        data.addProperty( "id",      p.canonicalId() );
        data.addProperty( "slug",    p.slug() );
        data.addProperty( "title",   p.title() );
        data.addProperty( "type",    p.type().asFrontmatterValue() );
        if ( p.cluster() != null ) data.addProperty( "cluster", p.cluster() );
        if ( p.summary() != null ) data.addProperty( "summary", p.summary() );
        if ( p.updated() != null ) data.addProperty( "updated", p.updated().toString() );
        final JsonArray tags = new JsonArray();
        p.tags().forEach( tags::add );
        data.add( "tags", tags );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }
}

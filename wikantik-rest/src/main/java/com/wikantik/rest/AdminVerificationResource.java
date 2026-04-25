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
import com.wikantik.api.structure.Confidence;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.Verification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /admin/verification} — admin triage of page verification state.
 *
 * <p>Returns every page (subject to optional {@code confidence} and
 * {@code min_days_stale} filters) with its verification metadata, the
 * computed confidence, and the days-since-verified. Aggregate counts per
 * confidence value are included so an operator can see at a glance how much
 * triage is left.</p>
 */
public class AdminVerificationResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminVerificationResource.class );

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

        final Optional< Confidence > confidenceFilter = Optional.ofNullable( req.getParameter( "confidence" ) )
                .flatMap( Confidence::fromWire );
        final int minDaysStale = parseIntOr( req.getParameter( "min_days_stale" ), 0 );
        final int limit = clamp( parseIntOr( req.getParameter( "limit" ), 200 ), 1, 1000 );

        final Instant now = Instant.now();
        final EnumMap< Confidence, Integer > counts = new EnumMap<>( Confidence.class );
        for ( final Confidence c : Confidence.values() ) {
            counts.put( c, 0 );
        }

        final JsonArray rows = new JsonArray();
        // Use the structural index's full page enumeration as the join driver — every
        // canonical_id with a descriptor is a row, augmented with verification when
        // the DAO has one for it.
        final List< PageDescriptor > pages = svc.listPagesByFilter( StructuralFilter.none() );
        int emitted = 0;
        for ( final PageDescriptor p : pages ) {
            final Verification v = svc.verificationOf( p.canonicalId() ).orElse( Verification.unverified() );
            counts.merge( v.confidence(), 1, Integer::sum );

            if ( confidenceFilter.isPresent() && v.confidence() != confidenceFilter.get() ) {
                continue;
            }
            final long daysSince = v.verifiedAt() == null
                    ? Long.MAX_VALUE
                    : Duration.between( v.verifiedAt(), now ).toDays();
            if ( daysSince < minDaysStale ) {
                continue;
            }
            if ( emitted >= limit ) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty( "id",          p.canonicalId() );
            o.addProperty( "slug",        p.slug() );
            o.addProperty( "title",       p.title() );
            o.addProperty( "confidence",  v.confidence().wireName() );
            o.addProperty( "audience",    v.audience().wireName() );
            if ( v.verifiedAt() != null ) {
                o.addProperty( "verified_at", v.verifiedAt().toString() );
                o.addProperty( "days_since_verified", daysSince );
            } else {
                o.addProperty( "verified_at", (String) null );
            }
            if ( v.verifiedBy() != null ) {
                o.addProperty( "verified_by", v.verifiedBy() );
            }
            rows.add( o );
            emitted++;
        }

        final JsonObject countsJson = new JsonObject();
        counts.forEach( ( c, n ) -> countsJson.addProperty( c.wireName(), n ) );

        final JsonObject data = new JsonObject();
        data.add( "pages", rows );
        data.addProperty( "count", rows.size() );
        data.addProperty( "total_pages", pages.size() );
        data.add( "by_confidence", countsJson );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) { return fallback; }
    }

    private static int clamp( final int value, final int lo, final int hi ) {
        return Math.max( lo, Math.min( hi, value ) );
    }
}

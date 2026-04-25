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
import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.eval.RetrievalRunResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code /admin/retrieval-quality} — operator surface for the Phase 5
 * retrieval-quality CI runner.
 *
 * <ul>
 *   <li>{@code GET /admin/retrieval-quality} — recent run history
 *       (filterable by {@code query_set_id}, {@code mode}, {@code limit}).</li>
 *   <li>{@code POST /admin/retrieval-quality/run} — run on demand;
 *       request body {@code {"query_set_id": "...","mode":"..."}}.</li>
 * </ul>
 *
 * <p>Auth is via the shared {@code AdminAuthFilter}. Returns 503 when the
 * {@link RetrievalQualityRunner} is not registered (knowledge graph
 * uninitialised), 400 on bad mode / missing fields, 200 with a
 * {@code data} envelope on success.</p>
 */
public class AdminRetrievalQualityResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminRetrievalQualityResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final RetrievalQualityRunner runner = engine().getManager( RetrievalQualityRunner.class );
        if ( runner == null ) {
            sendUnavailable( resp );
            return;
        }
        final String setIdRaw = req.getParameter( "query_set_id" );
        final String setId = setIdRaw == null || setIdRaw.isBlank() ? null : setIdRaw.trim();
        final String modeRaw = req.getParameter( "mode" );
        RetrievalMode mode = null;
        if ( modeRaw != null && !modeRaw.isBlank() ) {
            final Optional< RetrievalMode > parsed = RetrievalMode.fromWire( modeRaw );
            if ( parsed.isEmpty() ) {
                sendError( resp, HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown mode: " + modeRaw + " (expected one of bm25, hybrid, hybrid_graph)" );
                return;
            }
            mode = parsed.get();
        }
        final int limit = clamp( parseIntOr( req.getParameter( "limit" ), 50 ), 1, 1000 );

        final List< RetrievalRunResult > rows = runner.recentRuns( setId, mode, limit );

        final JsonArray runs = new JsonArray();
        for ( final RetrievalRunResult r : rows ) {
            runs.add( resultToJson( r ) );
        }
        final JsonObject data = new JsonObject();
        data.add( "recent_runs", runs );
        data.addProperty( "count", runs.size() );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        // Only /run is supported under POST.
        final String path = req.getPathInfo();
        if ( path == null || !path.equals( "/run" ) ) {
            sendError( resp, HttpServletResponse.SC_NOT_FOUND, "Not found: " + path );
            return;
        }
        final RetrievalQualityRunner runner = engine().getManager( RetrievalQualityRunner.class );
        if ( runner == null ) {
            sendUnavailable( resp );
            return;
        }
        final JsonObject body = parseJsonBody( req, resp );
        if ( body == null ) return;  // 400 already sent

        final String setId = body.has( "query_set_id" ) ? body.get( "query_set_id" ).getAsString() : null;
        final String modeRaw = body.has( "mode" ) ? body.get( "mode" ).getAsString() : null;
        if ( setId == null || setId.isBlank() ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "Missing query_set_id" );
            return;
        }
        final Optional< RetrievalMode > mode = RetrievalMode.fromWire( modeRaw );
        if ( mode.isEmpty() ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST,
                "Missing or unknown mode: " + modeRaw + " (expected one of bm25, hybrid, hybrid_graph)" );
            return;
        }
        final RetrievalRunResult result;
        try {
            result = runner.runNow( setId, mode.get() );
        } catch ( final IllegalArgumentException e ) {
            // E.g. unknown query_set_id.
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
            return;
        } catch ( final RuntimeException e ) {
            LOG.warn( "runNow(set={},mode={}) failed: {}", setId, mode.get().wireName(), e.getMessage(), e );
            sendError( resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Run failed: " + e.getMessage() );
            return;
        }

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", resultToJson( result ) );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private static JsonObject resultToJson( final RetrievalRunResult r ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "run_id",            r.runId() );
        o.addProperty( "query_set_id",      r.querySetId() );
        o.addProperty( "mode",              r.mode().wireName() );
        addNullable( o, "ndcg_at_5",        r.ndcgAt5() );
        addNullable( o, "ndcg_at_10",       r.ndcgAt10() );
        addNullable( o, "recall_at_20",     r.recallAt20() );
        addNullable( o, "mrr",              r.mrr() );
        o.addProperty( "started_at",        r.startedAt() == null ? null : r.startedAt().toString() );
        o.addProperty( "finished_at",       r.finishedAt() == null ? null : r.finishedAt().toString() );
        o.addProperty( "queries_evaluated", r.queriesEvaluated() );
        o.addProperty( "queries_skipped",   r.queriesSkipped() );
        o.addProperty( "degraded",          r.degraded() );
        return o;
    }

    private static void addNullable( final JsonObject o, final String name, final Double value ) {
        if ( value == null ) {
            o.add( name, com.google.gson.JsonNull.INSTANCE );
        } else {
            o.addProperty( name, value );
        }
    }

    private static void sendUnavailable( final HttpServletResponse resp ) throws IOException {
        resp.setStatus( 503 );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( "{\"error\":\"retrieval-quality runner unavailable\"}" );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) { return fallback; }
    }

    private static int clamp( final int value, final int lo, final int hi ) {
        return Math.max( lo, Math.min( hi, value ) );
    }
}

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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.llm.activity.CallStatus;
import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmActivityLogHolder;
import com.wikantik.llm.activity.LlmCallView;
import com.wikantik.llm.activity.Subsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * {@code GET /admin/llm-activity} — read-only snapshot of recent and in-flight LLM
 * calls (entity extraction, proposal judging, embeddings, section reranking, query
 * decomposition). Backed by the in-memory {@link LlmActivityLog}; nothing is
 * persisted. Auth is via the shared {@code AdminAuthFilter}.
 *
 * <p>Query parameters: {@code limit} (default 200), {@code subsystem}
 * (entity_extraction|proposal_judge|embedding|section_rerank|query_decomposition),
 * {@code status} (in_flight|ok|error).</p>
 */
public class AdminLlmActivityResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 200;
    private static final Logger LOG = LogManager.getLogger( AdminLlmActivityResource.class );

    /** Test seam — {@code doGet} is protected on the servlet base. */
    void doGetForTesting( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        doGet( req, resp );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final LlmActivityLog log = LlmActivityLogHolder.get();

        int limit = DEFAULT_LIMIT;
        final String limitRaw = req.getParameter( "limit" );
        if ( limitRaw != null && !limitRaw.isBlank() ) {
            try {
                limit = Integer.parseInt( limitRaw.trim() );
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Invalid 'limit' query parameter '{}', using default {}", limitRaw, DEFAULT_LIMIT );
                limit = DEFAULT_LIMIT;
            }
        }
        limit = Math.max( 1, Math.min( limit, log.maxRecords() ) );

        final Subsystem subsystem = parseSubsystem( req.getParameter( "subsystem" ) );
        final CallStatus status = parseStatus( req.getParameter( "status" ) );

        final LlmActivityLog.Snapshot snap = log.snapshot( limit, subsystem, status );

        final JsonArray calls = new JsonArray();
        for ( final LlmCallView c : snap.calls() ) {
            calls.add( callToJson( c ) );
        }
        final JsonObject data = new JsonObject();
        data.add( "calls", calls );
        data.addProperty( "inFlight", snap.inFlight() );
        data.addProperty( "enabled", snap.enabled() );
        data.addProperty( "windowMinutes", snap.windowMinutes() );
        data.addProperty( "capacity", snap.maxRecords() );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( envelope.toString() );
    }

    private static JsonObject callToJson( final LlmCallView c ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "seq",             c.seq() );
        o.addProperty( "startedAt",       c.startedAt() );
        o.addProperty( "subsystem",       c.subsystem() );
        o.addProperty( "backend",         c.backend() );
        o.addProperty( "model",           c.model() );
        o.addProperty( "operation",       c.operation() );
        o.addProperty( "status",          c.status() );
        o.addProperty( "durationMs",      c.durationMs() );
        o.addProperty( "promptPreview",   c.promptPreview() );
        o.addProperty( "responsePreview", c.responsePreview() );
        o.add( "inputTokens",  c.inputTokens()  == null ? JsonNull.INSTANCE
                                                        : new com.google.gson.JsonPrimitive( c.inputTokens() ) );
        o.add( "outputTokens", c.outputTokens() == null ? JsonNull.INSTANCE
                                                        : new com.google.gson.JsonPrimitive( c.outputTokens() ) );
        o.addProperty( "errorMessage",    c.errorMessage() );
        return o;
    }

    private static Subsystem parseSubsystem( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        try {
            return Subsystem.valueOf( raw.trim().toUpperCase( java.util.Locale.ROOT ) );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Unknown 'subsystem' query parameter '{}', ignoring filter", raw );
            return null;
        }
    }

    private static CallStatus parseStatus( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        try {
            return CallStatus.valueOf( raw.trim().toUpperCase( java.util.Locale.ROOT ) );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Unknown 'status' query parameter '{}', ignoring filter", raw );
            return null;
        }
    }
}

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
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditQuery;
import com.wikantik.audit.AuditService;
import com.wikantik.audit.PersistedAuditEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin REST resource for the tamper-evident audit log. All requests are
 * pre-authorized by {@link AdminAuthFilter}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /admin/audit}         — filtered list (newest-first, max 1000)</li>
 *   <li>{@code GET /admin/audit/verify}  — verifies the hash chain</li>
 *   <li>{@code GET /admin/audit/export}  — exports all entries as CSV</li>
 * </ul>
 */
public class AdminAuditResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminAuditResource.class );
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    /** Resolves the AuditService from the engine, or returns null if unavailable. */
    private AuditService auditService() {
        final var eng = getEngine();
        return eng instanceof com.wikantik.WikiEngine we ? we.getAuditService() : null;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final AuditService audit = auditService();
        if ( audit == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "audit log unavailable" );
            return;
        }

        final String pathInfo = request.getPathInfo();
        if ( "/verify".equals( pathInfo ) ) {
            doVerify( audit, response );
        } else if ( "/export".equals( pathInfo ) ) {
            doExport( audit, response );
        } else {
            doList( audit, request, response );
        }
    }

    private void doList( final AuditService audit,
                         final HttpServletRequest request,
                         final HttpServletResponse response ) throws IOException {
        final String actor     = request.getParameter( "actor" );
        final String category  = request.getParameter( "category" );
        final String eventType = request.getParameter( "eventType" );
        final String target    = request.getParameter( "target" );
        final String outcome   = request.getParameter( "outcome" );
        final String from      = request.getParameter( "from" );
        final String to        = request.getParameter( "to" );
        final String beforeSeqRaw = request.getParameter( "beforeSeq" );
        final String limitRaw  = request.getParameter( "limit" );

        AuditCategory cat = null;
        if ( category != null && !category.isBlank() ) {
            try {
                cat = AuditCategory.valueOf( category.toUpperCase( Locale.ROOT ) );
            } catch ( final IllegalArgumentException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown category: " + category );
                return;
            }
        }

        AuditOutcome out = null;
        if ( outcome != null && !outcome.isBlank() ) {
            try {
                out = AuditOutcome.valueOf( outcome.toUpperCase( Locale.ROOT ) );
            } catch ( final IllegalArgumentException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown outcome: " + outcome );
                return;
            }
        }

        Instant fromInstant = null;
        if ( from != null && !from.isBlank() ) {
            try {
                fromInstant = Instant.parse( from );
            } catch ( final Exception e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid 'from' timestamp: " + from );
                return;
            }
        }

        Instant toInstant = null;
        if ( to != null && !to.isBlank() ) {
            try {
                toInstant = Instant.parse( to );
            } catch ( final Exception e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid 'to' timestamp: " + to );
                return;
            }
        }

        int limit = DEFAULT_LIMIT;
        if ( limitRaw != null && !limitRaw.isBlank() ) {
            try {
                limit = Integer.parseInt( limitRaw.trim() );
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Invalid 'limit' query parameter '{}', using default {}", limitRaw, DEFAULT_LIMIT );
            }
        }
        limit = Math.min( Math.max( 1, limit ), MAX_LIMIT );

        long beforeSeq = Long.MAX_VALUE;
        if ( beforeSeqRaw != null && !beforeSeqRaw.isBlank() ) {
            try {
                beforeSeq = Long.parseLong( beforeSeqRaw.trim() );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid 'beforeSeq': " + beforeSeqRaw );
                return;
            }
        }

        final AuditQuery q = new AuditQuery(
                actor, cat, eventType, target,
                fromInstant, toInstant, out, limit, beforeSeq );
        final List<PersistedAuditEntry> rows = audit.query( q );
        final JsonArray arr = new JsonArray();
        for ( final PersistedAuditEntry r : rows ) {
            arr.add( toJson( r ) );
        }
        sendJson( response, arr );
    }

    private void doVerify( final AuditService audit,
                           final HttpServletResponse response ) throws IOException {
        final var broken = audit.verifyChain( 1, Long.MAX_VALUE );
        if ( broken.isPresent() ) {
            sendJson( response, Map.of( "ok", false, "firstBrokenSeq", broken.get() ) );
        } else {
            sendJson( response, Map.of( "ok", true ) );
        }
    }

    private void doExport( final AuditService audit,
                           final HttpServletResponse response ) throws IOException {
        final List<PersistedAuditEntry> rows = audit.query( AuditQuery.all() );
        final StringBuilder sb = new StringBuilder(
                "seq,created_at,event_time,category,event_type,actor,outcome,target,source_ip\n" );
        for ( final PersistedAuditEntry r : rows ) {
            final AuditEntry e = r.entry();
            sb.append( r.seq() ).append( ',' )
              .append( r.createdAt() ).append( ',' )
              .append( e.eventTime() ).append( ',' )
              .append( e.category() ).append( ',' )
              .append( e.eventType() ).append( ',' )
              .append( nz( e.actorPrincipal() ) ).append( ',' )
              .append( e.outcome() ).append( ',' )
              .append( nz( e.targetId() ) ).append( ',' )
              .append( nz( e.sourceIp() ) ).append( '\n' );
        }
        response.setContentType( "text/csv; charset=UTF-8" );
        response.setHeader( "Content-Disposition", "attachment; filename=audit-log.csv" );
        response.setStatus( HttpServletResponse.SC_OK );
        response.getWriter().write( sb.toString() );
    }

    private static JsonObject toJson( final PersistedAuditEntry r ) {
        final AuditEntry e = r.entry();
        final JsonObject o = new JsonObject();
        o.addProperty( "seq",          r.seq() );
        o.addProperty( "createdAt",    r.createdAt() != null ? r.createdAt().toString() : null );
        o.addProperty( "rowHash",      r.rowHash() );
        o.addProperty( "prevHash",     r.prevHash() );
        // entry fields
        o.addProperty( "eventTime",    e.eventTime() != null ? e.eventTime().toString() : null );
        o.addProperty( "category",     e.category() != null ? e.category().name() : null );
        o.addProperty( "eventType",    e.eventType() );
        o.addProperty( "actorId",      e.actorId() );
        o.addProperty( "actorPrincipal", e.actorPrincipal() );
        o.addProperty( "actorType",    e.actorType() );
        o.addProperty( "targetType",   e.targetType() );
        o.addProperty( "targetId",     e.targetId() );
        o.addProperty( "targetLabel",  e.targetLabel() );
        o.addProperty( "outcome",      e.outcome() != null ? e.outcome().name() : null );
        o.addProperty( "sourceIp",     e.sourceIp() );
        o.addProperty( "userAgent",    e.userAgent() );
        o.addProperty( "correlationId", e.correlationId() );
        o.addProperty( "detail",       e.detail() );
        return o;
    }

    private static String nz( final String s ) { return s == null ? "" : s; }
}

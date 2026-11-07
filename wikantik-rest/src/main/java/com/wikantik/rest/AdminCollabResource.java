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
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.adapter.CollabWiring;
import com.wikantik.collab.model.DraftListing;
import com.wikantik.collab.model.PurgeOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The administrator's view of collaborative drafts (docs/MultiEdit.MD, Phase 3), {@code /admin/collab/*}, behind
 * {@link AdminAuthFilter} ({@code AllPermission}):
 *
 * <ul>
 *   <li>{@code GET /admin/collab/drafts}: 200 {@code {drafts:[{sessionId, pageName, status, createdBy, createdByName,
 *       createdAt, lastEditAt, live, participants, publishPending}]}}, every stored draft oldest edit first. An admin
 *       surface may show logins, so {@code createdBy} is the login and {@code createdByName} its public display
 *       name.</li>
 *   <li>{@code DELETE /admin/collab/drafts/{sessionId}}: the purge (every participant is evicted {@code purged}; never
 *       publishes), passing the administrator's login as the admin key for the audit: 204, 404 {@code unknown}, 423
 *       {@code publish-in-progress} or 503 {@code store} (the draft is kept). The delete is CSRF-checked like every
 *       admin write ({@code CsrfProtectionFilter} covers state-changing {@code /admin/*} requests).</li>
 * </ul>
 *
 * <p>Every response is {@code Cache-Control: no-store}; 503 {@code collab-disabled} when no collaboration registry is
 * wired.</p>
 */
public class AdminCollabResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminCollabResource.class );
    private static final String DRAFTS = "/drafts";
    private static final String STORE = "store";

    private final transient CollabSessionRegistry fixedRegistry;
    /** True when the registry was handed in (tests); otherwise it is read from the engine per request. */
    private final boolean registryFixed;
    private final transient Function< String, String > displayName;
    private final transient Function< HttpServletRequest, String > adminKey;

    public AdminCollabResource() {
        this.fixedRegistry = null;
        this.registryFixed = false;
        this.displayName = null;
        this.adminKey = null;
    }

    /** For tests: a fixed registry (null: none wired), the login-to-display-name lookup and the caller's login. */
    AdminCollabResource( final CollabSessionRegistry registry, final Function< String, String > displayName,
                         final Function< HttpServletRequest, String > adminKey ) {
        this.fixedRegistry = registry;
        this.registryFixed = true;
        this.displayName = displayName;
        this.adminKey = adminKey;
    }

    /** Admin endpoints are same-origin only. */
    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private Optional< CollabSessionRegistry > registry() {
        return registryFixed ? Optional.ofNullable( fixedRegistry ) : CollabWiring.registryFor( getEngine() );
    }

    private String nameOf( final String login ) {
        if( displayName != null ) {
            return displayName.apply( login );
        }
        return getSubsystems().auth().publicIdentity().displayName( login );
    }

    private String loginOf( final HttpServletRequest request ) {
        if( adminKey != null ) {
            return adminKey.apply( request );
        }
        final Session session = Wiki.session().find( getEngine(), request );
        return AuthorKeys.authorKeyFor( session );
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        CollabWire.noStore( response );
        if( !DRAFTS.equals( request.getPathInfo() ) ) {
            sendNotFound( response, "Unknown collaboration admin call" );
            return;
        }
        final Optional< CollabSessionRegistry > reg = registry();
        if( reg.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Collaboration is not available",
                "collab-disabled" );
            return;
        }
        final List< DraftListing > drafts;
        try {
            drafts = reg.get().drafts();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: listing the drafts failed: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The draft store is unavailable", STORE );
            return;
        }
        final JsonArray out = new JsonArray();
        for( final DraftListing d : drafts ) {
            out.add( listing( d ) );
        }
        final JsonObject body = new JsonObject();
        body.add( "drafts", out );
        RestJson.sendJsonKeepingNulls( response, body );
    }

    private JsonObject listing( final DraftListing d ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "sessionId", d.sessionId().toString() );
        o.addProperty( "pageName", d.pageName() );
        o.addProperty( "status", d.status() );
        o.addProperty( "createdBy", d.createdBy() );
        o.addProperty( "createdByName", nameOf( d.createdBy() ) );
        o.addProperty( "createdAt", iso( d.createdAt() ) );
        o.addProperty( "lastEditAt", iso( d.lastEditAt() ) );
        o.addProperty( "live", d.live() );
        o.addProperty( "participants", d.participants() );
        o.addProperty( "publishPending", d.publishPending() );
        return o;
    }

    private static String iso( final Instant i ) {
        return i == null ? null : i.toString();
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        CollabWire.noStore( response );
        final String path = request.getPathInfo();
        final UUID id = path != null && path.startsWith( DRAFTS + "/" ) ? uuid( path.substring( DRAFTS.length() + 1 ) )
            : null;
        if( id == null ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "No such draft", "unknown" );
            return;
        }
        final Optional< CollabSessionRegistry > reg = registry();
        if( reg.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Collaboration is not available",
                "collab-disabled" );
            return;
        }
        final String admin = loginOf( request );
        final PurgeOutcome out = reg.get().purge( id, admin );
        switch( out.status() ) {
            // the registry logs and audits a purge itself (DraftAdmin.purge)
            case HttpServletResponse.SC_NO_CONTENT -> response.setStatus( HttpServletResponse.SC_NO_CONTENT );
            case HttpServletResponse.SC_NOT_FOUND -> sendError( response, out.status(), "No such draft",
                out.reason() );
            case 423 -> sendError( response, out.status(), "A publish of this draft is in progress; try again",
                out.reason() );
            default -> sendError( response, out.status(), "The draft could not be removed; it is kept",
                out.reason() == null ? STORE : out.reason() );
        }
    }

    private static UUID uuid( final String raw ) {
        try {
            return UUID.fromString( raw );
        } catch( final IllegalArgumentException e ) {
            LOG.debug( "collab admin: unparseable draft id '{}': {}", raw, e.getMessage() );
            return null;
        }
    }
}

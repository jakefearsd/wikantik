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
package com.wikantik.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserLifecycleService;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.sso.SSOAutoProvisionService;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCIM 2.0 Users resource servlet ({@code /scim/v2/Users} and {@code /scim/v2/Users/*}).
 *
 * <p>Implements:
 * <ul>
 *   <li>POST   /scim/v2/Users       — provision a user (SSO fail-closed)</li>
 *   <li>GET    /scim/v2/Users/{id}  — retrieve by uid</li>
 *   <li>GET    /scim/v2/Users       — list / filter (userName, externalId)</li>
 *   <li>PUT    /scim/v2/Users/{id}  — replace attributes</li>
 *   <li>PATCH  /scim/v2/Users/{id}  — partial update</li>
 *   <li>DELETE /scim/v2/Users/{id}  — soft-delete (deactivate)</li>
 * </ul>
 * All responses use {@code Content-Type: application/scim+json}.</p>
 *
 * <p>The engine is resolved once in {@link #init(ServletConfig)} via
 * {@code Wiki.engine().find(config)}; managers are obtained lazily from it.</p>
 */
public class ScimUserResource extends AbstractScimServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ScimUserResource.class );
    private static final String SCIM_ACTOR = "scim";
    // CONTENT_TYPE, GSON, and the parse/send helpers are inherited from AbstractScimServlet.

    private transient Engine engine;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config );
        LOG.info( "ScimUserResource initialized (engine={})", engine != null );
    }

    // -------------------------------------------------------------------------
    // HTTP method dispatch
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        handleCreate( req, resp );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractId( req );
        if ( id != null ) {
            handleGetById( id, req, resp );
        } else {
            handleList( req, resp );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractId( req );
        if ( id == null ) {
            sendError( resp, 400, null, "PUT requires a user id in the path" );
            return;
        }
        handleReplace( id, req, resp );
    }

    /** Dispatches PATCH (not a standard HttpServlet override — must override service). */
    @Override
    protected void service( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {
        if ( "PATCH".equalsIgnoreCase( req.getMethod() ) ) {
            final String id = extractId( req );
            if ( id == null ) {
                sendError( resp, 400, null, "PATCH requires a user id in the path" );
                return;
            }
            handlePatch( id, req, resp );
        } else {
            super.service( req, resp );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String id = extractId( req );
        if ( id == null ) {
            sendError( resp, 400, null, "DELETE requires a user id in the path" );
            return;
        }
        handleDelete( id, resp );
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleCreate( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        final ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( body );
        if ( f.userName() == null || f.userName().isBlank() ) {
            sendError( resp, 400, "invalidValue", "userName is required" );
            return;
        }

        final UserDatabase db = getUserDatabase();
        if ( db == null ) {
            sendError( resp, 503, null, "user database unavailable" );
            return;
        }

        // SSO fail-closed: if a profile exists for this userName AND it lacks sso.subject → 409
        try {
            final UserProfile existing = db.findByLoginName( f.userName() );
            final Object linked = existing.getAttributes() == null ? null
                    : existing.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT );
            if ( linked == null ) {
                sendError( resp, 409, "uniqueness",
                        "A local account with userName '" + f.userName()
                                + "' already exists and is not SSO-linked; SCIM cannot claim it." );
                return;
            }
            // Already exists and is SSO-linked — treat as idempotent conflict too (caller should PUT/PATCH)
            sendError( resp, 409, "uniqueness",
                    "User '" + f.userName() + "' already exists." );
            return;
        } catch ( final NoSuchPrincipalException ignored ) {
            // Expected — no existing account; proceed to create
        }

        try {
            final UserProfile p = db.newProfile();
            p.setLoginName( f.userName() );
            if ( f.fullName() != null ) p.setFullname( f.fullName() );
            if ( f.email() != null ) p.setEmail( f.email() );
            if ( f.displayName() != null ) p.setWikiName( f.displayName() );
            // Stamp sso.subject BEFORE save so the identity link is atomic with creation
            if ( f.externalId() != null ) {
                p.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT, f.externalId() );
            }
            // If no password supplied, generate a random one so the account exists but
            // authenticates only via SSO (the random token is never revealed)
            final String password = ( f.password() != null && !f.password().isBlank() )
                    ? f.password() : UUID.randomUUID().toString();
            p.setPassword( password );
            db.save( p );

            // Reload to get server-assigned uid
            final UserProfile saved = db.findByLoginName( f.userName() );

            // If active==false, immediately deactivate
            if ( Boolean.FALSE.equals( f.active() ) ) {
                try {
                    lifecycle( db ).deactivate( f.userName(), SCIM_ACTOR, SCIM_ACTOR );
                } catch ( final WikiSecurityException e ) {
                    LOG.warn( "SCIM create: failed to deactivate newly-created user={}: {}",
                            f.userName(), e.getMessage(), e );
                }
            }

            auditRecord( "scim.user.create", f.userName() );

            final String usersBaseUrl = usersBaseUrl( req );
            resp.setStatus( 201 );
            resp.setHeader( "Location", usersBaseUrl + "/" + saved.getUid() );
            sendScim( resp, ScimUserMapper.toScim( saved, usersBaseUrl ) );

        } catch ( final WikiSecurityException e ) {
            LOG.warn( "SCIM create user={} failed: {}", f.userName(), e.getMessage(), e );
            sendError( resp, 409, "uniqueness", "Failed to create user: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM create user={} unexpected error: {}", f.userName(), e.getMessage(), e );
            sendError( resp, 500, null, "Internal error creating user" );
        }
    }

    private void handleGetById( final String id, final HttpServletRequest req,
                                final HttpServletResponse resp ) throws IOException {
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }
        try {
            final UserProfile p = db.findByUid( id );
            sendScim( resp, ScimUserMapper.toScim( p, usersBaseUrl( req ) ) );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found: " + id );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM GET user uid={} error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error retrieving user" );
        }
    }

    private void handleList( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        final String filter = req.getParameter( "filter" );
        final int startIndex = parseIntParam( req, "startIndex", 1 );
        final int count = parseIntParam( req, "count", 100 );
        final String usersBaseUrl = usersBaseUrl( req );

        try {
            final List<UserProfile> matched;
            if ( filter != null && !filter.isBlank() ) {
                // May throw UnsupportedFilterException → 400
                final var eq = ScimFilterParser.parse( filter );
                if ( eq.isEmpty() ) {
                    // blank filter — list all
                    matched = listAll( db );
                } else {
                    final ScimFilterParser.Eq eq0 = eq.get();
                    if ( "userName".equals( eq0.attribute() ) ) {
                        List<UserProfile> byName;
                        try {
                            byName = List.of( db.findByLoginName( eq0.value() ) );
                        } catch ( final NoSuchPrincipalException e ) {
                            byName = List.of();
                        }
                        matched = byName;
                    } else if ( "externalId".equals( eq0.attribute() ) ) {
                        matched = findByExternalId( db, eq0.value() );
                    } else {
                        sendError( resp, 400, "invalidFilter",
                                "Filtering on '" + eq0.attribute() + "' is not supported" );
                        return;
                    }
                }
            } else {
                matched = listAll( db );
            }

            // Pagination
            final int total = matched.size();
            final int from = Math.max( 0, startIndex - 1 ); // SCIM startIndex is 1-based
            final List<UserProfile> page = matched.subList(
                    Math.min( from, total ),
                    Math.min( from + count, total ) );

            final JsonArray resources = new JsonArray();
            for ( final UserProfile p : page ) {
                resources.add( ScimUserMapper.toScim( p, usersBaseUrl ) );
            }

            final JsonObject listResp = new JsonObject();
            final JsonArray schemas = new JsonArray();
            schemas.add( "urn:ietf:params:scim:api:messages:2.0:ListResponse" );
            listResp.add( "schemas", schemas );
            listResp.addProperty( "totalResults", total );
            listResp.addProperty( "startIndex", startIndex );
            listResp.addProperty( "itemsPerPage", page.size() );
            listResp.add( "Resources", resources );

            sendScim( resp, listResp );

        } catch ( final ScimFilterParser.UnsupportedFilterException e ) {
            sendError( resp, 400, "invalidFilter", e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM list users error: {}", e.getMessage(), e );
            sendError( resp, 500, null, "Internal error listing users" );
        }
    }

    private void handleReplace( final String id, final HttpServletRequest req,
                                final HttpServletResponse resp ) throws IOException {
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        UserProfile p;
        try {
            p = db.findByUid( id );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found: " + id );
            return;
        }

        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        final ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( body );
        try {
            final boolean wasActive = !p.isLocked();
            if ( f.fullName() != null ) p.setFullname( f.fullName() );
            if ( f.email() != null ) p.setEmail( f.email() );
            if ( f.displayName() != null ) p.setWikiName( f.displayName() );
            if ( f.externalId() != null ) {
                p.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT, f.externalId() );
            }
            if ( f.password() != null && !f.password().isBlank() ) {
                p.setPassword( f.password() );
            }

            // Handle active transition via lifecycle service
            if ( f.active() != null ) {
                final boolean nowActive = f.active();
                if ( wasActive && !nowActive ) {
                    lifecycle( db ).deactivate( p.getLoginName(), SCIM_ACTOR, SCIM_ACTOR );
                } else if ( !wasActive && nowActive ) {
                    lifecycle( db ).reactivate( p.getLoginName(), SCIM_ACTOR, SCIM_ACTOR );
                } else {
                    db.save( p );
                }
            } else {
                db.save( p );
            }

            // Reload after potential lifecycle ops
            p = db.findByUid( id );
            auditRecord( "scim.user.update", p.getLoginName() );
            sendScim( resp, ScimUserMapper.toScim( p, usersBaseUrl( req ) ) );

        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found after update: " + id );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "SCIM PUT user uid={} security error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to update user: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM PUT user uid={} error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error updating user" );
        }
    }

    private void handlePatch( final String id, final HttpServletRequest req,
                              final HttpServletResponse resp ) throws IOException {
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        UserProfile p;
        try {
            p = db.findByUid( id );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found: " + id );
            return;
        }

        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        try {
            final ScimPatchApplier.Result patch = ScimPatchApplier.apply( body );

            // Apply active change via lifecycle
            if ( patch.activeChange() != null ) {
                if ( patch.activeChange() ) {
                    lifecycle( db ).reactivate( p.getLoginName(), SCIM_ACTOR, SCIM_ACTOR );
                } else {
                    lifecycle( db ).deactivate( p.getLoginName(), SCIM_ACTOR, SCIM_ACTOR );
                }
                // Reload profile after lifecycle op
                p = db.findByUid( id );
            }

            // Apply other simple attributes
            final JsonObject attrs = patch.attributes();
            boolean dirty = false;
            if ( attrs.has( "displayName" ) && !attrs.get( "displayName" ).isJsonNull() ) {
                p.setWikiName( attrs.get( "displayName" ).getAsString() );
                dirty = true;
            }
            if ( attrs.has( "name" ) && attrs.get( "name" ).isJsonObject() ) {
                final JsonObject n = attrs.getAsJsonObject( "name" );
                if ( n.has( "formatted" ) && !n.get( "formatted" ).isJsonNull() ) {
                    p.setFullname( n.get( "formatted" ).getAsString() );
                    dirty = true;
                }
            }
            if ( attrs.has( "emails" ) && attrs.get( "emails" ).isJsonArray() ) {
                final var arr = attrs.getAsJsonArray( "emails" );
                if ( arr.size() > 0 ) {
                    final JsonObject em = arr.get( 0 ).getAsJsonObject();
                    if ( em.has( "value" ) && !em.get( "value" ).isJsonNull() ) {
                        p.setEmail( em.get( "value" ).getAsString() );
                        dirty = true;
                    }
                }
            }
            if ( attrs.has( "externalId" ) && !attrs.get( "externalId" ).isJsonNull() ) {
                p.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT,
                        attrs.get( "externalId" ).getAsString() );
                dirty = true;
            }
            if ( dirty ) {
                db.save( p );
                p = db.findByUid( id );
            }

            auditRecord( "scim.user.update", p.getLoginName() );
            sendScim( resp, ScimUserMapper.toScim( p, usersBaseUrl( req ) ) );

        } catch ( final ScimPatchApplier.UnsupportedPatchException e ) {
            sendError( resp, 400, "invalidPath", e.getMessage() );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found after patch: " + id );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "SCIM PATCH user uid={} security error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to patch user: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM PATCH user uid={} error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error patching user" );
        }
    }

    private void handleDelete( final String id, final HttpServletResponse resp )
            throws IOException {
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        final UserProfile p;
        try {
            p = db.findByUid( id );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found: " + id );
            return;
        }

        try {
            lifecycle( db ).deactivate( p.getLoginName(), SCIM_ACTOR, "scim/delete" );
            resp.setStatus( 204 );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "User not found: " + id );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "SCIM DELETE user uid={} security error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to deactivate user: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM DELETE user uid={} error: {}", id, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error deactivating user" );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Extracts the id segment from path-info, e.g. {@code /abc123} → {@code abc123}. */
    private static String extractId( final HttpServletRequest req ) {
        final String pi = req.getPathInfo();
        if ( pi == null || pi.equals( "/" ) || pi.isBlank() ) return null;
        final String trimmed = pi.startsWith( "/" ) ? pi.substring( 1 ) : pi;
        return trimmed.isBlank() ? null : trimmed;
    }

    /** Derives the Users collection URL, e.g. {@code https://host/scim/v2/Users}. */
    private static String usersBaseUrl( final HttpServletRequest req ) {
        final String url = req.getRequestURL().toString();
        final int idx = url.indexOf( "/Users" );
        return idx >= 0 ? url.substring( 0, idx + "/Users".length() ) : url;
    }

    private UserDatabase getUserDatabase() {
        if ( engine == null ) return null;
        try {
            if ( engine instanceof WikiEngine we ) {
                return we.getManager( UserManager.class ).getUserDatabase();
            }
            LOG.warn( "ScimUserResource: engine is not a WikiEngine; cannot obtain UserDatabase" );
            return null;
        } catch ( final Exception e ) {
            LOG.warn( "ScimUserResource: could not obtain UserDatabase: {}", e.getMessage(), e );
            return null;
        }
    }

    private AuditService getAuditService() {
        return ( engine instanceof WikiEngine we ) ? we.getAuditService() : null;
    }

    private UserLifecycleService lifecycle( final UserDatabase db ) {
        // UserLifecycleService guards audit calls in try/catch with LOG.warn — safe to pass null
        return new UserLifecycleService( db, getAuditService() );
    }

    private void auditRecord( final String eventType, final String login ) {
        final AuditService audit = getAuditService();
        if ( audit == null ) return;
        try {
            audit.record( AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( eventType )
                    .outcome( AuditOutcome.SUCCESS )
                    .actorPrincipal( SCIM_ACTOR )
                    .actorType( "system" )
                    .targetType( "user" )
                    .targetId( login )
                    .targetLabel( login )
                    .build() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM audit record failed for event={} user={}: {}", eventType, login, e.getMessage(), e );
        }
    }

    private List<UserProfile> listAll( final UserDatabase db ) throws WikiSecurityException {
        final Principal[] wikiNames = db.getWikiNames();
        final List<UserProfile> profiles = new ArrayList<>( wikiNames.length );
        for ( final Principal p : wikiNames ) {
            try {
                profiles.add( db.findByWikiName( p.getName() ) );
            } catch ( final NoSuchPrincipalException e ) {
                LOG.warn( "ScimUserResource: could not load profile for wikiName={}: {}",
                        p.getName(), e.getMessage() );
            }
        }
        return profiles;
    }

    private List<UserProfile> findByExternalId( final UserDatabase db, final String externalId )
            throws WikiSecurityException {
        final List<UserProfile> all = listAll( db );
        final List<UserProfile> matched = new ArrayList<>();
        for ( final UserProfile p : all ) {
            final Object linked = p.getAttributes() == null ? null
                    : p.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT );
            if ( externalId.equals( linked ) ) {
                matched.add( p );
            }
        }
        return matched;
    }

}

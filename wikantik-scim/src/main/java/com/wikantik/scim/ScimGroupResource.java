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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * SCIM 2.0 Groups resource servlet ({@code /scim/v2/Groups} and {@code /scim/v2/Groups/*}).
 *
 * <p>Implements:
 * <ul>
 *   <li>POST   /scim/v2/Groups        — create a group</li>
 *   <li>GET    /scim/v2/Groups/{name} — retrieve by name</li>
 *   <li>GET    /scim/v2/Groups        — list / filter (displayName eq)</li>
 *   <li>PUT    /scim/v2/Groups/{name} — replace membership</li>
 *   <li>PATCH  /scim/v2/Groups/{name} — partial membership update</li>
 *   <li>DELETE /scim/v2/Groups/{name} — hard delete</li>
 * </ul>
 * All responses use {@code Content-Type: application/scim+json}.
 *
 * <p><strong>Admin-role invariant:</strong> this resource only calls {@link GroupManager} and
 * {@link UserDatabase}. It never writes the {@code roles} table or invokes any role-management API.
 * A SCIM group named "Admin" creates a group only — it does not grant any role.</p>
 */
public class ScimGroupResource extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ScimGroupResource.class );
    private static final String CONTENT_TYPE = "application/scim+json";
    private static final String SCIM_ACTOR = "scim";
    private static final Gson GSON = new Gson();

    private transient Engine engine;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config );
        LOG.info( "ScimGroupResource initialized (engine={})", engine != null );
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
        final String name = extractName( req );
        if ( name != null ) {
            handleGetByName( name, req, resp );
        } else {
            handleList( req, resp );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String name = extractName( req );
        if ( name == null ) {
            sendError( resp, 400, null, "PUT requires a group name in the path" );
            return;
        }
        handleReplace( name, req, resp );
    }

    /** Dispatches PATCH (not a standard HttpServlet override — must override service). */
    @Override
    protected void service( final HttpServletRequest req, final HttpServletResponse resp )
            throws ServletException, IOException {
        if ( "PATCH".equalsIgnoreCase( req.getMethod() ) ) {
            final String name = extractName( req );
            if ( name == null ) {
                sendError( resp, 400, null, "PATCH requires a group name in the path" );
                return;
            }
            handlePatch( name, req, resp );
        } else {
            super.service( req, resp );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String name = extractName( req );
        if ( name == null ) {
            sendError( resp, 400, null, "DELETE requires a group name in the path" );
            return;
        }
        handleDelete( name, resp );
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleCreate( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        final String displayName = ScimGroupMapper.readDisplayName( body );
        if ( displayName == null || displayName.isBlank() ) {
            sendError( resp, 400, "invalidValue", "displayName is required" );
            return;
        }

        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        // 409 if group already exists
        try {
            gm.getGroup( displayName );
            sendError( resp, 409, "uniqueness", "Group '" + displayName + "' already exists." );
            return;
        } catch ( final NoSuchPrincipalException ignored ) {
            // Expected — group does not exist; proceed to create
        }

        final List<String> memberUids;
        try {
            memberUids = ScimGroupMapper.readMemberUids( body );
        } catch ( final ScimGroupMapper.NestedGroupUnsupportedException e ) {
            sendError( resp, 400, "invalidValue", e.getMessage() );
            return;
        }

        try {
            saveGroupWithMembers( displayName, memberUids, gm, db );
            auditRecord( "scim.group.create", displayName );

            final Group saved = gm.getGroup( displayName );
            final String usersBase = usersBaseUrl( req );
            final String groupsBase = groupsBaseUrl( req );
            resp.setStatus( 201 );
            resp.setHeader( "Location", groupsBase + "/" + displayName );
            sendScim( resp, ScimGroupMapper.toScim( saved, usersBase, groupsBase, login -> loginToUid( login, db ) ) );
        } catch ( final InvalidMemberException e ) {
            sendError( resp, 400, "invalidValue", e.getMessage() );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found after create: " + displayName );
        } catch ( final WikiException e ) {
            LOG.warn( "SCIM create group={} failed: {}", displayName, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to create group: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM create group={} unexpected error: {}", displayName, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error creating group" );
        }
    }

    private void handleGetByName( final String name, final HttpServletRequest req,
                                   final HttpServletResponse resp ) throws IOException {
        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        try {
            final Group group = gm.getGroup( name );
            sendScim( resp, ScimGroupMapper.toScim( group, usersBaseUrl( req ), groupsBaseUrl( req ),
                    login -> loginToUid( login, db ) ) );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found: " + name );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM GET group name={} error: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error retrieving group" );
        }
    }

    private void handleList( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        final String filter = req.getParameter( "filter" );
        final int startIndex = parseIntParam( req, "startIndex", 1 );
        final int count = parseIntParam( req, "count", 100 );
        final String usersBase = usersBaseUrl( req );
        final String groupsBase = groupsBaseUrl( req );

        try {
            final List<Group> matched;
            if ( filter != null && !filter.isBlank() ) {
                final var eq = ScimFilterParser.parse( filter );
                if ( eq.isEmpty() ) {
                    matched = listAllGroups( gm );
                } else {
                    final ScimFilterParser.Eq eq0 = eq.get();
                    if ( "displayName".equals( eq0.attribute() ) ) {
                        List<Group> byName = new ArrayList<>();
                        try {
                            byName.add( gm.getGroup( eq0.value() ) );
                        } catch ( final NoSuchPrincipalException e ) {
                            // no match — empty result
                        }
                        matched = byName;
                    } else {
                        sendError( resp, 400, "invalidFilter",
                                "Filtering on '" + eq0.attribute() + "' is not supported for Groups" );
                        return;
                    }
                }
            } else {
                matched = listAllGroups( gm );
            }

            // Pagination (SCIM startIndex is 1-based)
            final int total = matched.size();
            final int from = Math.max( 0, startIndex - 1 );
            final List<Group> page = matched.subList(
                    Math.min( from, total ),
                    Math.min( from + count, total ) );

            final JsonArray resources = new JsonArray();
            for ( final Group g : page ) {
                resources.add( ScimGroupMapper.toScim( g, usersBase, groupsBase,
                        login -> loginToUid( login, db ) ) );
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
            LOG.warn( "SCIM list groups error: {}", e.getMessage(), e );
            sendError( resp, 500, null, "Internal error listing groups" );
        }
    }

    private void handleReplace( final String name, final HttpServletRequest req,
                                 final HttpServletResponse resp ) throws IOException {
        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        final List<String> memberUids;
        try {
            memberUids = ScimGroupMapper.readMemberUids( body );
        } catch ( final ScimGroupMapper.NestedGroupUnsupportedException e ) {
            sendError( resp, 400, "invalidValue", e.getMessage() );
            return;
        }

        try {
            saveGroupWithMembers( name, memberUids, gm, db );
            auditRecord( "scim.group.update", name );

            final Group saved = gm.getGroup( name );
            sendScim( resp, ScimGroupMapper.toScim( saved, usersBaseUrl( req ), groupsBaseUrl( req ),
                    login -> loginToUid( login, db ) ) );
        } catch ( final InvalidMemberException e ) {
            sendError( resp, 400, "invalidValue", e.getMessage() );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found: " + name );
        } catch ( final WikiException e ) {
            LOG.warn( "SCIM PUT group={} failed: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to update group: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM PUT group={} unexpected error: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error updating group" );
        }
    }

    private void handlePatch( final String name, final HttpServletRequest req,
                               final HttpServletResponse resp ) throws IOException {
        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }
        final UserDatabase db = getUserDatabase();
        if ( db == null ) { sendError( resp, 503, null, "user database unavailable" ); return; }

        // Load current members as uids
        final Group current;
        try {
            current = gm.getGroup( name );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found: " + name );
            return;
        }

        final LinkedHashSet<String> currentUids = new LinkedHashSet<>();
        for ( final Principal p : current.members() ) {
            final String uid = loginToUid( p.getName(), db );
            if ( uid != null ) currentUids.add( uid );
        }

        final JsonObject body = parseBody( req, resp );
        if ( body == null ) return;

        try {
            final LinkedHashSet<String> newUids = ScimGroupPatchApplier.apply( currentUids, body );
            saveGroupWithMembers( name, new ArrayList<>( newUids ), gm, db );
            auditRecord( "scim.group.update", name );

            final Group saved = gm.getGroup( name );
            sendScim( resp, ScimGroupMapper.toScim( saved, usersBaseUrl( req ), groupsBaseUrl( req ),
                    login -> loginToUid( login, db ) ) );
        } catch ( final ScimGroupPatchApplier.UnsupportedGroupPatchException e ) {
            sendError( resp, 400, "invalidPath", e.getMessage() );
        } catch ( final InvalidMemberException e ) {
            sendError( resp, 400, "invalidValue", e.getMessage() );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found after patch: " + name );
        } catch ( final WikiException e ) {
            LOG.warn( "SCIM PATCH group={} failed: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to patch group: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM PATCH group={} unexpected error: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error patching group" );
        }
    }

    private void handleDelete( final String name, final HttpServletResponse resp )
            throws IOException {
        final GroupManager gm = getGroupManager();
        if ( gm == null ) { sendError( resp, 503, null, "group manager unavailable" ); return; }

        try {
            gm.removeGroup( name );
            auditRecord( "scim.group.delete", name );
            resp.setStatus( 204 );
        } catch ( final NoSuchPrincipalException e ) {
            sendError( resp, 404, null, "Group not found: " + name );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "SCIM DELETE group={} security error: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Failed to delete group: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM DELETE group={} unexpected error: {}", name, e.getMessage(), e );
            sendError( resp, 500, null, "Internal error deleting group" );
        }
    }

    // -------------------------------------------------------------------------
    // Core helper: save group with resolved members (uid → loginName)
    // -------------------------------------------------------------------------

    /**
     * Maps each uid to a loginName, builds a newline-separated member line,
     * then calls {@code parseGroup} + {@code setGroup}. Any unresolved uid
     * causes a 400-mapped {@link InvalidMemberException} — no partial apply.
     */
    private void saveGroupWithMembers( final String name, final List<String> memberUids,
                                        final GroupManager gm, final UserDatabase db )
            throws InvalidMemberException, WikiException {
        final List<String> loginNames = new ArrayList<>( memberUids.size() );
        for ( final String uid : memberUids ) {
            try {
                loginNames.add( db.findByUid( uid ).getLoginName() );
            } catch ( final NoSuchPrincipalException e ) {
                throw new InvalidMemberException( "No user found for member uid '" + uid + "'" );
            }
        }

        final String memberLine = String.join( "\n", loginNames );
        final Session sysSession = WikiSession.getWikiSession( engine, null );
        final Group group = gm.parseGroup( name, memberLine, true );
        gm.setGroup( sysSession, group );
    }

    /** Signals that a member uid could not be resolved — maps to HTTP 400 invalidValue. */
    private static final class InvalidMemberException extends Exception {
        InvalidMemberException( final String m ) { super( m ); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Resolves a loginName to its uid; returns {@code null} if not found (mapper skips nulls). */
    private static String loginToUid( final String login, final UserDatabase db ) {
        try {
            return db.findByLoginName( login ).getUid();
        } catch ( final NoSuchPrincipalException e ) {
            return null;
        }
    }

    /** Lists all groups via {@code getRoles()} — exactly as {@code AdminGroupResource.handleListGroups} does. */
    private static List<Group> listAllGroups( final GroupManager gm ) {
        final Principal[] principals = gm.getRoles();
        final List<Group> groups = new ArrayList<>( principals.length );
        for ( final Principal p : principals ) {
            try {
                groups.add( gm.getGroup( p.getName() ) );
            } catch ( final NoSuchPrincipalException e ) {
                LOG.warn( "ScimGroupResource: could not load group '{}': {}", p.getName(), e.getMessage() );
            }
        }
        return groups;
    }

    /** Extracts the group name segment from path-info, e.g. {@code /Engineering} → {@code Engineering}. */
    private static String extractName( final HttpServletRequest req ) {
        final String pi = req.getPathInfo();
        if ( pi == null || pi.equals( "/" ) || pi.isBlank() ) return null;
        final String trimmed = pi.startsWith( "/" ) ? pi.substring( 1 ) : pi;
        return trimmed.isBlank() ? null : trimmed;
    }

    /** Derives the Users base URL, e.g. {@code https://host/scim/v2/Users}. */
    private static String usersBaseUrl( final HttpServletRequest req ) {
        final String url = req.getRequestURL().toString();
        final int idx = url.indexOf( "/Groups" );
        if ( idx >= 0 ) {
            return url.substring( 0, idx ) + "/Users";
        }
        return url.replaceAll( "/Groups.*$", "/Users" );
    }

    /** Derives the Groups base URL, e.g. {@code https://host/scim/v2/Groups}. */
    private static String groupsBaseUrl( final HttpServletRequest req ) {
        final String url = req.getRequestURL().toString();
        final int idx = url.indexOf( "/Groups" );
        return idx >= 0 ? url.substring( 0, idx + "/Groups".length() ) : url;
    }

    private static int parseIntParam( final HttpServletRequest req, final String name,
                                      final int defaultVal ) {
        final String v = req.getParameter( name );
        if ( v == null ) return defaultVal;
        try {
            final int i = Integer.parseInt( v.trim() );
            return i > 0 ? i : defaultVal;
        } catch ( final NumberFormatException e ) {
            return defaultVal;
        }
    }

    private static JsonObject parseBody( final HttpServletRequest req,
                                          final HttpServletResponse resp ) throws IOException {
        try ( final Reader r = req.getReader() ) {
            return JsonParser.parseReader( r ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( resp, 400, "invalidSyntax", "Could not parse JSON body: " + e.getMessage() );
            return null;
        }
    }

    private GroupManager getGroupManager() {
        if ( engine == null ) return null;
        try {
            if ( engine instanceof WikiEngine we ) {
                return we.getManager( GroupManager.class );
            }
            LOG.warn( "ScimGroupResource: engine is not a WikiEngine; cannot obtain GroupManager" );
            return null;
        } catch ( final Exception e ) {
            LOG.warn( "ScimGroupResource: could not obtain GroupManager: {}", e.getMessage(), e );
            return null;
        }
    }

    private UserDatabase getUserDatabase() {
        if ( engine == null ) return null;
        try {
            if ( engine instanceof WikiEngine we ) {
                return we.getManager( UserManager.class ).getUserDatabase();
            }
            LOG.warn( "ScimGroupResource: engine is not a WikiEngine; cannot obtain UserDatabase" );
            return null;
        } catch ( final Exception e ) {
            LOG.warn( "ScimGroupResource: could not obtain UserDatabase: {}", e.getMessage(), e );
            return null;
        }
    }

    private AuditService getAuditService() {
        return ( engine instanceof WikiEngine we ) ? we.getAuditService() : null;
    }

    private void auditRecord( final String eventType, final String name ) {
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
                    .targetType( "group" )
                    .targetId( name )
                    .targetLabel( name )
                    .build() );
        } catch ( final Exception e ) {
            LOG.warn( "SCIM audit record failed for event={} group={}: {}", eventType, name, e.getMessage(), e );
        }
    }

    private static void sendScim( final HttpServletResponse resp, final JsonObject body )
            throws IOException {
        resp.setContentType( CONTENT_TYPE );
        resp.getWriter().write( GSON.toJson( body ) );
    }

    private static void sendError( final HttpServletResponse resp, final int status,
                                    final String scimType, final String detail ) throws IOException {
        resp.setStatus( status );
        resp.setContentType( CONTENT_TYPE );
        resp.getWriter().write( GSON.toJson( ScimError.body( status, scimType, detail ) ) );
    }
}

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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.validate.PasswordValidator;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for admin user management.
 * <p>
 * Mapped to {@code /admin/users/*}. All requests are pre-authorized by
 * {@link AdminAuthFilter}. Handles:
 * <ul>
 *   <li>{@code GET /admin/users} — list all users</li>
 *   <li>{@code GET /admin/users/{loginName}} — get user profile</li>
 *   <li>{@code POST /admin/users} — create user</li>
 *   <li>{@code PUT /admin/users/{loginName}} — update user</li>
 *   <li>{@code DELETE /admin/users/{loginName}} — delete user</li>
 *   <li>{@code POST /admin/users/{loginName}/lock} — lock user account</li>
 *   <li>{@code POST /admin/users/{loginName}/unlock} — unlock user account</li>
 * </ul>
 */
public class AdminUserResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminUserResource.class );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private UserDatabase getUserDatabase() {
        return getSubsystems().auth().users().getUserDatabase();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );

        if ( pathParam == null ) {
            handleListUsers( response );
        } else {
            handleGetUser( response, pathParam );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        // Dispatch bulk-action BEFORE any other path handling so it doesn't
        // collide with the {loginName}/lock|unlock subpaths.
        final String pathInfo = request.getPathInfo();
        if ( "/bulk-action".equals( pathInfo ) ) {
            doBulkAction( request, response );
            return;
        }

        final String pathParam = extractPathParam( request );

        if ( pathParam == null ) {
            handleCreateUser( request, response );
        } else if ( pathParam.endsWith( "/lock" ) ) {
            final String loginName = pathParam.substring( 0, pathParam.length() - "/lock".length() );
            handleLockUser( request, response, loginName );
        } else if ( pathParam.endsWith( "/unlock" ) ) {
            final String loginName = pathParam.substring( 0, pathParam.length() - "/unlock".length() );
            handleUnlockUser( response, loginName );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint" );
        }
    }

    /**
     * Handles {@code POST /admin/users/bulk-action}.
     *
     * <p>Request body:
     * {@code { "action": "lock"|"unlock"|"delete"|"add-to-group",
     *           "ids": ["bob","alice"],
     *           "group": "editors"   // only for add-to-group
     * }}
     *
     * <p>Loops over {@code ids} without aborting on first failure. Returns the
     * standard bulk-result envelope:
     * {@code { "succeeded": [...], "failed": [...], "status": "completed",
     * "message": "N of M users locked" }}.
     *
     * <p>For {@code add-to-group}: {@code group} must be non-empty and the named
     * group must already exist — if not, the whole call returns 400 (not a per-id
     * failure) because this is not a per-id condition.
     *
     * <p>For {@code delete}: the actor's own login is rejected as a per-id
     * failure (operators cannot bulk-delete themselves).
     *
     * <p>Emits one audit log entry per call.
     */
    private void doBulkAction( final HttpServletRequest request,
            final HttpServletResponse response )
            throws ServletException, IOException {

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String action = getJsonString( body, "action" );
        if ( action == null || action.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "action is required (supported: lock, unlock, delete, add-to-group)" );
            return;
        }
        if ( !"lock".equals( action ) && !"unlock".equals( action )
                && !"delete".equals( action ) && !"add-to-group".equals( action ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Unsupported action '" + action + "' — supported: lock, unlock, delete, add-to-group" );
            return;
        }

        final JsonElement idsEl = body.get( "ids" );
        if ( idsEl == null || !idsEl.isJsonArray() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "ids is required and must be a JSON array" );
            return;
        }
        final JsonArray idsArr = idsEl.getAsJsonArray();
        if ( idsArr.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "ids must not be empty" );
            return;
        }

        // --- add-to-group pre-validation: resolve group once, bail with 400 if absent ---
        Group targetGroup = null;
        Session session = null;
        if ( "add-to-group".equals( action ) ) {
            final String groupName = getJsonString( body, "group" );
            if ( groupName == null || groupName.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "group is required for action 'add-to-group'" );
                return;
            }
            try {
                targetGroup = getSubsystems().auth().groups().getGroup( groupName );
                session = Wiki.session().find( getEngine(), request );
            } catch ( final NoSuchPrincipalException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Group not found: " + groupName );
                return;
            } catch ( final Exception e ) {
                LOG.warn( "bulk add-to-group: could not resolve group actor={}: {}",
                        currentLogin( request ), e.getMessage(), e );
                sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Failed to resolve group" );
                return;
            }
        }

        final String actor = currentLogin( request );
        final List< String > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final JsonElement idEl : idsArr ) {
            final String loginName = idEl.isJsonPrimitive() ? idEl.getAsString() : null;
            if ( loginName == null || loginName.isBlank() ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idEl.toString() );
                f.put( "error", "id must be a non-blank string" );
                failed.add( f );
                continue;
            }

            final Optional< String > err;
            switch ( action ) {
                case "lock"   -> err = tryLockUser( loginName );
                case "unlock" -> err = tryUnlockUser( loginName );
                case "delete" -> err = tryDeleteUser( loginName, actor );
                case "add-to-group" -> err = tryAddToGroup( loginName, targetGroup, session, actor );
                default -> err = Optional.of( "Unknown action" );  // unreachable
            }

            if ( err.isEmpty() ) {
                succeeded.add( loginName );
            } else {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", loginName );
                f.put( "error", err.get() );
                failed.add( f );
            }
        }

        final String suffix = "add-to-group".equals( action )
                ? " group=" + getJsonString( body, "group" )
                : "";
        LOG.info( "bulk action={} resource=users actor={} attempted={} succeeded={} failed={}{}",
                action, actor, idsArr.size(), succeeded.size(), failed.size(), suffix );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "succeeded", succeeded );
        result.put( "failed", failed );
        result.put( "status", "completed" );
        result.put( "message",
                succeeded.size() + " of " + idsArr.size() + " users " + actionPastTense( action ) );
        sendJson( response, result );
    }

    private static String actionPastTense( final String action ) {
        return switch ( action ) {
            case "lock"         -> "locked";
            case "unlock"       -> "unlocked";
            case "delete"       -> "deleted";
            case "add-to-group" -> "added to group";
            default             -> action + "d";
        };
    }

    // ---- per-id helpers shared with single-item path ----

    /**
     * Locks {@code loginName} indefinitely. Returns empty on success, an error
     * message on failure.
     */
    Optional< String > tryLockUser( final String loginName ) {
        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            profile.setLockExpiry( new Date( Long.MAX_VALUE ) );
            db.save( profile );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "bulk-lock: failed to lock user={}: {}", loginName, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to lock user" );
        }
    }

    /**
     * Unlocks {@code loginName}. Returns empty on success, error message on
     * failure.
     */
    Optional< String > tryUnlockUser( final String loginName ) {
        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            profile.setLockExpiry( null );
            db.save( profile );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "bulk-unlock: failed to unlock user={}: {}", loginName, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to unlock user" );
        }
    }

    /**
     * Deletes {@code loginName}. Rejects the actor's own login as a per-id
     * failure. Returns empty on success, error message on failure.
     */
    Optional< String > tryDeleteUser( final String loginName, final String actor ) {
        if ( loginName.equals( actor ) ) {
            return Optional.of( "Cannot delete yourself" );
        }
        try {
            getUserDatabase().deleteByLoginName( loginName );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "bulk-delete: failed to delete user={}: {}", loginName, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "User not found" );
        }
    }

    /**
     * Adds {@code loginName} as a member of {@code group}. Returns empty on
     * success, error message on failure. Verifies the login exists before
     * adding.
     */
    Optional< String > tryAddToGroup( final String loginName, final Group group,
            final Session session, final String actor ) {
        try {
            // Verify the user exists before adding to group
            getUserDatabase().findByLoginName( loginName );
            // Add using a login-name principal so the GroupDatabase persists it
            // in the standard way that matches how members are resolved.
            final Principal userPrincipal = new com.wikantik.auth.WikiPrincipal(
                    loginName, com.wikantik.auth.WikiPrincipal.LOGIN_NAME );
            group.add( userPrincipal );
            getGroupManager().setGroup( session, group );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "bulk-add-to-group: failed to add user={} actor={}: {}",
                    loginName, actor, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to add user to group" );
        }
    }

    private static String currentLogin( final HttpServletRequest request ) {
        final Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String loginName = extractPathParam( request );
        if ( loginName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Login name required in path" );
            return;
        }
        handleUpdateUser( request, response, loginName );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String loginName = extractPathParam( request );
        if ( loginName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Login name required in path" );
            return;
        }
        handleDeleteUser( response, loginName );
    }

    private void handleListUsers( final HttpServletResponse response ) throws IOException {
        try {
            final UserDatabase db = getUserDatabase();
            final Principal[] wikiNames = db.getWikiNames();
            final List< Map< String, Object > > users = new ArrayList<>();

            for ( final Principal wikiName : wikiNames ) {
                try {
                    final UserProfile profile = db.findByWikiName( wikiName.getName() );
                    users.add( profileToMap( profile ) );
                } catch ( final Exception e ) {
                    LOG.warn( "Could not load profile for wiki name {}: {}", wikiName.getName(), e.getMessage() );
                }
            }

            sendJson( response, Map.of( "users", users ) );
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Failed to list users", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to list users" );
        }
    }

    private void handleGetUser( final HttpServletResponse response, final String loginName ) throws IOException {
        try {
            final UserProfile profile = getUserDatabase().findByLoginName( loginName );
            sendJson( response, profileToMap( profile ) );
        } catch ( final Exception e ) {
            sendNotFound( response, "User not found: " + loginName );
        }
    }

    private void handleCreateUser( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String loginName = getJsonString( body, "loginName" );
        final String fullName = getJsonString( body, "fullName" );
        final String email = getJsonString( body, "email" );
        final String bio = getJsonString( body, "bio" );
        final String password = getJsonString( body, "password" );

        if ( loginName == null || loginName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "loginName is required" );
            return;
        }
        if ( password == null || password.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "password is required" );
            return;
        }
        final List<String> passwordErrors = PasswordValidator.validate( password, getSubsystems().core().properties().asProperties() );
        if ( !passwordErrors.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    passwordErrors.stream().map( PasswordValidator::describeError ).collect( java.util.stream.Collectors.joining( "; " ) ) );
            return;
        }

        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.newProfile();
            profile.setLoginName( loginName );
            if ( fullName != null ) profile.setFullname( fullName );
            if ( email != null ) profile.setEmail( email );
            if ( bio != null ) {
                if ( bio.length() > 1000 ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Bio must be 1000 characters or fewer" );
                    return;
                }
                profile.setBio( bio );
            }
            profile.setPassword( password );
            db.save( profile );

            LOG.info( "Created user: {}", loginName );
            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Failed to create user {}: {}", loginName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, "Failed to create user" );
        } catch ( final Exception e ) {
            LOG.error( "Failed to create user {}", loginName, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create user" );
        }
    }

    private void handleUpdateUser( final HttpServletRequest request, final HttpServletResponse response,
                                    final String loginName ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );

            final String fullName = getJsonString( body, "fullName" );
            final String email = getJsonString( body, "email" );
            final String password = getJsonString( body, "password" );
            final String bio = getJsonString( body, "bio" );

            if ( fullName != null ) profile.setFullname( fullName );
            if ( email != null ) profile.setEmail( email );
            if ( bio != null ) {
                if ( bio.length() > 1000 ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Bio must be 1000 characters or fewer" );
                    return;
                }
                profile.setBio( bio );
            }
            if ( password != null && !password.isBlank() ) {
                final List<String> passwordErrors = PasswordValidator.validate( password, getSubsystems().core().properties().asProperties() );
                if ( !passwordErrors.isEmpty() ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            passwordErrors.stream().map( PasswordValidator::describeError ).collect( java.util.stream.Collectors.joining( "; " ) ) );
                    return;
                }
                profile.setPassword( password );
            }

            db.save( profile );
            LOG.info( "Updated user: {}", loginName );
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to update user {}: {}", loginName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "User not found: " + loginName );
        }
    }

    private void handleDeleteUser( final HttpServletResponse response, final String loginName ) throws IOException {
        try {
            getUserDatabase().deleteByLoginName( loginName );
            LOG.info( "Deleted user: {}", loginName );
            sendJson( response, Map.of( "success", true ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to delete user {}: {}", loginName, e.getMessage() );
            sendNotFound( response, "User not found: " + loginName );
        }
    }

    private void handleLockUser( final HttpServletRequest request, final HttpServletResponse response,
                                  final String loginName ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );

            final String expiryStr = getJsonString( body, "expiry" );
            final Date expiry;
            if ( expiryStr != null && !expiryStr.isBlank() ) {
                expiry = new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT ).parse( expiryStr );
            } else {
                // Lock indefinitely — set expiry far in the future
                expiry = new Date( Long.MAX_VALUE );
            }

            profile.setLockExpiry( expiry );
            db.save( profile );
            LOG.info( "Locked user: {} until {}", loginName, expiry );
            sendJson( response, Map.of( "locked", true, "lockExpiry", formatDate( expiry ) ) );
        } catch ( final ParseException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid date format. Use yyyy-MM-dd" );
        } catch ( final Exception e ) {
            LOG.error( "Failed to lock user {}: {}", loginName, e.getMessage() );
            sendNotFound( response, "User not found: " + loginName );
        }
    }

    private void handleUnlockUser( final HttpServletResponse response, final String loginName ) throws IOException {
        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            profile.setLockExpiry( null );
            db.save( profile );
            LOG.info( "Unlocked user: {}", loginName );
            sendJson( response, Map.of( "locked", false ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to unlock user {}: {}", loginName, e.getMessage() );
            sendNotFound( response, "User not found: " + loginName );
        }
    }

    private GroupManager getGroupManager() {
        return getSubsystems().auth().groups();
    }

    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
        map.put( "bio", profile.getBio() );
        map.put( "wikiName", profile.getWikiName() );
        map.put( "created", formatDate( profile.getCreated() ) );
        map.put( "lastModified", formatDate( profile.getLastModified() ) );

        final Date lockExpiry = profile.getLockExpiry();
        final boolean locked = lockExpiry != null && lockExpiry.after( new Date() );
        map.put( "locked", locked );
        if ( locked ) {
            map.put( "lockExpiry", formatDate( lockExpiry ) );
        }
        return map;
    }

}

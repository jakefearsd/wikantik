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

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.ProfileChangeNotifier;
import com.wikantik.auth.ProfileNameRules;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.validate.PasswordValidator;
import com.wikantik.auth.user.DeletedLoginException;
import com.wikantik.auth.user.ProfileConflictException;
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
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    /**
     * Sentinel "lock indefinitely" expiry. Must stay within the range every supported
     * UserDatabase backend can persist: the JDBC backend writes this to a PostgreSQL
     * {@code TIMESTAMP} column whose maximum is year 294276, so {@code new Date(Long.MAX_VALUE)}
     * (year ~292278994) overflows and the lock save fails. Year 9999 is unambiguously
     * "indefinite" for account-locking purposes and persists cleanly everywhere.
     */
    static final Date INDEFINITE_LOCK_EXPIRY =
            new GregorianCalendar( 9999, Calendar.DECEMBER, 31, 23, 59, 59 ).getTime();

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    protected UserDatabase getUserDatabase() {
        return getSubsystems().auth().users().getUserDatabase();
    }

    protected com.wikantik.comments.PageOwnerService pageOwners() {
        final var persistence = getSubsystems().persistence();
        return persistence == null ? null : persistence.pageOwners();
    }

    /** Built from this class's overridable accessors so tests can substitute any collaborator. */
    @Override
    protected com.wikantik.auth.UserDeletionService userDeletionService() {
        final var persistence = getSubsystems().persistence();
        return new com.wikantik.auth.UserDeletionService(
                getEngine(), getUserDatabase(), getGroupManager(),
                com.wikantik.auth.apikeys.ApiKeyServiceHolder.get( getEngine().getWikiProperties() ),
                pageOwners(), persistence == null ? null : persistence.trustedAuthors() );
    }

    /**
     * What a route runs, against the servlet that dispatched it (passed in rather than captured, so a table
     * copied along with a spied servlet still reaches the spy): the request, the response, and the path
     * parameter (the path info without its leading slash).
     */
    @FunctionalInterface
    private interface Route {
        void run( AdminUserResource self, HttpServletRequest request, HttpServletResponse response,
                  String pathParam ) throws IOException;
    }

    /**
     * One table per HTTP method, rebuilt lazily because the handlers close over this servlet and servlets are
     * serializable (see {@link PathRouteTable}). The key is the raw path parameter, with {@code ""} standing for
     * "no path"; matching the raw string rather than segments keeps a trailing slash or an embedded slash exactly
     * as hand-routed before. {@code POST bulk-action} is registered ahead of the {@code {login}/lock} suffix routes.
     */
    private transient volatile Map< String, PathRouteTable< Route > > routes;

    private Map< String, PathRouteTable< Route > > routes() {
        Map< String, PathRouteTable< Route > > r = routes;
        if ( r == null ) {
            final Route loginRequired = ( self, req, resp, pathParam ) ->
                    self.sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "Login name required in path" );
            r = Map.of(
                "GET", PathRouteTable.< Route >builder()
                        .exact( NO_PATH, ( self, req, resp, p ) -> self.handleListUsers( resp ) )
                        .when( p -> true, ( self, req, resp, p ) -> self.handleGetUser( resp, p ) )
                        .build(),
                "POST", PathRouteTable.< Route >builder()
                        .exact( "bulk-action", ( self, req, resp, p ) -> self.doBulkAction( req, resp ) )
                        .exact( NO_PATH, ( self, req, resp, p ) -> self.handleCreateUser( req, resp ) )
                        .when( p -> p.endsWith( LOCK ),
                                ( self, req, resp, p ) -> self.handleLockUser( req, resp, withoutSuffix( p, LOCK ) ) )
                        .when( p -> p.endsWith( UNLOCK ),
                                ( self, req, resp, p ) -> self.handleUnlockUser( req, resp, withoutSuffix( p, UNLOCK ) ) )
                        .build(),
                "PUT", PathRouteTable.< Route >builder()
                        .exact( NO_PATH, loginRequired )
                        .when( p -> true, ( self, req, resp, p ) -> self.handleUpdateUser( req, resp, p ) )
                        .build(),
                "DELETE", PathRouteTable.< Route >builder()
                        .exact( NO_PATH, loginRequired )
                        .when( p -> true, ( self, req, resp, p ) -> self.handleDeleteUser( req, resp, p ) )
                        .build() );
            routes = r;
        }
        return r;
    }

    private static final String NO_PATH = "";
    private static final String LOCK = "/lock";
    private static final String UNLOCK = "/unlock";

    private static String withoutSuffix( final String pathParam, final String suffix ) {
        return pathParam.substring( 0, pathParam.length() - suffix.length() );
    }

    private void dispatch( final String method, final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String pathParam = extractPathParam( request );
        final String key = pathParam == null ? NO_PATH : pathParam;
        final Optional< Route > route = routes().get( method ).match( key );
        if ( route.isPresent() ) {
            route.get().run( this, request, response, key );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint" );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( "GET", request, response );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( "POST", request, response );
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( "PUT", request, response );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( "DELETE", request, response );
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
            throws IOException {
        final String actor = currentLogin( request );
        final BulkTarget target = new BulkTarget();
        BulkActions.handle( request, response, "users", List.of(
                userAction( "lock", "users locked", id -> tryLockUser( id, actor ) ),
                userAction( "unlock", "users unlocked", id -> tryUnlockUser( id, actor ) ),
                userAction( "delete", "users deleted", id -> tryDeleteUser( id, actor, target.session ) ),
                new BulkActions.Action( "add-to-group", "users added to group",
                        perUser( id -> tryAddToGroup( id, target.group, target.session, actor ) ),
                        "id must be a non-blank string",
                        body -> " group=" + getJsonString( body, "group" ) ) ),
                ( action, body, resp ) -> prepareBulk( action, request, body, resp, target ) );
    }

    private static BulkActions.Action userAction( final String name, final String pastTense,
            final Function< String, Optional< String > > perUser ) {
        return new BulkActions.Action( name, pastTense, perUser( perUser ) );
    }

    /** Adapts a per-login attempt (empty on success, a message on failure) to the template's per-id handler. */
    private static BulkActions.IdHandler perUser( final Function< String, Optional< String > > attempt ) {
        return ( id, result ) -> {
            final Optional< String > err = attempt.apply( id );
            if ( err.isEmpty() ) {
                result.succeed( id );
            } else {
                result.fail( id, err.get() );
            }
        };
    }

    /** What the pre-step resolved for one bulk request: the add-to-group target and the acting session. */
    private static final class BulkTarget {
        private Group group;
        private Session session;
    }

    /**
     * The pre-step of the bulk template: {@code add-to-group} resolves its group once (a 400 when absent) and
     * {@code delete} looks up the acting session. Returns {@code false} when it has sent the error response.
     */
    private boolean prepareBulk( final String action, final HttpServletRequest request, final JsonObject body,
            final HttpServletResponse response, final BulkTarget target ) throws IOException {
        if ( "add-to-group".equals( action ) ) {
            return resolveGroupForBulkAdd( request, body, response, target );
        }
        if ( "delete".equals( action ) ) {
            target.session = Wiki.session().find( getEngine(), request );
        }
        return true;
    }

    /**
     * Resolves the target group (and acting session) for a bulk {@code add-to-group} request.
     * On failure, sends the appropriate error response itself and returns {@code false}.
     */
    private boolean resolveGroupForBulkAdd( final HttpServletRequest request, final JsonObject body,
            final HttpServletResponse response, final BulkTarget target ) throws IOException {
        final String groupName = getJsonString( body, "group" );
        if ( groupName == null || groupName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "group is required for action 'add-to-group'" );
            return false;
        }
        try {
            target.group = getSubsystems().auth().groups().getGroup( groupName );
            target.session = Wiki.session().find( getEngine(), request );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Group not found: " + groupName );
            return false;
        } catch ( final Exception e ) {
            LOG.warn( "bulk add-to-group: could not resolve group actor={}: {}",
                    currentLogin( request ), e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to resolve group" );
            return false;
        }
    }

    // ---- per-id helpers shared with single-item path ----

    /**
     * Locks {@code loginName} indefinitely via {@link com.wikantik.auth.UserLifecycleService}
     * (when an audit service is available) or falls back to the inline mechanism.
     * Returns empty on success, an error message on failure.
     */
    Optional< String > tryLockUser( final String loginName, final String actor ) {
        final AuditService audit = getEngine() instanceof com.wikantik.WikiEngine we
                ? we.getAuditService() : null;
        if ( audit != null ) {
            try {
                final com.wikantik.auth.UserLifecycleService lifecycle =
                        new com.wikantik.auth.UserLifecycleService( getUserDatabase(), audit );
                lifecycle.deactivate( loginName, actor, "admin-ui" );
                return Optional.empty();
            } catch ( final WikiSecurityException e ) {
                LOG.warn( "tryLockUser: failed to deactivate user={}: {}", loginName, e.getMessage(), e );
                return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to lock user" );
            } catch ( final Exception e ) {
                LOG.warn( "tryLockUser: failed to deactivate user={}: {}", loginName, e.getMessage(), e );
                return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to lock user" );
            }
        }
        // Fallback: no audit subsystem — inline mechanism
        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            profile.setLockExpiry( INDEFINITE_LOCK_EXPIRY );
            db.save( profile );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryLockUser: failed to lock user={}: {}", loginName, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to lock user" );
        }
    }

    /**
     * Unlocks {@code loginName} via {@link com.wikantik.auth.UserLifecycleService}
     * (when an audit service is available) or falls back to the inline mechanism.
     * Returns empty on success, error message on failure.
     */
    Optional< String > tryUnlockUser( final String loginName, final String actor ) {
        final AuditService audit = getEngine() instanceof com.wikantik.WikiEngine we
                ? we.getAuditService() : null;
        if ( audit != null ) {
            try {
                final com.wikantik.auth.UserLifecycleService lifecycle =
                        new com.wikantik.auth.UserLifecycleService( getUserDatabase(), audit );
                lifecycle.reactivate( loginName, actor, "admin-ui" );
                return Optional.empty();
            } catch ( final WikiSecurityException e ) {
                LOG.warn( "tryUnlockUser: failed to reactivate user={}: {}", loginName, e.getMessage(), e );
                return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to unlock user" );
            } catch ( final Exception e ) {
                LOG.warn( "tryUnlockUser: failed to reactivate user={}: {}", loginName, e.getMessage(), e );
                return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to unlock user" );
            }
        }
        // Fallback: no audit subsystem — inline mechanism
        try {
            final UserDatabase db = getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            profile.setLockExpiry( null );
            db.save( profile );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryUnlockUser: failed to unlock user={}: {}", loginName, e.getMessage(), e );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Failed to unlock user" );
        }
    }

    /**
     * Deletes {@code loginName}. Rejects the actor's own login as a per-id
     * failure. Returns empty on success, error message on failure.
     */
    Optional< String > tryDeleteUser( final String loginName, final String actor, final Session session ) {
        if ( loginName.equals( actor ) ) {
            return Optional.of( "Cannot delete yourself" );
        }
        try {
            userDeletionService().delete( session, loginName );
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

    private void handleListUsers( final HttpServletResponse response ) throws IOException {
        try {
            final UserDatabase db = getUserDatabase();
            final List< Map< String, Object > > users = new ArrayList<>();
            for ( final UserProfile profile : db.findAllProfiles() ) {
                try {
                    users.add( profileToMap( profile ) );
                } catch ( final Exception e ) {
                    String loginInfo = "unknown";
                    try {
                        loginInfo = profile != null ? profile.getLoginName() : "unknown";
                    } catch ( final Exception ex ) {
                        LOG.warn( "Could not resolve login name of unmappable profile: {}", ex.getMessage() );
                    }
                    LOG.warn( "Failed to map profile for login={}: {}", loginInfo, e.getMessage(), e );
                    // Continue with next profile; skip this one
                }
            }
            users.sort( Comparator.comparing( m -> String.valueOf( m.get( "loginName" ) ) ) );
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
            // The display name is required on create: a missing/blank one is a 400, a taken one a 409 (a null name used to
            // count as "unchanged" and slipped through). The rules also cover the wiki name derived from it.
            final java.util.Optional< ProfileNameRules.NameViolation > nameViolation =
                    ProfileNameRules.displayNameViolation( getEngine(), db, loginName, fullName );
            if ( nameViolation.isPresent() ) {
                RestJson.sendNameViolation( response, nameViolation.get() );
                return;
            }
            final UserProfile profile = db.newProfile();
            profile.setLoginName( loginName );
            ProfileNameRules.applyFullName( getEngine(), db, profile, fullName );
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
            profile.setPasswordMustChange( true );
            db.save( profile );
            ProfileChangeNotifier.profileSaved( getEngine(), loginName );

            LOG.info( "Created user: {}", loginName );
            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final ProfileConflictException e ) {
            LOG.warn( "Creation of user {} conflicts with an existing account", loginName, e );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, ProfileConflictException.MESSAGE );
        } catch ( final DeletedLoginException e ) {
            LOG.warn( "Creation of user {} refused: the login belonged to a deleted account", loginName );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, DeletedLoginException.MESSAGE );
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

            if ( fullName != null ) {
                final java.util.Optional< ProfileNameRules.NameViolation > nameViolation =
                        ProfileNameRules.fullNameChangeViolation( getEngine(), db, profile, fullName );
                if ( nameViolation.isPresent() ) {
                    RestJson.sendNameViolation( response, nameViolation.get() );
                    return;
                }
                ProfileNameRules.applyFullName( getEngine(), db, profile, fullName );
            }
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
                profile.setPasswordMustChange( true );
            }

            db.save( profile );
            ProfileChangeNotifier.profileSaved( getEngine(), loginName );
            LOG.info( "Updated user: {}", loginName );
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final ProfileConflictException e ) {
            LOG.warn( "Update of user {} conflicts with an existing account", loginName, e );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, ProfileConflictException.MESSAGE );
        } catch ( final Exception e ) {
            LOG.error( "Failed to update user {}: {}", loginName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "User not found: " + loginName );
        }
    }

    private void handleDeleteUser( final HttpServletRequest request, final HttpServletResponse response,
                                   final String loginName ) throws IOException {
        try {
            userDeletionService().delete( Wiki.session().find( getEngine(), request ), loginName );
            LOG.info( "Deleted user: {}", loginName );
            sendJson( response, Map.of( "success", true ) );
        } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
            LOG.error( "Failed to delete user {}: {}", loginName, e.getMessage() );
            sendNotFound( response, "User not found: " + loginName );
        } catch ( final Exception e ) {
            // A fail-closed cleanup step (API keys / groups / trusted author) refused; the account is still there.
            LOG.error( "Failed to delete user {}: {}", loginName, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not delete user " + loginName + "; the account was left in place — retry" );
        }
    }

    private void handleLockUser( final HttpServletRequest request, final HttpServletResponse response,
                                  final String loginName ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String actor = currentLogin( request );
        final String expiryStr = getJsonString( body, "expiry" );

        if ( expiryStr != null && !expiryStr.isBlank() ) {
            // Timed lock with a specific expiry date — route through lifecycle service for audit.
            final Date expiry;
            try {
                expiry = new SimpleDateFormat( "yyyy-MM-dd", Locale.ROOT ).parse( expiryStr );
            } catch ( final ParseException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid date format. Use yyyy-MM-dd" );
                return;
            }
            final AuditService audit = getEngine() instanceof com.wikantik.WikiEngine we
                    ? we.getAuditService() : null;
            try {
                if ( audit != null ) {
                    final com.wikantik.auth.UserLifecycleService lifecycle =
                            new com.wikantik.auth.UserLifecycleService( getUserDatabase(), audit );
                    lifecycle.deactivate( loginName, expiry, actor, "admin-ui" );
                } else {
                    // Fallback: no audit subsystem — inline mechanism
                    final UserDatabase db = getUserDatabase();
                    final UserProfile profile = db.findByLoginName( loginName );
                    profile.setLockExpiry( expiry );
                    db.save( profile );
                }
                LOG.info( "Locked user: {} until {}", loginName, expiry );
                sendJson( response, Map.of( "locked", true, "lockExpiry", formatDate( expiry ) ) );
            } catch ( final Exception e ) {
                LOG.error( "Failed to lock user {}: {}", loginName, e.getMessage() );
                sendNotFound( response, "User not found: " + loginName );
            }
        } else {
            // Indefinite lock — delegate to tryLockUser so the lifecycle service handles audit.
            final Optional< String > err = tryLockUser( loginName, actor );
            if ( err.isEmpty() ) {
                LOG.info( "Locked user: {} indefinitely", loginName );
                sendJson( response, Map.of( "locked", true,
                        "lockExpiry", formatDate( INDEFINITE_LOCK_EXPIRY ) ) );
            } else {
                LOG.error( "Failed to lock user {}: {}", loginName, err.get() );
                sendNotFound( response, "User not found: " + loginName );
            }
        }
    }

    private void handleUnlockUser( final HttpServletRequest request, final HttpServletResponse response,
                                    final String loginName ) throws IOException {
        final String actor = currentLogin( request );
        final Optional< String > err = tryUnlockUser( loginName, actor );
        if ( err.isEmpty() ) {
            LOG.info( "Unlocked user: {}", loginName );
            sendJson( response, Map.of( "locked", false ) );
        } else {
            LOG.error( "Failed to unlock user {}: {}", loginName, err.get() );
            sendNotFound( response, "User not found: " + loginName );
        }
    }

    private GroupManager getGroupManager() {
        return getSubsystems().auth().groups();
    }

    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = UserProfileMapping.coreFields( profile, this::formatDate );
        map.put( "lastLogin", formatDate( profile.getLastLogin() ) );

        final Date lockExpiry = profile.getLockExpiry();
        final boolean locked = lockExpiry != null && lockExpiry.after( new Date() );
        map.put( "locked", locked );
        if ( locked ) {
            map.put( "lockExpiry", formatDate( lockExpiry ) );
        }
        map.put( "passwordMustChange", profile.isPasswordMustChange() );
        return map;
    }

}

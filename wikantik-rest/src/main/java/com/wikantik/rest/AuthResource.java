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
import com.google.gson.JsonParser;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.auth.validate.PasswordValidator;
import com.wikantik.util.MailUtil;
import com.wikantik.util.TextUtil;
import com.wikantik.util.TimedCounterList;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST servlet for authentication operations.
 * <p>
 * Mapped to {@code /api/auth/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/auth/user} - Returns current session/user info</li>
 *   <li>{@code GET /api/auth/profile} - Returns authenticated user's profile</li>
 *   <li>{@code POST /api/auth/login} - Authenticate with username/password</li>
 *   <li>{@code POST /api/auth/logout} - Invalidate session</li>
 *   <li>{@code POST /api/auth/reset-password} - Send password reset email</li>
 *   <li>{@code PUT /api/auth/profile} - Update authenticated user's own profile</li>
 * </ul>
 */
public class AuthResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AuthResource.class );

    /** Rate limiter for password reset requests: max 3 per email per hour. */
    private static final TimedCounterList< String > RESET_ATTEMPTS = new TimedCounterList<>();
    private static final int MAX_RESET_PER_HOUR = 3;
    private static final long ONE_HOUR_MS = 60 * 60 * 1000L;

    /** Generic response for password reset to prevent email enumeration. */
    private static final String RESET_GENERIC_MESSAGE =
            "If an account exists with that email, a new password has been sent.";

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "user".equals( action ) ) {
            handleGetUser( request, response );
        } else if ( "profile".equals( action ) ) {
            handleGetProfile( request, response );
        } else {
            sendNotFound( response, "Unknown auth endpoint: " + action );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "login".equals( action ) ) {
            handleLogin( request, response );
        } else if ( "logout".equals( action ) ) {
            handleLogout( request, response );
        } else if ( "reset-password".equals( action ) ) {
            handleResetPassword( request, response );
        } else {
            sendNotFound( response, "Unknown auth endpoint: " + action );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "profile".equals( action ) ) {
            handleUpdateProfile( request, response );
        } else {
            sendNotFound( response, "Unknown auth endpoint: " + action );
        }
    }

    /**
     * Returns current user session information.
     */
    private void handleGetUser( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "GET auth/user" );

        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "authenticated", session.isAuthenticated() );

        if ( session.isAuthenticated() ) {
            result.put( "username", session.getUserPrincipal().getName() );
            result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
        } else {
            result.put( "username", "anonymous" );
            result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
        }

        final List< String > roles = Arrays.stream( session.getRoles() )
                .map( Principal::getName )
                .toList();
        result.put( "roles", roles );

        sendJson( response, result );
    }

    /**
     * Returns the authenticated user's own profile.
     */
    private void handleGetProfile( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "GET auth/profile" );

        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );

        if ( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return;
        }

        try {
            final String loginName = session.getLoginPrincipal().getName();
            final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );
            sendJson( response, profileToMap( profile ) );
        } catch ( final NoSuchPrincipalException e ) {
            LOG.warn( "Profile not found for authenticated user: {}", session.getLoginPrincipal().getName() );
            sendNotFound( response, "Profile not found" );
        }
    }

    /**
     * Updates the authenticated user's own profile (self-service).
     */
    private void handleUpdateProfile( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "PUT auth/profile" );

        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );

        if ( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return;
        }

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        try {
            final String loginName = session.getLoginPrincipal().getName();
            final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
            final UserProfile profile = db.findByLoginName( loginName );

            // Update fullName if provided
            final String fullName = getJsonString( body, "fullName" );
            if ( fullName != null ) {
                profile.setFullname( fullName );
            }

            // Update email if provided
            final String email = getJsonString( body, "email" );
            if ( email != null ) {
                profile.setEmail( email );
            }

            // Handle password change if requested
            final String newPassword = getJsonString( body, "newPassword" );
            if ( newPassword != null && !newPassword.isBlank() ) {
                final String currentPassword = getJsonString( body, "currentPassword" );
                if ( currentPassword == null || currentPassword.isBlank() ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Current password is required to set a new password" );
                    return;
                }

                // Verify current password
                if ( !db.validatePassword( loginName, currentPassword ) ) {
                    sendError( response, HttpServletResponse.SC_FORBIDDEN, "Current password is incorrect" );
                    return;
                }

                // Validate new password strength
                final List< String > passwordErrors =
                        PasswordValidator.validate( newPassword, engine.getWikiProperties() );
                if ( !passwordErrors.isEmpty() ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            passwordErrors.stream()
                                    .map( PasswordValidator::describeError )
                                    .collect( Collectors.joining( "; " ) ) );
                    return;
                }

                profile.setPassword( newPassword );
            }

            db.save( profile );
            LOG.info( "User {} updated their own profile", loginName );

            // Return updated profile
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final NoSuchPrincipalException e ) {
            LOG.warn( "Profile not found for authenticated user: {}", session.getLoginPrincipal().getName() );
            sendNotFound( response, "Profile not found" );
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Failed to update profile: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to update profile" );
        }
    }

    /**
     * Authenticates a user with username and password.
     */
    private void handleLogin( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "POST auth/login" );

        // Parse JSON body
        final JsonObject body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        final String username = body.has( "username" ) ? body.get( "username" ).getAsString() : null;
        final String password = body.has( "password" ) ? body.get( "password" ).getAsString() : null;

        if ( username == null || username.trim().isEmpty() || password == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Username and password are required" );
            return;
        }

        final Engine engine = getEngine();
        final AuthenticationManager authManager = engine.getManager( AuthenticationManager.class );
        final Session wikiSession = Wiki.session().find( engine, request );

        try {
            final boolean success = authManager.login( wikiSession, request, username, password );

            if ( success ) {
                final Map< String, Object > result = new LinkedHashMap<>();
                result.put( "success", true );
                result.put( "username", username );
                sendJson( response, result );
            } else {
                sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials" );
            }
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Login error for user '{}': {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials" );
        }
    }

    /**
     * Invalidates the current session.
     */
    private void handleLogout( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "POST auth/logout" );

        request.getSession().invalidate();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );

        sendJson( response, result );
    }

    /**
     * Sends a password reset email with a new random password.
     * Always returns a generic success message to prevent email enumeration.
     * Rate limited to {@value #MAX_RESET_PER_HOUR} requests per email per hour.
     */
    private void handleResetPassword( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        LOG.debug( "POST auth/reset-password" );

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String email = getJsonString( body, "email" );
        if ( email == null || email.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Email address is required" );
            return;
        }

        // Rate limiting: clean up old entries, then check count
        RESET_ATTEMPTS.cleanup( ONE_HOUR_MS );
        if ( RESET_ATTEMPTS.count( email.toLowerCase() ) >= MAX_RESET_PER_HOUR ) {
            LOG.warn( "Rate limit exceeded for password reset: {}", email );
            // Still return generic success to prevent enumeration
            sendJson( response, Map.of( "success", true, "message", RESET_GENERIC_MESSAGE ) );
            return;
        }

        // Record this attempt
        RESET_ATTEMPTS.add( email.toLowerCase() );

        final Engine engine = getEngine();
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();

        try {
            final UserProfile profile = db.findByEmail( email );
            final String randomPassword = TextUtil.generateRandomPassword();

            // Send email first (more likely to fail)
            final String subject = engine.getApplicationName() + " - Password Reset";
            final String mailBody = "Your password for " + engine.getApplicationName()
                    + " has been reset.\n\n"
                    + "Login name: " + profile.getLoginName() + "\n"
                    + "New password: " + randomPassword + "\n\n"
                    + "Please change your password after logging in.";

            MailUtil.sendMessage( engine.getWikiProperties(), profile.getEmail(), subject, mailBody );

            // Email succeeded, now save the new password
            profile.setPassword( randomPassword );
            db.save( profile );

            LOG.info( "Password reset for user '{}' (email: {})", profile.getLoginName(), email );
        } catch ( final NoSuchPrincipalException e ) {
            // User not found — log but return generic success to prevent enumeration
            LOG.info( "Password reset requested for unknown email: {}", email );
        } catch ( final Exception e ) {
            // Mail or save failed — log but still return generic success
            LOG.error( "Failed to process password reset for email {}: {}", email, e.getMessage() );
        }

        // Always return generic success
        sendJson( response, Map.of( "success", true, "message", RESET_GENERIC_MESSAGE ) );
    }

    // ----- Helper methods -----

    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
        map.put( "wikiName", profile.getWikiName() );
        map.put( "created", formatDate( profile.getCreated() ) );
        map.put( "lastModified", formatDate( profile.getLastModified() ) );
        return map;
    }

    private String formatDate( final Date date ) {
        if ( date == null ) return null;
        return new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" ).format( date );
    }

    private JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return null;
        }
    }

    private String getJsonString( final JsonObject obj, final String key ) {
        if ( obj.has( key ) && !obj.get( key ).isJsonNull() ) {
            return obj.get( key ).getAsString();
        }
        return null;
    }

}

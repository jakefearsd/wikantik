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

import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.validate.PasswordValidator;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
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

    private UserDatabase getUserDatabase() {
        return getEngine().getManager( UserManager.class ).getUserDatabase();
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
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to list users: " + e.getMessage() );
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
        final String password = getJsonString( body, "password" );

        if ( loginName == null || loginName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "loginName is required" );
            return;
        }
        if ( password == null || password.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "password is required" );
            return;
        }
        final List<String> passwordErrors = PasswordValidator.validate( password, getEngine().getWikiProperties() );
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
            profile.setPassword( password );
            db.save( profile );

            LOG.info( "Created user: {}", loginName );
            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, profileToMap( db.findByLoginName( loginName ) ) );
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Failed to create user {}: {}", loginName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_CONFLICT, "Failed to create user: " + e.getMessage() );
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

            if ( fullName != null ) profile.setFullname( fullName );
            if ( email != null ) profile.setEmail( email );
            if ( password != null && !password.isBlank() ) {
                final List<String> passwordErrors = PasswordValidator.validate( password, getEngine().getWikiProperties() );
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
                expiry = new SimpleDateFormat( "yyyy-MM-dd" ).parse( expiryStr );
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

    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
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

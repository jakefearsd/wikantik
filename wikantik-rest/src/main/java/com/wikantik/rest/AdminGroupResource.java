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
import com.google.gson.JsonParser;

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for admin group management.
 * <p>
 * Mapped to {@code /admin/groups/*}. All requests are pre-authorized by
 * {@link AdminAuthFilter}. Handles:
 * <ul>
 *   <li>{@code GET /admin/groups} — list all groups with members</li>
 *   <li>{@code GET /admin/groups/{name}} — get single group</li>
 *   <li>{@code PUT /admin/groups/{name}} — create or update group</li>
 *   <li>{@code DELETE /admin/groups/{name}} — delete group</li>
 * </ul>
 */
public class AdminGroupResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminGroupResource.class );

    private GroupManager getGroupManager() {
        return getEngine().getManager( GroupManager.class );
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );

        if ( pathParam == null ) {
            handleListGroups( response );
        } else {
            handleGetGroup( response, pathParam );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String groupName = extractPathParam( request );
        if ( groupName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Group name required in path" );
            return;
        }
        handleCreateOrUpdateGroup( request, response, groupName );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String groupName = extractPathParam( request );
        if ( groupName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Group name required in path" );
            return;
        }
        handleDeleteGroup( response, groupName );
    }

    private void handleListGroups( final HttpServletResponse response ) throws IOException {
        try {
            final GroupManager gm = getGroupManager();
            final Principal[] groupPrincipals = gm.getRoles();
            final List< Map< String, Object > > groups = new ArrayList<>();

            for ( final Principal gp : groupPrincipals ) {
                try {
                    final Group group = gm.getGroup( gp.getName() );
                    groups.add( groupToMap( group ) );
                } catch ( final Exception e ) {
                    LOG.warn( "Could not load group {}: {}", gp.getName(), e.getMessage() );
                }
            }

            sendJson( response, Map.of( "groups", groups ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list groups", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to list groups: " + e.getMessage() );
        }
    }

    private void handleGetGroup( final HttpServletResponse response, final String groupName ) throws IOException {
        try {
            final Group group = getGroupManager().getGroup( groupName );
            sendJson( response, groupToMap( group ) );
        } catch ( final Exception e ) {
            sendNotFound( response, "Group not found: " + groupName );
        }
    }

    private void handleCreateOrUpdateGroup( final HttpServletRequest request, final HttpServletResponse response,
                                             final String groupName ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        try {
            final GroupManager gm = getGroupManager();

            // Build the member line from the JSON array.
            // GroupManager.parseGroup() uses newline as the member separator.
            final StringBuilder memberLine = new StringBuilder();
            if ( body.has( "members" ) && body.get( "members" ).isJsonArray() ) {
                final JsonArray membersArray = body.getAsJsonArray( "members" );
                for ( int i = 0; i < membersArray.size(); i++ ) {
                    if ( i > 0 ) memberLine.append( "\n" );
                    memberLine.append( membersArray.get( i ).getAsString() );
                }
            }

            // Parse and create/update the group
            final Group group = gm.parseGroup( groupName, memberLine.toString(), true );

            // Get the session for setGroup
            final Session session = Wiki.session().find( getEngine(), request );
            gm.setGroup( session, group );

            LOG.info( "Created/updated group: {}", groupName );
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, groupToMap( gm.getGroup( groupName ) ) );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Failed to create/update group {}: {}", groupName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Failed to create/update group: " + e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "Failed to create/update group {}", groupName, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to create/update group: " + e.getMessage() );
        }
    }

    private void handleDeleteGroup( final HttpServletResponse response, final String groupName ) throws IOException {
        try {
            getGroupManager().removeGroup( groupName );
            LOG.info( "Deleted group: {}", groupName );
            sendJson( response, Map.of( "success", true ) );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Cannot delete group {}: {}", groupName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final Exception e ) {
            LOG.error( "Failed to delete group {}: {}", groupName, e.getMessage() );
            sendNotFound( response, "Group not found: " + groupName );
        }
    }

    private Map< String, Object > groupToMap( final Group group ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "name", group.getName() );

        final List< String > memberNames = new ArrayList<>();
        for ( final Principal member : group.members() ) {
            memberNames.add( member.getName() );
        }
        map.put( "members", memberNames );
        map.put( "creator", group.getCreator() );
        map.put( "created", group.getCreated() != null ? group.getCreated().toString() : null );
        map.put( "modifier", group.getModifier() );
        map.put( "lastModified", group.getLastModified() != null ? group.getLastModified().toString() : null );
        return map;
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
}

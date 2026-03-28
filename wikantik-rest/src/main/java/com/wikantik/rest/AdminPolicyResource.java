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

import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.DatabasePolicy;
import com.wikantik.auth.DefaultAuthorizationManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST servlet for admin policy grant management.
 * <p>
 * Mapped to {@code /admin/policy/*}. All requests are pre-authorized by
 * {@link AdminAuthFilter}. Handles:
 * <ul>
 *   <li>{@code GET /admin/policy} — list all policy grants</li>
 *   <li>{@code POST /admin/policy} — create a new grant</li>
 *   <li>{@code PUT /admin/policy/{id}} — update a grant</li>
 *   <li>{@code DELETE /admin/policy/{id}} — delete a grant</li>
 * </ul>
 */
public class AdminPolicyResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminPolicyResource.class );

    /** Valid page permission actions. */
    private static final Set< String > PAGE_ACTIONS = Set.of(
            "view", "comment", "edit", "modify", "upload", "rename", "delete" );

    /** Valid wiki permission actions. */
    private static final Set< String > WIKI_ACTIONS = Set.of(
            "createPages", "createGroups", "editPreferences", "editProfile", "login" );

    /** Valid group permission actions. */
    private static final Set< String > GROUP_ACTIONS = Set.of( "view", "edit" );

    /** Valid principal types. */
    private static final Set< String > PRINCIPAL_TYPES = Set.of( "role", "user", "group" );

    /** Valid permission types. */
    private static final Set< String > PERMISSION_TYPES = Set.of( "page", "wiki", "group" );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    /**
     * Returns the DatabasePolicy from the DefaultAuthorizationManager, or null
     * if the engine is using file-based policy.
     */
    private DatabasePolicy getDatabasePolicy() {
        final AuthorizationManager authMgr = getEngine().getManager( AuthorizationManager.class );
        if ( authMgr instanceof DefaultAuthorizationManager dam ) {
            return dam.getDatabasePolicy();
        }
        return null;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        handleListGrants( response );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        handleCreateGrant( request, response );
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String idStr = extractPathParam( request );
        if ( idStr == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Grant ID required in path" );
            return;
        }
        handleUpdateGrant( request, response, idStr );
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String idStr = extractPathParam( request );
        if ( idStr == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Grant ID required in path" );
            return;
        }
        handleDeleteGrant( response, idStr );
    }

    private void handleListGrants( final HttpServletResponse response ) throws IOException {
        final DatabasePolicy dbPolicy = getDatabasePolicy();
        if ( dbPolicy == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database policy is not configured. Policy grants are managed via file-based policy." );
            return;
        }

        final DataSource ds = dbPolicy.getDataSource();
        final String tableName = dbPolicy.getTableName();
        final String sql = "SELECT id, principal_type, principal_name, permission_type, target, actions FROM "
                + tableName + " ORDER BY id";

        try ( final Connection conn = ds.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {

            final List< Map< String, Object > > grants = new ArrayList<>();
            while ( rs.next() ) {
                final Map< String, Object > grant = new LinkedHashMap<>();
                grant.put( "id", rs.getInt( "id" ) );
                grant.put( "principalType", rs.getString( "principal_type" ) );
                grant.put( "principalName", rs.getString( "principal_name" ) );
                grant.put( "permissionType", rs.getString( "permission_type" ) );
                grant.put( "target", rs.getString( "target" ) );
                grant.put( "actions", rs.getString( "actions" ) );
                grants.add( grant );
            }

            sendJson( response, Map.of( "grants", grants ) );
        } catch ( final SQLException e ) {
            LOG.error( "Failed to list policy grants", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to list policy grants" );
        }
    }

    private void handleCreateGrant( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String principalType = getJsonString( body, "principalType" );
        final String principalName = getJsonString( body, "principalName" );
        final String permissionType = getJsonString( body, "permissionType" );
        final String target = getJsonString( body, "target" );
        final String actions = getJsonString( body, "actions" );

        // Validate required fields
        final String validationError = validateGrantFields( principalType, principalName, permissionType, target, actions );
        if ( validationError != null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, validationError );
            return;
        }

        final DatabasePolicy dbPolicy = getDatabasePolicy();
        if ( dbPolicy == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database policy is not configured." );
            return;
        }

        final DataSource ds = dbPolicy.getDataSource();
        final String tableName = dbPolicy.getTableName();
        final String sql = "INSERT INTO " + tableName
                + " (principal_type, principal_name, permission_type, target, actions) VALUES (?, ?, ?, ?, ?)";

        try ( final Connection conn = ds.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql, PreparedStatement.RETURN_GENERATED_KEYS ) ) {

            ps.setString( 1, principalType );
            ps.setString( 2, principalName );
            ps.setString( 3, permissionType );
            ps.setString( 4, target );
            ps.setString( 5, actions );
            ps.executeUpdate();

            int generatedId = -1;
            try ( final ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) {
                    generatedId = keys.getInt( 1 );
                }
            }

            // Refresh the in-memory cache
            dbPolicy.refresh();

            LOG.info( "Created policy grant: {} {} {} {} {}", principalType, principalName,
                    permissionType, target, actions );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "id", generatedId );
            result.put( "principalType", principalType );
            result.put( "principalName", principalName );
            result.put( "permissionType", permissionType );
            result.put( "target", target );
            result.put( "actions", actions );

            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, result );
        } catch ( final SQLException e ) {
            LOG.error( "Failed to create policy grant", e );
            sendError( response, HttpServletResponse.SC_CONFLICT,
                    "Failed to create policy grant" );
        }
    }

    private void handleUpdateGrant( final HttpServletRequest request, final HttpServletResponse response,
                                     final String idStr ) throws IOException {
        final int id;
        try {
            id = Integer.parseInt( idStr );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid grant ID: " + idStr );
            return;
        }

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String principalType = getJsonString( body, "principalType" );
        final String principalName = getJsonString( body, "principalName" );
        final String permissionType = getJsonString( body, "permissionType" );
        final String target = getJsonString( body, "target" );
        final String actions = getJsonString( body, "actions" );

        // Validate required fields
        final String validationError = validateGrantFields( principalType, principalName, permissionType, target, actions );
        if ( validationError != null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, validationError );
            return;
        }

        final DatabasePolicy dbPolicy = getDatabasePolicy();
        if ( dbPolicy == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database policy is not configured." );
            return;
        }

        final DataSource ds = dbPolicy.getDataSource();
        final String tableName = dbPolicy.getTableName();
        final String sql = "UPDATE " + tableName
                + " SET principal_type = ?, principal_name = ?, permission_type = ?, target = ?, actions = ? WHERE id = ?";

        try ( final Connection conn = ds.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {

            ps.setString( 1, principalType );
            ps.setString( 2, principalName );
            ps.setString( 3, permissionType );
            ps.setString( 4, target );
            ps.setString( 5, actions );
            ps.setInt( 6, id );

            final int rows = ps.executeUpdate();
            if ( rows == 0 ) {
                sendNotFound( response, "Grant not found: " + id );
                return;
            }

            // Refresh the in-memory cache
            dbPolicy.refresh();

            LOG.info( "Updated policy grant {}: {} {} {} {} {}", id, principalType, principalName,
                    permissionType, target, actions );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "id", id );
            result.put( "principalType", principalType );
            result.put( "principalName", principalName );
            result.put( "permissionType", permissionType );
            result.put( "target", target );
            result.put( "actions", actions );

            sendJson( response, result );
        } catch ( final SQLException e ) {
            LOG.error( "Failed to update policy grant {}", id, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to update policy grant" );
        }
    }

    private void handleDeleteGrant( final HttpServletResponse response, final String idStr ) throws IOException {
        final int id;
        try {
            id = Integer.parseInt( idStr );
        } catch ( final NumberFormatException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid grant ID: " + idStr );
            return;
        }

        final DatabasePolicy dbPolicy = getDatabasePolicy();
        if ( dbPolicy == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database policy is not configured." );
            return;
        }

        final DataSource ds = dbPolicy.getDataSource();
        final String tableName = dbPolicy.getTableName();
        final String sql = "DELETE FROM " + tableName + " WHERE id = ?";

        try ( final Connection conn = ds.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {

            ps.setInt( 1, id );
            final int rows = ps.executeUpdate();
            if ( rows == 0 ) {
                sendNotFound( response, "Grant not found: " + id );
                return;
            }

            // Refresh the in-memory cache
            dbPolicy.refresh();

            LOG.info( "Deleted policy grant: {}", id );
            sendJson( response, Map.of( "success", true ) );
        } catch ( final SQLException e ) {
            LOG.error( "Failed to delete policy grant {}", id, e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to delete policy grant" );
        }
    }

    /**
     * Validates all grant fields. Returns an error message if validation fails, or null if valid.
     */
    private String validateGrantFields( final String principalType, final String principalName,
                                         final String permissionType, final String target,
                                         final String actions ) {
        if ( principalType == null || principalType.isBlank() ) {
            return "principalType is required";
        }
        if ( principalName == null || principalName.isBlank() ) {
            return "principalName is required";
        }
        if ( permissionType == null || permissionType.isBlank() ) {
            return "permissionType is required";
        }
        if ( target == null || target.isBlank() ) {
            return "target is required";
        }
        if ( actions == null || actions.isBlank() ) {
            return "actions is required";
        }

        // Validate principal type
        if ( !PRINCIPAL_TYPES.contains( principalType ) ) {
            return "Invalid principalType: " + principalType + ". Must be one of: " + PRINCIPAL_TYPES;
        }

        // Validate permission type
        if ( !PERMISSION_TYPES.contains( permissionType ) ) {
            return "Invalid permissionType: " + permissionType + ". Must be one of: " + PERMISSION_TYPES;
        }

        // Validate actions
        return validateActions( permissionType, actions );
    }

    /**
     * Validates that each comma-separated action is valid for the given permission type.
     * Returns an error message if invalid, or null if valid.
     */
    private String validateActions( final String permissionType, final String actions ) {
        // Special case: "*" means AllPermission
        if ( "*".equals( actions.trim() ) ) {
            return null;
        }

        final Set< String > validActions = switch ( permissionType ) {
            case "page" -> PAGE_ACTIONS;
            case "wiki" -> WIKI_ACTIONS;
            case "group" -> GROUP_ACTIONS;
            default -> Set.of();
        };

        final String[] actionList = actions.split( "," );
        for ( final String action : actionList ) {
            final String trimmed = action.trim();
            if ( trimmed.isEmpty() || !validActions.contains( trimmed ) ) {
                return "Invalid action '" + trimmed + "' for permission type '" + permissionType
                        + "'. Valid actions: " + validActions;
            }
        }
        return null;
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

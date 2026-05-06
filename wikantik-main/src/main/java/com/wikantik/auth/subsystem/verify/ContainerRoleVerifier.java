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
package com.wikantik.auth.subsystem.verify;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.SecurityVerifier;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebContainerAuthorizer;

import java.security.Principal;

/**
 * Verifies which web-container roles map to which servlet URL patterns.
 * Extracted from {@link SecurityVerifier} as part of Phase 4 Checkpoint 3.
 *
 * <p>This helper is stateless beyond its constructor arguments and has no
 * eager verification side-effects; callers invoke
 * {@link #containerRoleTable()} and {@link #webContainerRoles()} on demand.</p>
 */
public final class ContainerRoleVerifier {

    private static final String[] CONTAINER_ACTIONS = {
            "View pages",
            "Comment on existing pages",
            "Edit pages",
            "Upload attachments",
            "Create a new group",
            "Rename an existing page",
            "Delete pages"
    };

    private static final String[] CONTAINER_PATHS = {
            "/attach/*",
            "/attach/*",
            "/api/*",
            "/api/attachments/*",
            "/admin/*",
            "/admin/*",
            "/admin/*"
    };

    private static final String BG_GREEN = "bgcolor=\"#c0ffc0\"";
    private static final String BG_RED   = "bgcolor=\"#ffc0c0\"";

    private final Engine               engine;
    private final AuthorizationManager authorizationManager;

    /**
     * Constructs a ContainerRoleVerifier.
     *
     * @param engine               the wiki engine (unused directly but kept for symmetry)
     * @param authorizationManager the authorization manager
     */
    public ContainerRoleVerifier( final Engine engine,
                                  final AuthorizationManager authorizationManager ) {
        this.engine               = engine;
        this.authorizationManager = authorizationManager;
    }

    /**
     * Formats and returns an HTML table containing the roles the web container
     * is aware of, and whether each role maps to particular servlet URL patterns.
     *
     * @return the formatted HTML table
     * @throws WikiException         if tests fail for unexpected reasons
     * @throws IllegalStateException if the authorizer is not a {@link WebContainerAuthorizer}
     */
    public String containerRoleTable() throws WikiException {
        final Authorizer authorizer = authorizationManager.getAuthorizer();

        if( !( authorizer instanceof final WebContainerAuthorizer wca ) ) {
            throw new IllegalStateException( "Authorizer should be WebContainerAuthorizer" );
        }

        final StringBuilder containerTable = new StringBuilder();
        final Principal[] roles = authorizer.getRoles();
        containerTable.append( "<table class=\"wikitable\" border=\"1\">\n" );
        containerTable.append( "<thead>\n" );
        containerTable.append( "  <tr>\n" );
        containerTable.append( "    <th rowspan=\"2\">Action</th>\n" );
        containerTable.append( "    <th rowspan=\"2\">Page</th>\n" );
        containerTable.append( "    <th colspan=\"" ).append( roles.length ).append( 1 ).append( "\">Roles</th>\n" );
        containerTable.append( "  </tr>\n" );
        containerTable.append( "  <tr>\n" );
        containerTable.append( "    <th>Anonymous</th>\n" );
        for( final Principal role : roles ) {
            containerTable.append( "    <th>" ).append( role.getName() ).append( "</th>\n" );
        }
        containerTable.append( "</tr>\n" );
        containerTable.append( "</thead>\n" );
        containerTable.append( "<tbody>\n" );

        for( int i = 0; i < CONTAINER_ACTIONS.length; i++ ) {
            final String action = CONTAINER_ACTIONS[i];
            final String path   = CONTAINER_PATHS[i];

            final boolean allowsAnonymous = !wca.isConstrained( path, Role.ALL );
            containerTable.append( "  <tr>\n" );
            containerTable.append( "    <td>" ).append( action ).append( "</td>\n" );
            containerTable.append( "    <td>" ).append( path ).append( "</td>\n" );
            containerTable.append( "    <td title=\"" );
            containerTable.append( allowsAnonymous ? "ALLOW: " : "DENY: " );
            containerTable.append( path );
            containerTable.append( " Anonymous" );
            containerTable.append( '"' );
            containerTable.append( allowsAnonymous ? BG_GREEN + ">" : BG_RED + ">" );
            containerTable.append( "&nbsp;</td>\n" );
            for( final Principal role : roles ) {
                final boolean allowed = allowsAnonymous || wca.isConstrained( path, (Role) role );
                containerTable.append( "    <td title=\"" );
                containerTable.append( allowed ? "ALLOW: " : "DENY: " );
                containerTable.append( path );
                containerTable.append( ' ' );
                containerTable.append( role.getClass().getName() );
                containerTable.append( " &quot;" );
                containerTable.append( role.getName() );
                containerTable.append( "&quot;" );
                containerTable.append( '"' );
                containerTable.append( allowed ? BG_GREEN + ">" : BG_RED + ">" );
                containerTable.append( "&nbsp;</td>\n" );
            }
            containerTable.append( "  </tr>\n" );
        }

        containerTable.append( "</tbody>\n" );
        containerTable.append( "</table>\n" );
        return containerTable.toString();
    }

    /**
     * If the active Authorizer is the {@link WebContainerAuthorizer}, returns
     * the roles it knows about; otherwise returns a zero-length array.
     *
     * @return the roles parsed from {@code web.xml}, or a zero-length array
     * @throws WikiException if the web authorizer cannot obtain the list of roles
     */
    public Principal[] webContainerRoles() throws WikiException {
        final Authorizer authorizer = authorizationManager.getAuthorizer();
        if( authorizer instanceof WebContainerAuthorizer wca ) {
            return wca.getRoles();
        }
        return new Principal[0];
    }
}

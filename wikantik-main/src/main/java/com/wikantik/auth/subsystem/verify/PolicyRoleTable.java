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

import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.permissions.WikiPermission;

import java.security.Permission;
import java.security.Principal;

/**
 * Renders the sample-permission HTML table used by {@link PolicyVerifier#policyRoleTable()}.
 * Split out of {@link PolicyVerifier} (2026-09, complexity burn-down): once the table-building
 * logic outgrew a single method and was broken into several private helpers, those helpers'
 * sheer count started tripping PMD's class-level GodClass rule on {@code PolicyVerifier} even
 * though every individual method was within its own complexity budget — moving the whole
 * cohesive table-rendering concern to its own class (a pure function of roles/wiki
 * name/authorization manager, no other state) fixes that at the source instead of re-merging
 * methods back together.
 */
final class PolicyRoleTable {

    private static final String BG_GREEN = "bgcolor=\"#c0ffc0\"";
    private static final String BG_RED   = "bgcolor=\"#ffc0c0\"";

    private final AuthorizationManager authorizationManager;

    PolicyRoleTable( final AuthorizationManager authorizationManager ) {
        this.authorizationManager = authorizationManager;
    }

    /**
     * Formats and returns an HTML table containing sample permissions and what
     * roles are allowed to have them.
     *
     * @return the formatted HTML table
     */
    String render( final Principal[] roles, final String wiki ) {
        final String[] pages = { "Main", "Index", "GroupTest", "GroupAdmin" };
        final String[] pageActions = { "view", "edit", "modify", "rename", "delete" };

        final String[] groups = { "Admin", "TestGroup", "Foo" };
        final String[] groupActions = { "view", "edit", null, null, "delete" };

        final int pageActionsLength = pageActions.length;
        final String colWidth = roles.length > 0 ? ( 67f / ( pageActionsLength * roles.length ) ) + "%" : "67%";

        final StringBuilder table = new StringBuilder();
        table.append( "<table class=\"wikitable\" border=\"1\">\n" );
        appendTableHeader( table, roles, pageActions, colWidth );
        appendPageRows( table, pages, pageActions, roles, wiki );
        appendGroupRows( table, groups, groupActions, roles, wiki );
        appendWikiAndAllPermissionRows( table, roles, wiki, pageActionsLength );
        table.append( "</table>" );
        return table.toString();
    }

    /**
     *  Appends the two-row column-group header (role names, then per-role page-action initials)
     *  to {@code table}.
     */
    private void appendTableHeader( final StringBuilder table, final Principal[] roles, final String[] pageActions, final String colWidth ) {
        final int rolesLength = roles.length;
        final int pageActionsLength = pageActions.length;

        table.append( "  <colgroup span=\"1\" width=\"33%\"/>\n" );
        table.append( "  <colgroup span=\"" ).append( pageActionsLength * rolesLength ).append( "\" width=\"" ).append( colWidth ).append( "\" align=\"center\"/>\n" );
        table.append( "  <tr>\n" );
        table.append( "    <th rowspan=\"2\" valign=\"bottom\">Permission</th>\n" );
        for( final Principal principal : roles ) {
            table.append( "    <th colspan=\"" ).append( pageActionsLength ).append( "\" title=\"" ).append( principal.getClass().getName() ).append( "\">" ).append( principal.getName() ).append( "</th>\n" );
        }
        table.append( "  </tr>\n" );

        table.append( "  <tr>\n" );
        for( int i = 0; i < rolesLength; i++ ) {
            for( final String pageAction : pageActions ) {
                final String action = pageAction.substring( 0, 1 );
                table.append( "    <th title=\"" ).append( pageAction ).append( "\">" ).append( action ).append( "</th>\n" );
            }
        }
        table.append( "  </tr>\n" );
    }

    /**
     *  Appends one {@code PagePermission} row per entry in {@code pages}, one cell per
     *  role/page-action combination.
     */
    private void appendPageRows( final StringBuilder table, final String[] pages, final String[] pageActions, final Principal[] roles, final String wiki ) {
        for( final String page : pages ) {
            table.append( "  <tr>\n" );
            table.append( "    <td>PagePermission \"" ).append( wiki ).append( ':' ).append( page ).append( "\"</td>\n" );
            for( final Principal role : roles ) {
                for( final String pageAction : pageActions ) {
                    final Permission permission = PermissionFactory.getPagePermission( wiki + ":" + page, pageAction );
                    table.append( printPermissionTest( permission, role, 1 ) );
                }
            }
            table.append( "  </tr>\n" );
        }
    }

    /**
     *  Appends one {@code GroupPermission} row per entry in {@code groups}, one cell per
     *  role/group-action combination (a {@code null} action renders an N/A cell).
     */
    private void appendGroupRows( final StringBuilder table, final String[] groups, final String[] groupActions, final Principal[] roles, final String wiki ) {
        for( final String group : groups ) {
            table.append( "  <tr>\n" );
            table.append( "    <td>GroupPermission \"" ).append( wiki ).append( ':' ).append( group ).append( "\"</td>\n" );
            for( final Principal role : roles ) {
                for( final String groupAction : groupActions ) {
                    Permission permission = null;
                    if( groupAction != null ) {
                        permission = new GroupPermission( wiki + ":" + group, groupAction );
                    }
                    table.append( printPermissionTest( permission, role, 1 ) );
                }
            }
            table.append( "  </tr>\n" );
        }
    }

    /**
     *  Appends one {@code WikiPermission} row per named wiki-level action, followed by a
     *  single {@code AllPermission} row.
     */
    private void appendWikiAndAllPermissionRows( final StringBuilder table, final Principal[] roles, final String wiki, final int pageActionsLength ) {
        final String[] wikiPerms = { "createGroups", "createPages", "login", "editPreferences", "editProfile" };
        for( final String wikiPerm : wikiPerms ) {
            table.append( "  <tr>\n" );
            table.append( "    <td>WikiPermission \"" ).append( wiki ).append( "\",\"" ).append( wikiPerm ).append( "\"</td>\n" );
            for( final Principal role : roles ) {
                final Permission permission = new WikiPermission( wiki, wikiPerm );
                table.append( printPermissionTest( permission, role, pageActionsLength ) );
            }
            table.append( "  </tr>\n" );
        }

        table.append( "  <tr>\n" );
        table.append( "    <td>AllPermission \"" ).append( wiki ).append( "\"</td>\n" );
        for( final Principal role : roles ) {
            final Permission permission = new AllPermission( wiki );
            table.append( printPermissionTest( permission, role, pageActionsLength ) );
        }
        table.append( "  </tr>\n" );
    }

    private String printPermissionTest( final Permission permission, final Principal principal, final int cols ) {
        final StringBuilder cell = new StringBuilder();
        if( permission == null ) {
            cell.append( "    <td colspan=\"" ).append( cols ).append( "\" align=\"center\" title=\"N/A\">" );
            cell.append( "&nbsp;</td>\n" );
        } else {
            final boolean allowed = verifyStaticPermission( principal, permission );
            cell.append( "    <td colspan=\"" ).append( cols ).append( "\" align=\"center\" title=\"" );
            cell.append( allowed ? "ALLOW: " : "DENY: " );
            cell.append( permission.getClass().getName() );
            cell.append( " &quot;" );
            cell.append( permission.getName() );
            cell.append( "&quot;" );
            if( permission.getName() != null ) {
                cell.append( ",&quot;" );
                cell.append( permission.getActions() );
                cell.append( "&quot;" );
            }
            cell.append( ' ' );
            cell.append( principal.getClass().getName() );
            cell.append( " &quot;" );
            cell.append( principal.getName() );
            cell.append( "&quot;" );
            cell.append( '"' );
            cell.append( allowed ? BG_GREEN + ">" : BG_RED + ">" );
            cell.append( "&nbsp;</td>\n" );
        }
        return cell.toString();
    }

    private boolean verifyStaticPermission( final Principal principal, final Permission permission ) {
        final Principal[] principals = { principal };
        return authorizationManager.allowedByLocalPolicy( principals, permission );
    }
}

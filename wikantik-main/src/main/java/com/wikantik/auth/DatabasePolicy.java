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
package com.wikantik.auth;

import com.wikantik.auth.permissions.AdminPermission;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.security.Permission;
import java.security.Principal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Database-backed policy provider that loads permission grants from the
 * {@code policy_grants} table, caches them in memory, and provides an
 * {@link #implies(Principal, Permission)} method.
 *
 * <p>This class replaces the file-based {@code LocalPolicy} from the
 * freshcookies library, moving authorization policy from a static file
 * to the database where it can be managed through admin interfaces.</p>
 *
 * <p>Thread safety: the cached grants map is published via a volatile
 * field, so concurrent readers see a consistent snapshot without
 * synchronization.</p>
 *
 * @since 2.12
 */
public class DatabasePolicy
{
    private static final Logger LOG = LogManager.getLogger( DatabasePolicy.class );

    private final DataSource dataSource;
    private final String tableName;
    private final Jdbc jdbc;

    /** {@code principal_type} for a grant to a built-in or container role. */
    static final String TYPE_ROLE = "role";
    /** {@code principal_type} for a grant to a wiki group. */
    static final String TYPE_GROUP = "group";
    /** {@code principal_type} for a grant to a single user, keyed by login name. */
    static final String TYPE_USER = "user";

    /**
     * Immutable snapshot of the grant table, split by principal kind. Role and group rows share one
     * map because a role-typed grant also covers the wiki group of the same name (and vice versa);
     * user rows live apart so a user principal can never satisfy a role or group grant.
     */
    private record Snapshot( Map<String, List<Permission>> roleGrants, Map<String, List<Permission>> userGrants ) { }

    private volatile Snapshot grants = new Snapshot( Collections.emptyMap(), Collections.emptyMap() );

    /**
     * Creates a new DatabasePolicy backed by the given DataSource and table.
     * The constructor immediately loads all grants from the database via
     * {@link #refresh()}.
     *
     * @param dataSource the JDBC DataSource to read grants from
     * @param tableName  the name of the policy_grants table
     */
    public DatabasePolicy( final DataSource dataSource, final String tableName )
    {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.jdbc = new Jdbc( dataSource );
        refresh();
    }

    /**
     * Reloads all grants from the database into the in-memory cache.
     * Thread-safe: the new map is built locally and then published via
     * a single volatile write.
     */
    public void refresh()
    {
        final Map<String, List<Permission>> roleGrants = new HashMap<>();
        final Map<String, List<Permission>> userGrants = new HashMap<>();
        final String sql = "SELECT id, principal_type, principal_name, permission_type, target, actions FROM " + tableName;

        try
        {
            for( final PermissionGrant grant : jdbc.query( sql, SqlBinder.NONE, DatabasePolicy::readGrant ) )
            {
                final Map<String, List<Permission>> target = mapFor( grant, roleGrants, userGrants );
                final Permission perm = target == null ? null : buildPermissionOrSkip( grant );
                if( perm != null )
                {
                    target.computeIfAbsent( grant.principalName(), k -> new ArrayList<>() ).add( perm );
                }
            }
        }
        catch( final SQLException e )
        {
            // LOG.error justified: SQL failure loading policy grants makes authorization unreliable
            LOG.error( "Failed to load policy grants from table '{}': {}", tableName, e.getMessage(), e );
        }

        // Publish the new snapshot atomically
        this.grants = new Snapshot( Collections.unmodifiableMap( roleGrants ), Collections.unmodifiableMap( userGrants ) );
    }

    /**
     * Picks the grant map for a row by its {@code principal_type}, or returns {@code null} (with a
     * WARN) for a type this build does not recognise, so such a row grants nothing.
     */
    private Map<String, List<Permission>> mapFor( final PermissionGrant grant,
                                                 final Map<String, List<Permission>> roleGrants,
                                                 final Map<String, List<Permission>> userGrants )
    {
        final String type = grant.principalType() == null ? "" : grant.principalType().trim().toLowerCase( Locale.ROOT );
        return switch( type )
        {
            case TYPE_ROLE, TYPE_GROUP -> roleGrants;
            case TYPE_USER -> userGrants;
            default ->
            {
                LOG.warn( "Skipping policy grant id={} in table '{}': unknown principal_type '{}' for principal '{}'",
                        grant.id(), tableName, grant.principalType(), grant.principalName() );
                yield null;
            }
        };
    }

    /**
     * Returns {@code true} if the given principal has been granted a
     * permission that implies the requested permission.
     *
     * <p>Matching is by principal kind as well as name: a {@link com.wikantik.auth.authorize.Role}
     * or {@link GroupPrincipal} matches only {@code role}/{@code group} rows, and any other principal
     * matches only {@code user} rows. A {@link WikiPrincipal} carrying a display name
     * ({@link WikiPrincipal#FULL_NAME} or {@link WikiPrincipal#WIKI_NAME}) matches nothing, because
     * those names are user-editable profile data rather than identities.</p>
     *
     * @param principal the principal (typically a {@link com.wikantik.auth.authorize.Role})
     * @param requested the permission being checked
     * @return {@code true} if granted, {@code false} otherwise
     */
    public boolean implies( final Principal principal, final Permission requested )
    {
        final Map<String, List<Permission>> byName;
        if( AuthenticationManager.isRolePrincipal( principal ) )
        {
            byName = grants.roleGrants();
        }
        else if( isDisplayName( principal ) )
        {
            return false;
        }
        else
        {
            byName = grants.userGrants();
        }
        final List<Permission> granted = byName.get( principal.getName() );
        if( granted == null )
        {
            return false;
        }
        for( final Permission perm : granted )
        {
            if( perm.implies( requested ) )
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisplayName( final Principal principal )
    {
        return principal instanceof WikiPrincipal wp
                && ( WikiPrincipal.FULL_NAME.equals( wp.getType() ) || WikiPrincipal.WIKI_NAME.equals( wp.getType() ) );
    }

    /**
     * Returns the DataSource used by this policy.
     *
     * @return the JDBC DataSource
     */
    public DataSource getDataSource()
    {
        return dataSource;
    }

    /**
     * Returns the table name used for policy grants.
     *
     * @return the table name
     */
    public String getTableName()
    {
        return tableName;
    }

    /**
     * One {@code policy_grants} row as exposed to admin API callers: the id plus the five
     * grant fields, read verbatim (no {@link #buildPermission} interpretation).
     */
    public record GrantRecord( int id, String principalType, String principalName,
                                String permissionType, String target, String actions ) { }

    /**
     * Lists every row in the policy grants table, ordered by id. Moved here from
     * {@code AdminPolicyResource} — the servlet reads through this method instead of
     * running its own SQL.
     *
     * @throws SQLException if the query fails
     */
    public List< GrantRecord > listGrants() throws SQLException
    {
        final String sql = "SELECT id, principal_type, principal_name, permission_type, target, actions FROM "
                + tableName + " ORDER BY id";
        return jdbc.query( sql, SqlBinder.NONE, rs -> new GrantRecord(
                rs.getInt( "id" ),
                rs.getString( "principal_type" ),
                rs.getString( "principal_name" ),
                rs.getString( "permission_type" ),
                rs.getString( "target" ),
                rs.getString( "actions" ) ) );
    }

    /**
     * Inserts a new policy grant row.
     *
     * @return the generated id, or {@code -1} if the driver returned no generated key
     * @throws SQLException if the insert fails
     */
    public int insertGrant( final String principalType, final String principalName, final String permissionType,
                             final String target, final String actions ) throws SQLException
    {
        final String sql = "INSERT INTO " + tableName
                + " (principal_type, principal_name, permission_type, target, actions) VALUES (?, ?, ?, ?, ?)";
        final Optional< Integer > generatedId = jdbc.insertReturningKey( sql, ps ->
        {
            ps.setString( 1, principalType );
            ps.setString( 2, principalName );
            ps.setString( 3, permissionType );
            ps.setString( 4, target );
            ps.setString( 5, actions );
        }, rs -> rs.getInt( 1 ) );
        return generatedId.orElse( -1 );
    }

    /**
     * Updates an existing policy grant row by id.
     *
     * @return the number of rows affected (0 if no row matched)
     * @throws SQLException if the update fails
     */
    public int updateGrant( final int id, final String principalType, final String principalName,
                             final String permissionType, final String target, final String actions ) throws SQLException
    {
        final String sql = "UPDATE " + tableName
                + " SET principal_type = ?, principal_name = ?, permission_type = ?, target = ?, actions = ? WHERE id = ?";
        return jdbc.update( sql, ps ->
        {
            ps.setString( 1, principalType );
            ps.setString( 2, principalName );
            ps.setString( 3, permissionType );
            ps.setString( 4, target );
            ps.setString( 5, actions );
            ps.setInt( 6, id );
        } );
    }

    /**
     * Deletes a policy grant row by id.
     *
     * @return the number of rows affected (0 if no row matched)
     * @throws SQLException if the delete fails
     */
    public int deleteGrant( final int id ) throws SQLException
    {
        final String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        return jdbc.update( sql, ps -> ps.setInt( 1, id ) );
    }

    /** One {@code policy_grants} row's raw columns, read verbatim before {@link #buildPermission} interprets them. */
    private record PermissionGrant( int id, String principalType, String principalName, String permissionType,
                                    String target, String actions ) { }

    private static PermissionGrant readGrant( final java.sql.ResultSet rs ) throws SQLException {
        return new PermissionGrant(
                rs.getInt( "id" ),
                rs.getString( "principal_type" ),
                rs.getString( "principal_name" ),
                rs.getString( "permission_type" ),
                rs.getString( "target" ),
                rs.getString( "actions" ) );
    }

    /**
     * {@link #buildPermission} for one row, skipping (with a WARN) a row whose actions this build
     * does not recognise. Rollback safety: after a downgrade, a row written by a newer release
     * (e.g. the {@code export} wiki action) must cost only that one grant — an
     * {@link IllegalArgumentException} escaping {@link #refresh()} would stop the wiki booting.
     */
    private Permission buildPermissionOrSkip( final PermissionGrant grant )
    {
        try
        {
            return buildPermission( grant.permissionType(), grant.target(), grant.actions() );
        }
        catch( final IllegalArgumentException e )
        {
            LOG.warn( "Skipping policy grant id={} (principal '{}', type '{}', target '{}', actions '{}') in table '{}': {}",
                    grant.id(), grant.principalName(), grant.permissionType(), grant.target(), grant.actions(),
                    tableName, e.getMessage() );
            return null;
        }
    }

    /**
     * Builds a Permission object from the database row values.
     *
     * @param permType the permission type: "all" (AllPermission), "page", "wiki", or "group"
     * @param target   the permission target (e.g. "*", "*:&lt;groupmember&gt;")
     * @param actions  the comma-separated actions, or "*" for AllPermission
     * @return the constructed Permission, or {@code null} if the type is unrecognized
     */
    private Permission buildPermission( final String permType, final String target, final String actions )
    {
        final String type = ( permType == null ) ? "" : permType.toLowerCase( Locale.ROOT );

        // "all" is the canonical AllPermission type. Write-time validation pins target='*',
        // but we honour whatever wiki scope is stored for forward compatibility.
        if( "all".equals( type ) )
        {
            return new AllPermission( target );
        }

        // Back-compat: a wildcard action on a wildcard target is global AllPermission — this is how
        // the legacy seeded Admin page/wiki god-mode rows resolve. A wildcard action on a SPECIFIC
        // target is malformed (it is NOT "all actions on that target"): skip it rather than silently
        // mis-grant a scoped AllPermission. Express AllPermission with permission type "all". [R4]
        if( "*".equals( actions ) )
        {
            if( "*".equals( target ) )
            {
                return new AllPermission( target );
            }
            LOG.warn( "Ignoring wildcard-action grant with non-wildcard target '{}' (type '{}') in table '{}'; "
                    + "use permission type 'all' for AllPermission.", target, permType, tableName );
            return null;
        }

        return switch( type )
        {
            case "page" -> new PagePermission( qualifyTarget( target ), actions );
            case "wiki" -> new WikiPermission( target, actions );
            case "group" -> new GroupPermission( qualifyTarget( target ), actions );
            // Scoped access to ONE /admin/* functional area (target = the area, e.g. "insights").
            // Additive only: AllPermission still implies every AdminPermission, so an existing
            // administrator's reach is unchanged.
            case "admin" -> new AdminPermission( qualifyTarget( target ), actions );
            default ->
            {
                LOG.warn( "Unrecognized permission type '{}' in table '{}'; skipping.", permType, tableName );
                yield null;
            }
        };
    }

    /**
     * Ensures the target includes a wiki prefix. PagePermission and
     * GroupPermission expect targets in "wiki:name" format. If the
     * stored target lacks a colon separator, prepend "*:" so the
     * permission applies to all wikis.
     *
     * @param target the raw target from the database
     * @return the qualified target in "wiki:name" format
     */
    private static String qualifyTarget( final String target )
    {
        if( target != null && !target.contains( ":" ) )
        {
            return "*:" + target;
        }
        return target;
    }
}

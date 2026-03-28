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

import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.security.Permission;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Cache: principal name (case-sensitive) to list of granted Permissions. */
    private volatile Map<String, List<Permission>> grants = Collections.emptyMap();

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
        refresh();
    }

    /**
     * Reloads all grants from the database into the in-memory cache.
     * Thread-safe: the new map is built locally and then published via
     * a single volatile write.
     */
    public void refresh()
    {
        final Map<String, List<Permission>> newGrants = new HashMap<>();
        final String sql = "SELECT principal_name, permission_type, target, actions FROM " + tableName;

        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql );
             final ResultSet rs = ps.executeQuery() )
        {
            while( rs.next() )
            {
                final String principalName = rs.getString( "principal_name" );
                final String permType = rs.getString( "permission_type" );
                final String target = rs.getString( "target" );
                final String actions = rs.getString( "actions" );

                final Permission perm = buildPermission( permType, target, actions );
                if( perm != null )
                {
                    newGrants.computeIfAbsent( principalName, k -> new ArrayList<>() ).add( perm );
                }
            }
        }
        catch( final SQLException e )
        {
            // LOG.error justified: SQL failure loading policy grants makes authorization unreliable
            LOG.error( "Failed to load policy grants from table '{}': {}", tableName, e.getMessage(), e );
        }

        // Publish the new snapshot atomically
        this.grants = Collections.unmodifiableMap( newGrants );
    }

    /**
     * Returns {@code true} if the given principal has been granted a
     * permission that implies the requested permission.
     *
     * @param principal the principal (typically a {@link com.wikantik.auth.authorize.Role})
     * @param requested the permission being checked
     * @return {@code true} if granted, {@code false} otherwise
     */
    public boolean implies( final Principal principal, final Permission requested )
    {
        final List<Permission> granted = grants.get( principal.getName() );
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
     * Builds a Permission object from the database row values.
     *
     * @param permType the permission type: "page", "wiki", or "group"
     * @param target   the permission target (e.g. "*", "*:&lt;groupmember&gt;")
     * @param actions  the comma-separated actions, or "*" for AllPermission
     * @return the constructed Permission, or {@code null} if the type is unrecognized
     */
    private Permission buildPermission( final String permType, final String target, final String actions )
    {
        // Actions of "*" means AllPermission regardless of permType
        if( "*".equals( actions ) )
        {
            return new AllPermission( target );
        }

        return switch( permType.toLowerCase() )
        {
            case "page" -> new PagePermission( qualifyTarget( target ), actions );
            case "wiki" -> new WikiPermission( target, actions );
            case "group" -> new GroupPermission( qualifyTarget( target ), actions );
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

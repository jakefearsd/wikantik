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
package com.wikantik.comments;

import com.wikantik.api.comments.PageOwnership;
import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** JDBC gateway for {@code page_owners} plus the find-or-create lazy
 *  bootstrap that derives an initial owner from a page's frontmatter
 *  {@code author}. Applies the {@code admin} fallback at read time when the
 *  stored owner is missing or no longer a real user. */
public class PageOwnerService {

    public static final String ADMIN_FALLBACK = "admin";
    public static final String BOOTSTRAP_ASSIGNER = "system:bootstrap";

    private static final Logger LOG = LogManager.getLogger( PageOwnerService.class );

    private final Jdbc jdbc;
    private final Predicate< String > userExists;
    private final Function< String, Optional< String > > authorResolver;
    private final String defaultOwner;

    /**
     * Back-compat constructor: no configured default owner. Agent/unresolvable-author
     * pages bootstrap to a {@code NULL} (orphaned) owner.
     *
     * @param ds JDBC data source
     * @param userExists predicate: does this login_name exist in the user database?
     * @param authorResolver canonical_id → bootstrap login (typically the
     *        frontmatter {@code author}); empty when no author is available.
     */
    public PageOwnerService( final DataSource ds,
                             final Predicate< String > userExists,
                             final Function< String, Optional< String > > authorResolver ) {
        this( ds, userExists, authorResolver, null );
    }

    /**
     * @param ds JDBC data source
     * @param userExists predicate: does this login_name exist in the user database?
     * @param authorResolver canonical_id → bootstrap login (typically the
     *        frontmatter {@code author}); empty when no author is available.
     * @param defaultOwner login stored as the owner when the frontmatter author
     *        cannot be resolved to a real user (e.g. AI-agent-authored pages).
     *        Typically the {@code agents} service account. When {@code null},
     *        blank, or not itself a real user, such pages bootstrap to a
     *        {@code NULL} (orphaned) owner — so a missing/unseeded default
     *        degrades safely rather than persisting a dangling owner_login.
     */
    public PageOwnerService( final DataSource ds,
                             final Predicate< String > userExists,
                             final Function< String, Optional< String > > authorResolver,
                             final String defaultOwner ) {
        this.jdbc = new Jdbc( ds );
        this.userExists = userExists;
        this.authorResolver = authorResolver;
        this.defaultOwner = defaultOwner;
    }

    /** Resolved owner with the admin fallback applied. Find-or-create. */
    public String getOwner( final String canonicalId ) {
        final Optional< PageOwnership > existing = findRaw( canonicalId );
        if ( existing.isPresent() ) {
            return resolveWithFallback( existing.get().ownerLogin() );
        }
        // No row → bootstrap from the resolver. When the frontmatter author is not
        // a real user (e.g. AI-agent-authored pages), fall back to the configured
        // default owner so the stored data is consistent rather than orphaned.
        final Optional< String > author = authorResolver.apply( canonicalId );
        final String initial = author.filter( userExists ).orElseGet( this::defaultOwnerOrNull );
        insertRow( canonicalId, initial, BOOTSTRAP_ASSIGNER );
        return resolveWithFallback( initial );
    }

    /** The configured default owner if it is set and a real user; else {@code null}. */
    private String defaultOwnerOrNull() {
        if ( defaultOwner == null || defaultOwner.isBlank() ) {
            return null;
        }
        return userExists.test( defaultOwner ) ? defaultOwner : null;
    }

    /** Raw row (owner_login may be null). Read-only — does not bootstrap. */
    public Optional< PageOwnership > findRaw( final String canonicalId ) {
        try {
            return jdbc.queryOne( "SELECT canonical_id, owner_login, assigned_by, assigned_at " +
                    "FROM page_owners WHERE canonical_id = ?",
                    ps -> ps.setString( 1, canonicalId ), PageOwnerService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.findRaw({}) failed: {}", canonicalId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    /** Upsert (no bootstrap). Pass {@code null} owner to orphan explicitly. */
    public void setOwner( final String canonicalId, final String ownerLogin, final String assignedBy ) {
        try {
            jdbc.inTransaction( c -> {
                upsert( jdbc, c, canonicalId, ownerLogin, assignedBy );
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.setOwner({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "page-owner upsert failed", e );
        }
    }

    public int bulkReassign( final String fromOwner, final String toOwner, final String assignedBy ) {
        try {
            return jdbc.update( "UPDATE page_owners SET owner_login = ?, assigned_by = ?, " +
                    "assigned_at = CURRENT_TIMESTAMP WHERE owner_login = ?",
                    ps -> {
                        ps.setString( 1, toOwner );
                        ps.setString( 2, assignedBy );
                        ps.setString( 3, fromOwner );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "bulkReassign({}->{}) failed: {}", fromOwner, toOwner, e.getMessage(), e );
            throw new RuntimeException( "bulk reassign failed", e );
        }
    }

    /** Bulk-move every row whose {@code owner_login IS NULL} to {@code toOwner}.
     *  Mirrors {@link #bulkReassign}: same audit-stamp semantics, returns the
     *  number of rows updated. */
    public int reassignFromOrphaned( final String toOwner, final String assignedBy ) {
        try {
            return jdbc.update( "UPDATE page_owners SET owner_login = ?, assigned_by = ?, " +
                    "assigned_at = CURRENT_TIMESTAMP WHERE owner_login IS NULL",
                    ps -> {
                        ps.setString( 1, toOwner );
                        ps.setString( 2, assignedBy );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "reassignFromOrphaned({}) failed: {}", toOwner, e.getMessage(), e );
            throw new RuntimeException( "reassignFromOrphaned failed", e );
        }
    }

    public int orphanByOwner( final String owner, final String assignedBy ) {
        try {
            return jdbc.update( "UPDATE page_owners SET owner_login = NULL, assigned_by = ?, " +
                    "assigned_at = CURRENT_TIMESTAMP WHERE owner_login = ?",
                    ps -> {
                        ps.setString( 1, assignedBy );
                        ps.setString( 2, owner );
                    } );
        } catch ( final SQLException e ) {
            LOG.warn( "orphanByOwner({}) failed: {}", owner, e.getMessage(), e );
            throw new RuntimeException( "orphan-by-owner failed", e );
        }
    }

    public List< PageOwnership > listOrphaned( final int limit, final int offset ) {
        try {
            return jdbc.query( "SELECT canonical_id, owner_login, assigned_by, assigned_at FROM page_owners " +
                    "WHERE owner_login IS NULL ORDER BY assigned_at DESC LIMIT ? OFFSET ?",
                    ps -> {
                        ps.setInt( 1, limit );
                        ps.setInt( 2, offset );
                    }, PageOwnerService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "listOrphaned failed: {}", e.getMessage(), e );
            return List.of();
        }
    }

    public List< PageOwnership > listByOwner( final String owner, final int limit, final int offset ) {
        try {
            return jdbc.query( "SELECT canonical_id, owner_login, assigned_by, assigned_at FROM page_owners " +
                    "WHERE owner_login = ? ORDER BY assigned_at DESC LIMIT ? OFFSET ?",
                    ps -> {
                        ps.setString( 1, owner );
                        ps.setInt( 2, limit );
                        ps.setInt( 3, offset );
                    }, PageOwnerService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "listByOwner({}) failed: {}", owner, e.getMessage(), e );
            return List.of();
        }
    }

    public int countOrphaned() {
        return countQuery( "SELECT COUNT(*) FROM page_owners WHERE owner_login IS NULL", null );
    }

    public int countByOwner( final String owner ) {
        return countQuery( "SELECT COUNT(*) FROM page_owners WHERE owner_login = ?", owner );
    }

    // ---- internals ----

    private String resolveWithFallback( final String stored ) {
        if ( stored == null || stored.isBlank() ) return ADMIN_FALLBACK;
        if ( !userExists.test( stored ) ) return ADMIN_FALLBACK;
        return stored;
    }

    private void insertRow( final String canonicalId, final String ownerLogin, final String assignedBy ) {
        try {
            jdbc.inTransaction( c -> {
                upsert( jdbc, c, canonicalId, ownerLogin, assignedBy );
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.insertRow({}) failed: {}", canonicalId, e.getMessage(), e );
            // swallow: getOwner still returns the resolved value; next call retries.
        }
    }

    private static void upsert( final Jdbc jdbc, final Connection c, final String canonicalId,
                                final String ownerLogin, final String assignedBy ) throws SQLException {
        // Portable cross-DB upsert: explicit SELECT then INSERT or UPDATE.
        // Mirrors the established pattern in PageVerificationDao.
        final boolean exists = jdbc.queryOne( c, "SELECT 1 FROM page_owners WHERE canonical_id = ?",
                ps -> ps.setString( 1, canonicalId ), rs -> Boolean.TRUE ).isPresent();
        if ( exists ) {
            jdbc.update( c, "UPDATE page_owners SET owner_login = ?, assigned_by = ?, " +
                    "assigned_at = CURRENT_TIMESTAMP WHERE canonical_id = ?",
                    ps -> {
                        ps.setString( 1, ownerLogin );
                        ps.setString( 2, assignedBy );
                        ps.setString( 3, canonicalId );
                    } );
        } else {
            jdbc.update( c, "INSERT INTO page_owners (canonical_id, owner_login, assigned_by) " +
                    "VALUES (?, ?, ?)",
                    ps -> {
                        ps.setString( 1, canonicalId );
                        ps.setString( 2, ownerLogin );
                        ps.setString( 3, assignedBy );
                    } );
        }
    }

    private int countQuery( final String sql, final String arg ) {
        try {
            return jdbc.queryOne( sql, ps -> {
                if ( arg != null ) ps.setString( 1, arg );
            }, rs -> rs.getInt( 1 ) ).orElse( 0 );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService count failed: {}", e.getMessage(), e );
            return 0;
        }
    }

    private static PageOwnership readRow( final ResultSet rs ) throws SQLException {
        final Timestamp at = rs.getTimestamp( "assigned_at" );
        return new PageOwnership(
                rs.getString( "canonical_id" ),
                rs.getString( "owner_login" ),
                rs.getString( "assigned_by" ),
                at == null ? null : at.toInstant() );
    }
}

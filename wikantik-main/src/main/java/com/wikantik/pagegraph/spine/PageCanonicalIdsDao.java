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
package com.wikantik.pagegraph.spine;

import com.wikantik.jdbc.JdbcSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC gateway for {@code page_canonical_ids} and {@code page_slug_history}.
 * All methods are idempotent: {@link #upsert} inserts on miss and updates
 * on hit, emitting a history row whenever the slug actually changes.
 */
public class PageCanonicalIdsDao extends JdbcSupport {

    private static final Logger LOG = LogManager.getLogger( PageCanonicalIdsDao.class );

    public PageCanonicalIdsDao( final DataSource ds ) {
        super( ds );
    }

    @Override
    protected Logger log() { return LOG; }

    /**
     * Outcome of an {@link #upsert} call. {@link #WRITTEN} means the row is now
     * present (either INSERTed or UPDATEd); {@link #SKIPPED_STALE_SLUG_OWNER}
     * means the slug is already claimed by a different canonical_id and the
     * caller must NOT cascade dependent FK-bound writes (e.g.
     * {@code page_verification}, which would error with an FK violation
     * because the new canonical_id is not in {@code page_canonical_ids}).
     */
    public enum UpsertResult { WRITTEN, SKIPPED_STALE_SLUG_OWNER }

    /**
     * The desired state of one {@code page_canonical_ids} row. Groups the five values that
     * travel together through every branch of the upsert, so the branch helpers below take
     * one argument instead of five.
     */
    private record UpsertSpec( String canonicalId, String currentSlug, String title, String type, String cluster ) {}

    public UpsertResult upsert( final String canonicalId,
                                final String currentSlug,
                                final String title,
                                final String type,
                                final String cluster ) {
        final UpsertSpec spec = new UpsertSpec( canonicalId, currentSlug, title, type, cluster );
        try {
            return inTransaction( conn -> {
                final Optional< Row > existing = findByCanonicalId( conn, canonicalId );
                return existing.isEmpty()
                        ? insertNew( conn, spec )
                        : updateExisting( conn, spec, existing.get() );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "PageCanonicalIdsDao.upsert({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "upsert failed", e );
        }
    }

    /**
     * INSERT path — no row yet carries this canonical_id.
     *
     * <p>Before inserting, check whether the slug is already claimed by a different
     * canonical_id. This happens when the frontmatter canonical_id was changed after the DB
     * row was first written (data corruption / stale row), or when two pages share the same
     * slug. Attempting the INSERT would throw a unique-constraint violation with a noisy
     * stacktrace; instead we detect the conflict pre-emptively and warn cleanly.
     */
    private UpsertResult insertNew( final Connection conn, final UpsertSpec spec ) throws SQLException {
        final Optional< Row > slugOwner = findBySlug( conn, spec.currentSlug() );
        if ( slugOwner.isPresent() ) {
            final String ownerId = slugOwner.get().canonicalId();
            if ( ownerId.equals( spec.canonicalId() ) ) {
                // Already consistent — nothing to do (should be unreachable since
                // findByCanonicalId returned empty, but guard defensively).
                LOG.debug( "upsert({}, {}): slug already owned by same canonical_id — no-op",
                           spec.canonicalId(), spec.currentSlug() );
                return UpsertResult.WRITTEN;
            }
            warnStaleSlugOwner( "slug '{}' is already claimed by canonical_id '{}'", spec, ownerId );
            return UpsertResult.SKIPPED_STALE_SLUG_OWNER;
        }
        update( conn, "INSERT INTO page_canonical_ids " +
                "(canonical_id, current_slug, title, type, cluster) " +
                "VALUES (?, ?, ?, ?, ?)", ps -> {
            ps.setString( 1, spec.canonicalId() );
            ps.setString( 2, spec.currentSlug() );
            ps.setString( 3, spec.title() );
            ps.setString( 4, spec.type() );
            ps.setString( 5, spec.cluster() );
        } );
        return UpsertResult.WRITTEN;
    }

    /**
     * UPDATE path — this canonical_id already has a row. A slug change additionally records
     * slug history, after the same stale-owner guard the INSERT path applies.
     */
    private UpsertResult updateExisting( final Connection conn, final UpsertSpec spec, final Row prev )
            throws SQLException {
        if ( prev.currentSlug().equals( spec.currentSlug() ) ) {
            LOG.debug( "upsert({}, {}): canonical_id and slug unchanged — updating metadata only",
                       spec.canonicalId(), spec.currentSlug() );
        } else {
            // Mirror of the INSERT-branch slug-owner check: when this canonical_id already
            // exists in the DB but its slug is changing to one already claimed by a
            // *different* canonical_id, the UPDATE on current_slug would explode with
            // page_canonical_ids_current_slug_key. That fires a verbose PSQLException
            // stacktrace into catalina.out (seen 2026-05-15 boot — PaxosAndRaft). Detect
            // pre-emptively, WARN with the same recovery hint, and skip the write.
            final Optional< Row > slugOwner = findBySlug( conn, spec.currentSlug() );
            if ( slugOwner.isPresent() && !slugOwner.get().canonicalId().equals( spec.canonicalId() ) ) {
                warnStaleSlugOwner( "rename target slug '{}' is already claimed by canonical_id '{}'",
                                    spec, slugOwner.get().canonicalId() );
                return UpsertResult.SKIPPED_STALE_SLUG_OWNER;
            }
            if ( !slugHistoryRowExists( conn, spec.canonicalId(), prev.currentSlug() ) ) {
                update( conn, "INSERT INTO page_slug_history (canonical_id, previous_slug) " +
                        "VALUES (?, ?)", ps -> {
                    ps.setString( 1, spec.canonicalId() );
                    ps.setString( 2, prev.currentSlug() );
                } );
            }
        }
        update( conn, "UPDATE page_canonical_ids SET " +
                "current_slug = ?, title = ?, type = ?, cluster = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE canonical_id = ?", ps -> {
            ps.setString( 1, spec.currentSlug() );
            ps.setString( 2, spec.title() );
            ps.setString( 3, spec.type() );
            ps.setString( 4, spec.cluster() );
            ps.setString( 5, spec.canonicalId() );
        } );
        return UpsertResult.WRITTEN;
    }

    /** Shared stale-slug-owner warning: same recovery hint from both upsert branches. */
    private static void warnStaleSlugOwner( final String conflict, final UpsertSpec spec, final String ownerId ) {
        LOG.warn( "upsert({}, {}): " + conflict + ". Frontmatter and DB are out of sync — skipping DB "
                + "write so the in-memory projection continues cleanly. To fix: run "
                + "bin/db/one-shots/reconcile_page_canonical_ids.sh (or manually DELETE the stale row "
                + "from page_canonical_ids WHERE canonical_id='{}' AND current_slug='{}').",
                  spec.canonicalId(), spec.currentSlug(), spec.currentSlug(),
                  ownerId, ownerId, spec.currentSlug() );
    }

    private Optional< Row > findBySlug( final Connection c, final String slug ) throws SQLException {
        return queryOne( c, "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                "FROM page_canonical_ids WHERE current_slug = ?",
                ps -> ps.setString( 1, slug ), PageCanonicalIdsDao::readRow );
    }

    public Optional< Row > findByCanonicalId( final String canonicalId ) {
        try {
            return queryOne( "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                    "FROM page_canonical_ids WHERE canonical_id = ?",
                    ps -> ps.setString( 1, canonicalId ), PageCanonicalIdsDao::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "findByCanonicalId({}) failed: {}", canonicalId, e.getMessage() );
            return Optional.empty();
        }
    }

    private boolean slugHistoryRowExists( final Connection c, final String canonicalId,
                                           final String previousSlug ) throws SQLException {
        return queryOne( c, "SELECT 1 FROM page_slug_history WHERE canonical_id = ? AND previous_slug = ?",
                ps -> {
                    ps.setString( 1, canonicalId );
                    ps.setString( 2, previousSlug );
                }, rs -> Boolean.TRUE ).isPresent();
    }

    private Optional< Row > findByCanonicalId( final Connection c, final String id ) throws SQLException {
        return queryOne( c, "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                "FROM page_canonical_ids WHERE canonical_id = ?",
                ps -> ps.setString( 1, id ), PageCanonicalIdsDao::readRow );
    }

    public Optional< Row > findBySlug( final String slug ) {
        try {
            return queryOne( "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                    "FROM page_canonical_ids WHERE current_slug = ?",
                    ps -> ps.setString( 1, slug ), PageCanonicalIdsDao::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "findBySlug({}) failed: {}", slug, e.getMessage() );
            return Optional.empty();
        }
    }

    public List< Row > findAll() {
        try {
            return query( "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                    "FROM page_canonical_ids ORDER BY canonical_id",
                    ps -> {}, PageCanonicalIdsDao::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "findAll() failed: {}", e.getMessage() );
            return List.of();
        }
    }

    public List< String > slugHistory( final String canonicalId ) {
        try {
            return query( "SELECT previous_slug FROM page_slug_history " +
                    "WHERE canonical_id = ? ORDER BY renamed_at DESC",
                    ps -> ps.setString( 1, canonicalId ), rs -> rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            LOG.warn( "slugHistory({}) failed: {}", canonicalId, e.getMessage() );
            return List.of();
        }
    }

    public void delete( final String canonicalId ) {
        try {
            update( "DELETE FROM page_canonical_ids WHERE canonical_id = ?", ps -> ps.setString( 1, canonicalId ) );
        } catch ( final SQLException e ) {
            LOG.warn( "delete({}) failed: {}", canonicalId, e.getMessage() );
            throw new RuntimeException( "delete failed", e );
        }
    }

    private static Row readRow( final ResultSet rs ) throws SQLException {
        return new Row(
                rs.getString( "canonical_id" ),
                rs.getString( "current_slug" ),
                rs.getString( "title" ),
                rs.getString( "type" ),
                rs.getString( "cluster" ),
                rs.getTimestamp( "created_at" ).toInstant(),
                rs.getTimestamp( "updated_at" ).toInstant() );
    }

    public record Row(
            String canonicalId,
            String currentSlug,
            String title,
            String type,
            String cluster,
            Instant createdAt,
            Instant updatedAt
    ) {}
}

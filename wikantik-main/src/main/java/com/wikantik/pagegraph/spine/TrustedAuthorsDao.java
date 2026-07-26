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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny registry of authors whose verified pages are promoted from
 * {@code provisional} to {@code authoritative} confidence. Read-mostly:
 * the {@link com.wikantik.pagegraph.spine.ConfidenceComputer} hits
 * {@link #contains} on every rebuild, so we cache the set in memory and
 * refresh on writes.
 */
public class TrustedAuthorsDao extends SpineJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( TrustedAuthorsDao.class );

    private final AtomicReference< Set< String > > cache = new AtomicReference<>( Set.of() );

    public TrustedAuthorsDao( final DataSource ds ) {
        super( ds );
        refresh();
    }

    @Override
    protected Logger log() { return LOG; }

    /** Snapshot of all trusted login_names. Always returns the cached set. */
    public Set< String > all() {
        return cache.get();
    }

    /** True iff {@code loginName} is currently in the trusted-authors set. */
    public boolean contains( final String loginName ) {
        if ( loginName == null || loginName.isBlank() ) {
            return false;
        }
        return cache.get().contains( loginName.trim() );
    }

    /** Insert or update; refreshes the in-memory cache. */
    public void upsert( final String loginName, final String notes ) {
        if ( loginName == null || loginName.isBlank() ) {
            throw new IllegalArgumentException( "loginName required" );
        }
        try {
            inTransaction( conn -> {
                final boolean exists = queryOne( conn, "SELECT 1 FROM trusted_authors WHERE login_name = ?",
                        ps -> ps.setString( 1, loginName ), rs -> Boolean.TRUE ).isPresent();
                if ( exists ) {
                    update( conn, "UPDATE trusted_authors SET notes = ? WHERE login_name = ?", ps -> {
                        ps.setString( 1, notes );
                        ps.setString( 2, loginName );
                    } );
                } else {
                    update( conn, "INSERT INTO trusted_authors (login_name, notes) VALUES (?, ?)", ps -> {
                        ps.setString( 1, loginName );
                        ps.setString( 2, notes );
                    } );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "TrustedAuthorsDao.upsert({}) failed: {}", loginName, e.getMessage(), e );
            throw new RuntimeException( "trusted_authors upsert failed", e );
        }
        refresh();
    }

    /** Remove a trusted author; refreshes the in-memory cache. */
    public void remove( final String loginName ) {
        try {
            update( "DELETE FROM trusted_authors WHERE login_name = ?", ps -> ps.setString( 1, loginName ) );
        } catch ( final SQLException e ) {
            LOG.warn( "TrustedAuthorsDao.remove({}) failed: {}", loginName, e.getMessage() );
            throw new RuntimeException( "trusted_authors remove failed", e );
        }
        refresh();
    }

    /** Force a reload from the DB. Called automatically after every write. */
    public void refresh() {
        final List< String > rows;
        try {
            rows = query( "SELECT login_name FROM trusted_authors", SqlBinder.NONE, rs -> rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            LOG.warn( "TrustedAuthorsDao.refresh() failed (cache unchanged): {}", e.getMessage() );
            return;
        }
        cache.set( Set.copyOf( rows ) );
    }
}

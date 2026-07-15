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
package com.wikantik.connectors.state;

import com.wikantik.api.connectors.SyncCursor;
import com.wikantik.api.connectors.SyncStateStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 {@link SyncStateStore}. {@code acl_refs} is a JSON-array string (TEXT). */
public final class JdbcSyncStateStore implements SyncStateStore {

    private static final Logger LOG = LogManager.getLogger( JdbcSyncStateStore.class );
    private final DataSource ds;

    public JdbcSyncStateStore( final DataSource ds ) { this.ds = ds; }

    @Override public Optional< SyncCursor > loadCursor( final String id ) {
        return query1( "SELECT cursor FROM connector_sync_state WHERE connector_id=?", id,
            rs -> new SyncCursor( rs.getString( 1 ) ) );
    }

    @Override public void saveCursor( final String id, final SyncCursor cursor ) {
        // Portable upsert: UPDATE first, INSERT only if no row existed. H2 2.4.240 in PostgreSQL
        // mode does not support "ON CONFLICT ... DO UPDATE" (only DO NOTHING) — verified empirically —
        // so this avoids dialect-specific syntax entirely and works identically against H2 and PostgreSQL.
        final String value = cursor == null ? null : cursor.value();
        try ( Connection c = ds.getConnection() ) {
            int updated;
            try ( PreparedStatement ps = c.prepareStatement(
                    "UPDATE connector_sync_state SET cursor=?, last_run=now(), status='ok' WHERE connector_id=?" ) ) {
                ps.setString( 1, value );
                ps.setString( 2, id );
                updated = ps.executeUpdate();
            }
            if ( updated == 0 ) {
                try ( PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO connector_sync_state (connector_id, cursor, last_run, status) VALUES (?,?,now(),'ok')" ) ) {
                    ps.setString( 1, id );
                    ps.setString( 2, value );
                    ps.executeUpdate();
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "saveCursor failed for connector '{}': {}", id, e.getMessage() );
        }
    }

    @Override public Optional< String > syncedHash( final String id, final String uri ) {
        return query1( "SELECT content_hash FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            id, uri, rs -> rs.getString( 1 ) );
    }

    @Override public void recordSynced( final String id, final String uri, final String hash,
                                        final String pageName, final List< String > aclRefs ) {
        // Same portable UPDATE-then-INSERT upsert as saveCursor (see comment there); also preserves
        // first_synced on update, which an ON CONFLICT ... DO UPDATE would need to special-case anyway.
        final String json = toJsonArray( aclRefs );
        try ( Connection c = ds.getConnection() ) {
            int updated;
            try ( PreparedStatement ps = c.prepareStatement(
                    "UPDATE connector_synced_item SET content_hash=?, page_name=?, acl_refs=?, last_synced=now() "
                    + "WHERE connector_id=? AND source_uri=?" ) ) {
                ps.setString( 1, hash );
                ps.setString( 2, pageName );
                ps.setString( 3, json );
                ps.setString( 4, id );
                ps.setString( 5, uri );
                updated = ps.executeUpdate();
            }
            if ( updated == 0 ) {
                try ( PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO connector_synced_item (connector_id, source_uri, content_hash, page_name, acl_refs, first_synced, last_synced) "
                        + "VALUES (?,?,?,?,?,now(),now())" ) ) {
                    ps.setString( 1, id );
                    ps.setString( 2, uri );
                    ps.setString( 3, hash );
                    ps.setString( 4, pageName );
                    ps.setString( 5, json );
                    ps.executeUpdate();
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "recordSynced failed for connector '{}' uri '{}': {}", id, uri, e.getMessage() );
        }
    }

    @Override public Optional< String > pageNameFor( final String id, final String uri ) {
        return query1( "SELECT page_name FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            id, uri, rs -> rs.getString( 1 ) );
    }

    @Override public List< String > knownUris( final String id ) {
        final List< String > out = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT source_uri FROM connector_synced_item WHERE connector_id=?" ) ) {
            ps.setString( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) { while ( rs.next() ) out.add( rs.getString( 1 ) ); }
        } catch ( final SQLException e ) {
            LOG.warn( "knownUris failed for connector '{}': {}", id, e.getMessage() );
        }
        return out;
    }

    @Override public void removeSynced( final String id, final String uri ) {
        exec( "DELETE FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            ps -> { ps.setString( 1, id ); ps.setString( 2, uri ); } );
    }

    @Override public void purge( final String id ) {
        try ( Connection c = ds.getConnection() ) {
            try ( PreparedStatement ps = c.prepareStatement( "DELETE FROM connector_synced_item WHERE connector_id=?" ) ) {
                ps.setString( 1, id );
                ps.executeUpdate();
            }
            try ( PreparedStatement ps = c.prepareStatement( "DELETE FROM connector_sync_state WHERE connector_id=?" ) ) {
                ps.setString( 1, id );
                ps.executeUpdate();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "purge failed for connector '{}': {}", id, e.getMessage() );
        }
    }

    @Override public List< SyncedItem > items( final String id ) {
        final List< SyncedItem > out = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT source_uri, page_name, last_synced FROM connector_synced_item WHERE connector_id=? ORDER BY page_name" ) ) {
            ps.setString( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( new SyncedItem( rs.getString( 1 ), rs.getString( 2 ), rs.getTimestamp( 3 ).toInstant() ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "items failed for connector '{}': {}", id, e.getMessage() );
        }
        return out;
    }

    // --- helpers ---
    private interface Row< T > { T map( ResultSet rs ) throws SQLException; }
    private interface Bind { void bind( PreparedStatement ps ) throws SQLException; }

    private < T > Optional< T > query1( final String sql, final String a, final Row< T > row ) {
        return query1( sql, a, null, row );
    }
    private < T > Optional< T > query1( final String sql, final String a, final String b, final Row< T > row ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, a );
            if ( b != null ) ps.setString( 2, b );
            try ( ResultSet rs = ps.executeQuery() ) { return rs.next() ? Optional.ofNullable( row.map( rs ) ) : Optional.empty(); }
        } catch ( final SQLException e ) {
            LOG.warn( "query failed [{}]: {}", sql, e.getMessage() );
            return Optional.empty();
        }
    }
    private void exec( final String sql, final Bind bind ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            bind.bind( ps );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "update failed [{}]: {}", sql, e.getMessage() );
        }
    }
    static String toJsonArray( final List< String > values ) {
        if ( values == null || values.isEmpty() ) return "[]";
        final StringBuilder sb = new StringBuilder( "[" );
        for ( int i = 0; i < values.size(); i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( '"' ).append( values.get( i ).replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) ).append( '"' );
        }
        return sb.append( ']' ).toString();
    }
}

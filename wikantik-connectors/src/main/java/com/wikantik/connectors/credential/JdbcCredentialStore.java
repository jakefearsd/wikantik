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
package com.wikantik.connectors.credential;

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.util.AesGcmCipher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 {@link CredentialStore}; secrets stored as AES-GCM tokens. Disabled when cipher is null. */
public final class JdbcCredentialStore implements CredentialStore {

    private static final Logger LOG = LogManager.getLogger( JdbcCredentialStore.class );
    private final DataSource ds;
    private final AesGcmCipher cipher;   // null ⇒ disabled

    public JdbcCredentialStore( final DataSource ds, final AesGcmCipher cipher ) {
        this.ds = ds;
        this.cipher = cipher;
    }

    @Override public boolean enabled() { return cipher != null; }

    @Override
    public void put( final String connectorId, final String name, final String secret ) {
        if ( cipher == null ) { LOG.warn( "credential put refused for {}/{}: no master key configured", connectorId, name ); return; }
        final String token = cipher.encrypt( secret );
        try ( Connection c = ds.getConnection() ) {
            try ( PreparedStatement up = c.prepareStatement(
                    "UPDATE connector_credentials SET ciphertext=?, updated_at=now() WHERE connector_id=? AND credential_name=?" ) ) {
                up.setString( 1, token ); up.setString( 2, connectorId ); up.setString( 3, name );
                if ( up.executeUpdate() == 0 ) {
                    try ( PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO connector_credentials (connector_id, credential_name, ciphertext) VALUES (?,?,?)" ) ) {
                        ins.setString( 1, connectorId ); ins.setString( 2, name ); ins.setString( 3, token );
                        ins.executeUpdate();
                    }
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "credential put failed for {}/{}: {}", connectorId, name, e.getMessage() );  // no secret
        }
    }

    @Override
    public Optional< String > get( final String connectorId, final String name ) {
        if ( cipher == null ) { LOG.warn( "credential get refused for {}/{}: no master key configured", connectorId, name ); return Optional.empty(); }
        String token = null;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT ciphertext FROM connector_credentials WHERE connector_id=? AND credential_name=?" ) ) {
            ps.setString( 1, connectorId ); ps.setString( 2, name );
            try ( ResultSet rs = ps.executeQuery() ) { if ( rs.next() ) token = rs.getString( 1 ); }
        } catch ( final SQLException e ) {
            LOG.warn( "credential get failed for {}/{}: {}", connectorId, name, e.getMessage() );
            return Optional.empty();
        }
        if ( token == null ) return Optional.empty();
        try {
            return Optional.of( cipher.decrypt( token ) );
        } catch ( final Exception e ) {   // GCM tag mismatch / wrong key / corrupt token
            LOG.warn( "credential decrypt failed for {}/{}: {}", connectorId, name, e.getMessage() );  // no plaintext/ciphertext
            return Optional.empty();
        }
    }

    @Override
    public List< String > list( final String connectorId ) {
        final List< String > out = new ArrayList<>();
        if ( cipher == null ) return out;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT credential_name FROM connector_credentials WHERE connector_id=?" ) ) {
            ps.setString( 1, connectorId );
            try ( ResultSet rs = ps.executeQuery() ) { while ( rs.next() ) out.add( rs.getString( 1 ) ); }
        } catch ( final SQLException e ) {
            LOG.warn( "credential list failed for {}: {}", connectorId, e.getMessage() );
        }
        return out;
    }

    @Override
    public void delete( final String connectorId, final String name ) {
        if ( cipher == null ) { LOG.warn( "credential delete refused for {}/{}: no master key configured", connectorId, name ); return; }
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "DELETE FROM connector_credentials WHERE connector_id=? AND credential_name=?" ) ) {
            ps.setString( 1, connectorId ); ps.setString( 2, name );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "credential delete failed for {}/{}: {}", connectorId, name, e.getMessage() );
        }
    }
}

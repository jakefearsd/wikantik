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
import com.wikantik.jdbc.Jdbc;
import com.wikantik.util.AesGcmCipher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL {@link CredentialStore}; secrets stored as AES-GCM tokens. Disabled when cipher is null. */
public final class JdbcCredentialStore implements CredentialStore {

    private static final Logger LOG = LogManager.getLogger( JdbcCredentialStore.class );
    private final Jdbc jdbc;
    private final AesGcmCipher cipher;   // null ⇒ disabled

    public JdbcCredentialStore( final DataSource ds, final AesGcmCipher cipher ) {
        this.jdbc = new Jdbc( ds );
        this.cipher = cipher;
    }

    @Override public boolean enabled() { return cipher != null; }

    @Override
    public void put( final String connectorId, final String name, final String secret ) {
        if ( cipher == null ) { LOG.warn( "credential put refused for {}/{}: no master key configured", connectorId, name ); return; }
        final String token = cipher.encrypt( secret );
        try {
            final int updated = jdbc.update(
                "UPDATE connector_credentials SET ciphertext=?, updated_at=now() WHERE connector_id=? AND credential_name=?",
                ps -> { ps.setString( 1, token ); ps.setString( 2, connectorId ); ps.setString( 3, name ); } );
            if ( updated == 0 ) {
                jdbc.update(
                    "INSERT INTO connector_credentials (connector_id, credential_name, ciphertext) VALUES (?,?,?)",
                    ps -> { ps.setString( 1, connectorId ); ps.setString( 2, name ); ps.setString( 3, token ); } );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "credential put failed for {}/{}: {}", connectorId, name, e.getMessage() );  // no secret
        }
    }

    @Override
    public Optional< String > get( final String connectorId, final String name ) {
        if ( cipher == null ) { LOG.warn( "credential get refused for {}/{}: no master key configured", connectorId, name ); return Optional.empty(); }
        String token = null;
        try {
            token = jdbc.queryOne(
                "SELECT ciphertext FROM connector_credentials WHERE connector_id=? AND credential_name=?",
                ps -> { ps.setString( 1, connectorId ); ps.setString( 2, name ); },
                rs -> rs.getString( 1 ) ).orElse( null );
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
        if ( cipher == null ) return new ArrayList<>();
        try {
            return new ArrayList<>( jdbc.query(
                "SELECT credential_name FROM connector_credentials WHERE connector_id=?",
                ps -> ps.setString( 1, connectorId ), rs -> rs.getString( 1 ) ) );
        } catch ( final SQLException e ) {
            LOG.warn( "credential list failed for {}: {}", connectorId, e.getMessage() );
            return new ArrayList<>();
        }
    }

    @Override
    public void delete( final String connectorId, final String name ) {
        if ( cipher == null ) { LOG.warn( "credential delete refused for {}/{}: no master key configured", connectorId, name ); return; }
        try {
            jdbc.update( "DELETE FROM connector_credentials WHERE connector_id=? AND credential_name=?",
                ps -> { ps.setString( 1, connectorId ); ps.setString( 2, name ); } );
        } catch ( final SQLException e ) {
            LOG.warn( "credential delete failed for {}/{}: {}", connectorId, name, e.getMessage() );
        }
    }
}

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

import com.wikantik.util.AesGcmCipher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JdbcCredentialStoreTest {

    private DataSource ds;
    private AesGcmCipher cipher;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:creds;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "DELETE FROM connector_credentials WHERE 1=1" );  // idempotent across methods
        } catch ( final Exception ignore ) { /* table may not exist yet */ }
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_credentials (connector_id VARCHAR NOT NULL, credential_name VARCHAR NOT NULL, ciphertext VARCHAR NOT NULL, created_at TIMESTAMP WITH TIME ZONE DEFAULT now(), updated_at TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, credential_name))" );
            s.execute( "DELETE FROM connector_credentials" );
        }
        byte[] k = new byte[32]; new SecureRandom().nextBytes( k );
        cipher = new AesGcmCipher( AesGcmCipher.keyFromBase64( Base64.getEncoder().encodeToString( k ) ) );
    }

    @Test void putGetRoundTripsAndStoresCiphertextNotPlaintext() throws Exception {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, cipher );
        assertTrue( store.enabled() );
        store.put( "gh1", "token", "ghp_secret_value" );
        assertEquals( "ghp_secret_value", store.get( "gh1", "token" ).orElseThrow() );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery( "SELECT ciphertext FROM connector_credentials WHERE connector_id='gh1'" ) ) {
            rs.next();
            assertNotEquals( "ghp_secret_value", rs.getString( 1 ), "must store ciphertext, not plaintext" );
        }
    }

    @Test void listReturnsNamesAndDeleteRemoves() {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, cipher );
        store.put( "gh1", "token", "a" );
        store.put( "gh1", "webhook", "b" );
        assertEquals( List.of( "token", "webhook" ), store.list( "gh1" ).stream().sorted().toList() );
        store.delete( "gh1", "token" );
        assertEquals( List.of( "webhook" ), store.list( "gh1" ) );
    }

    @Test void disabledWhenNoCipher() {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, null );
        assertFalse( store.enabled() );
        store.put( "gh1", "token", "x" );                 // refuses (no throw)
        assertTrue( store.get( "gh1", "token" ).isEmpty() );
        assertTrue( store.list( "gh1" ).isEmpty() );
        assertDoesNotThrow( () -> store.delete( "gh1", "token" ) );   // refuses w/o touching the DB
    }

    @Test void putOverwritesExistingCredential_noDuplicateRow() throws Exception {
        JdbcCredentialStore store = new JdbcCredentialStore( ds, cipher );
        store.put( "c", "k", "v1" );
        store.put( "c", "k", "v2" );

        assertEquals( "v2", store.get( "c", "k" ).orElseThrow() );
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement( "SELECT count(*) FROM connector_credentials WHERE connector_id=? AND credential_name=?" ) ) {
            ps.setString( 1, "c" ); ps.setString( 2, "k" );
            try ( var rs = ps.executeQuery() ) {
                rs.next();
                assertEquals( 1, rs.getInt( 1 ), "second put() must overwrite, not duplicate, the row" );
            }
        }
    }
}

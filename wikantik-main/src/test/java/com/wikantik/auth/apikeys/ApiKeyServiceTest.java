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
package com.wikantik.auth.apikeys;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiKeyService}, backed by an in-memory H2 database
 * configured to speak PostgreSQL dialect so the production SQL runs unchanged.
 */
class ApiKeyServiceTest {

    private DataSource dataSource;
    private ApiKeyService service;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:apikeys_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        ds.setUser( "sa" );
        ds.setPassword( "" );
        this.dataSource = ds;

        try ( final Connection conn = ds.getConnection();
              final Statement st = conn.createStatement() ) {
            st.execute( """
                CREATE TABLE api_keys (
                    id              SERIAL       PRIMARY KEY,
                    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
                    principal_login VARCHAR(100) NOT NULL,
                    label           VARCHAR(200),
                    scope           VARCHAR(20)  NOT NULL,
                    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
                    created_by      VARCHAR(100),
                    last_used_at    TIMESTAMP,
                    revoked_at      TIMESTAMP,
                    revoked_by      VARCHAR(100)
                )
                """ );
        }
        this.service = new ApiKeyService( dataSource );
    }

    @Test
    void generatedTokenVerifiesAndCarriesPrincipal() {
        final ApiKeyService.Generated g = service.generate(
                "alice", "laptop", ApiKeyService.Scope.TOOLS, "admin" );

        assertTrue( g.plaintext().startsWith( ApiKeyService.TOKEN_PREFIX ),
                "Token should start with wkk_ prefix: " + g.plaintext() );
        assertTrue( g.plaintext().length() > 40, "Token should carry ~256 bits of entropy" );

        final Optional< ApiKeyService.Record > verified = service.verify( g.plaintext() );
        assertTrue( verified.isPresent() );
        assertEquals( "alice", verified.get().principalLogin() );
        assertEquals( ApiKeyService.Scope.TOOLS, verified.get().scope() );
    }

    @Test
    void verifyReturnsEmptyForUnknownToken() {
        service.generate( "alice", null, ApiKeyService.Scope.ALL, "admin" );
        assertTrue( service.verify( "wkk_definitely-not-a-real-token" ).isEmpty() );
        assertTrue( service.verify( null ).isEmpty() );
        assertTrue( service.verify( "" ).isEmpty() );
    }

    @Test
    void verifyReturnsEmptyAfterRevoke() {
        final ApiKeyService.Generated g = service.generate(
                "alice", "laptop", ApiKeyService.Scope.ALL, "admin" );
        assertTrue( service.verify( g.plaintext() ).isPresent() );

        assertTrue( service.revoke( g.record().id(), "admin" ) );
        assertTrue( service.verify( g.plaintext() ).isEmpty(),
                "Revoked key must no longer verify" );
    }

    @Test
    void revokeIsIdempotent() {
        final ApiKeyService.Generated g = service.generate(
                "alice", null, ApiKeyService.Scope.MCP, "admin" );
        assertTrue( service.revoke( g.record().id(), "admin" ) );
        assertFalse( service.revoke( g.record().id(), "admin" ),
                "Second revoke must report no-op" );
        assertFalse( service.revoke( 99999, "admin" ), "Unknown id must report no-op" );
    }

    @Test
    void listReturnsActiveAndRevokedNewestFirst() throws Exception {
        final ApiKeyService.Generated older = service.generate(
                "alice", "old", ApiKeyService.Scope.TOOLS, "admin" );
        // Small delay so created_at ordering is deterministic.
        Thread.sleep( 10 );
        final ApiKeyService.Generated newer = service.generate(
                "bob", "new", ApiKeyService.Scope.MCP, "admin" );

        service.revoke( older.record().id(), "admin" );

        final List< ApiKeyService.Record > all = service.list();
        assertEquals( 2, all.size() );
        assertEquals( newer.record().id(), all.get( 0 ).id(),
                "Most recent key listed first" );
        assertTrue( all.get( 0 ).isActive() );
        assertFalse( all.get( 1 ).isActive() );
    }

    @Test
    void generateRejectsBlankPrincipal() {
        assertThrows( IllegalArgumentException.class,
                () -> service.generate( "", "label", ApiKeyService.Scope.ALL, "admin" ) );
        assertThrows( IllegalArgumentException.class,
                () -> service.generate( null, "label", ApiKeyService.Scope.ALL, "admin" ) );
    }

    @Test
    void scopeMatchingAllowsAllForAnyRequired() {
        assertTrue( ApiKeyService.Scope.ALL.matches( ApiKeyService.Scope.TOOLS ) );
        assertTrue( ApiKeyService.Scope.ALL.matches( ApiKeyService.Scope.MCP ) );
        assertTrue( ApiKeyService.Scope.TOOLS.matches( ApiKeyService.Scope.TOOLS ) );
        assertFalse( ApiKeyService.Scope.TOOLS.matches( ApiKeyService.Scope.MCP ) );
        assertFalse( ApiKeyService.Scope.MCP.matches( ApiKeyService.Scope.TOOLS ) );
    }

    @Test
    void scopeFromWireAcceptsKnownValuesAndRejectsGarbage() {
        assertEquals( ApiKeyService.Scope.TOOLS, ApiKeyService.Scope.fromWire( "tools" ) );
        assertEquals( ApiKeyService.Scope.MCP, ApiKeyService.Scope.fromWire( "MCP" ) );
        assertEquals( ApiKeyService.Scope.ALL, ApiKeyService.Scope.fromWire( null ) );
        assertThrows( IllegalArgumentException.class,
                () -> ApiKeyService.Scope.fromWire( "bogus" ) );
    }

    @Test
    void sha256HexIsDeterministicAndConstantLength() {
        final String a = ApiKeyService.sha256Hex( "wkk_abc" );
        final String b = ApiKeyService.sha256Hex( "wkk_abc" );
        assertEquals( a, b );
        assertEquals( 64, a.length() );
        assertNotEquals( a, ApiKeyService.sha256Hex( "wkk_abcd" ) );
    }

    @Test
    void verifyUpdatesLastUsedTimestamp() {
        final ApiKeyService.Generated g = service.generate(
                "alice", null, ApiKeyService.Scope.TOOLS, "admin" );
        assertNull( g.record().lastUsedAt() );

        service.verify( g.plaintext() );

        final ApiKeyService.Record reread = service.list().stream()
                .filter( r -> r.id() == g.record().id() )
                .findFirst().orElseThrow();
        assertNotNull( reread.lastUsedAt(),
                "verify() must touch last_used_at so operators can see idle keys" );
    }
}

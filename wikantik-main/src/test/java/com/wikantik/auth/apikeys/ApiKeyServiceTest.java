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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    void verifyUpdatesLastUsedTimestamp() throws InterruptedException {
        final ApiKeyService.Generated g = service.generate(
                "alice", null, ApiKeyService.Scope.TOOLS, "admin" );
        assertNull( g.record().lastUsedAt() );

        service.verify( g.plaintext() );

        // Touch runs async on the first (cache-miss) verify — wait briefly for the executor.
        final long deadline = System.currentTimeMillis() + 2_000;
        ApiKeyService.Record reread = null;
        while ( System.currentTimeMillis() < deadline ) {
            reread = service.list().stream()
                    .filter( r -> r.id() == g.record().id() )
                    .findFirst().orElseThrow();
            if ( reread.lastUsedAt() != null ) {
                break;
            }
            Thread.sleep( 50 );
        }
        assertNotNull( reread.lastUsedAt(),
                "verify() must touch last_used_at so operators can see idle keys" );
    }

    // -----------------------------------------------------------------------
    // Caching tests
    // -----------------------------------------------------------------------

    /** Wraps a DataSource and counts how many times getConnection() is called. */
    private static class CountingDataSource implements DataSource {

        private final DataSource delegate;
        final AtomicInteger connectionCount = new AtomicInteger();

        CountingDataSource( final DataSource delegate ) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionCount.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection( final String username, final String password ) throws SQLException {
            connectionCount.incrementAndGet();
            return delegate.getConnection( username, password );
        }

        @Override public <T> T unwrap( final Class<T> iface ) throws SQLException { return delegate.unwrap( iface ); }
        @Override public boolean isWrapperFor( final Class<?> iface ) throws SQLException { return delegate.isWrapperFor( iface ); }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter( final java.io.PrintWriter out ) throws SQLException { delegate.setLogWriter( out ); }
        @Override public void setLoginTimeout( final int seconds ) throws SQLException { delegate.setLoginTimeout( seconds ); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger( java.util.logging.Logger.GLOBAL_LOGGER_NAME ); }
    }

    @Test
    void verify_isCachedAcrossCalls() {
        // Use a fresh counting wrapper over the shared H2 DataSource.
        final CountingDataSource counting = new CountingDataSource( dataSource );
        final ApiKeyService svc = new ApiKeyService( counting );

        final ApiKeyService.Generated g = svc.generate(
                "carol", "cache-test", ApiKeyService.Scope.ALL, "admin" );

        // First call: cache miss — SELECT runs, touch queued async.
        final Optional< ApiKeyService.Record > first = svc.verify( g.plaintext() );
        assertTrue( first.isPresent() );
        assertEquals( g.record().id(), first.get().id() );

        // Snapshot cache stats after first call.
        final long missAfterFirst = svc.verifyCacheStats().missCount();
        final long hitAfterFirst  = svc.verifyCacheStats().hitCount();
        assertEquals( 1, missAfterFirst, "First call should be a cache miss" );
        assertEquals( 0, hitAfterFirst,  "No hits yet" );

        // Second call: cache hit — no new SELECT connection on the request thread.
        final Optional< ApiKeyService.Record > second = svc.verify( g.plaintext() );
        assertTrue( second.isPresent() );
        assertEquals( g.record().id(), second.get().id() );

        assertEquals( 1, svc.verifyCacheStats().missCount(), "Miss count unchanged after second call" );
        assertEquals( 1, svc.verifyCacheStats().hitCount(),  "Second call must be a cache hit" );
    }

    @Test
    void verify_unknownTokenCachedAsEmpty() {
        final CountingDataSource counting = new CountingDataSource( dataSource );
        final ApiKeyService svc = new ApiKeyService( counting );

        // Both calls return empty; second should be a cache hit.
        assertTrue( svc.verify( "wkk_does-not-exist" ).isEmpty() );
        assertTrue( svc.verify( "wkk_does-not-exist" ).isEmpty() );

        assertEquals( 1, svc.verifyCacheStats().missCount(), "Only one DB lookup for unknown token" );
        assertEquals( 1, svc.verifyCacheStats().hitCount(),  "Second call is a cache hit" );
    }

    // ----- revokeAllForPrincipal tests -----

    @Test
    void revokeAllForPrincipal_revokesAllActiveKeysForThatPrincipal() {
        final ApiKeyService.Generated g1 = service.generate( "bob", "key1", ApiKeyService.Scope.ALL, "admin" );
        final ApiKeyService.Generated g2 = service.generate( "bob", "key2", ApiKeyService.Scope.MCP, "admin" );
        // A key for a different user — must NOT be revoked.
        final ApiKeyService.Generated g3 = service.generate( "carol", "key3", ApiKeyService.Scope.ALL, "admin" );

        // Both of bob's keys are initially active.
        assertTrue( service.verify( g1.plaintext() ).isPresent(), "bob key1 should be active before revoke" );
        assertTrue( service.verify( g2.plaintext() ).isPresent(), "bob key2 should be active before revoke" );

        service.revokeAllForPrincipal( "bob" );

        // Bob's keys are now revoked.
        assertTrue( service.verify( g1.plaintext() ).isEmpty(), "bob key1 should be revoked" );
        assertTrue( service.verify( g2.plaintext() ).isEmpty(), "bob key2 should be revoked" );

        // Carol's key is unaffected.
        assertTrue( service.verify( g3.plaintext() ).isPresent(), "carol key3 must remain active" );
    }

    @Test
    void revokeAllForPrincipal_noKeysForPrincipal_isNoOp() {
        // Should not throw even when no rows match.
        assertDoesNotThrow( () -> service.revokeAllForPrincipal( "nobody" ) );
    }

    @Test
    void revokeAllForPrincipal_alreadyRevokedKeysAreIgnored() {
        final ApiKeyService.Generated g = service.generate( "dan", "key1", ApiKeyService.Scope.ALL, "admin" );
        service.revoke( g.record().id(), "admin" );

        // Should not throw when all keys are already revoked.
        assertDoesNotThrow( () -> service.revokeAllForPrincipal( "dan" ) );
    }

    @Test
    void listByPrincipalReturnsOnlyThatPrincipalsActiveKeysNewestFirst() {
        final ApiKeyService.Generated bob1 = service.generate( "bob", "k1", ApiKeyService.Scope.ALL, "admin" );
        service.generate( "bob", "k2", ApiKeyService.Scope.MCP, "admin" );
        service.generate( "carol", "k3", ApiKeyService.Scope.ALL, "admin" );
        service.revoke( bob1.record().id(), "admin" );            // k1 now revoked

        final java.util.List< ApiKeyService.Record > bobKeys = service.listByPrincipal( "bob" );
        assertEquals( 1, bobKeys.size(), "revoked k1 excluded, carol excluded" );
        assertEquals( "k2", bobKeys.get( 0 ).label() );
        assertEquals( "bob", bobKeys.get( 0 ).principalLogin() );
        assertTrue( service.listByPrincipal( "nobody" ).isEmpty() );
    }

    @Test
    void findByIdReturnsRecordOrEmpty() {
        final ApiKeyService.Generated g = service.generate( "dan", "laptop", ApiKeyService.Scope.TOOLS, "admin" );
        final Optional< ApiKeyService.Record > found = service.findById( g.record().id() );
        assertTrue( found.isPresent() );
        assertEquals( "dan", found.get().principalLogin() );
        assertEquals( ApiKeyService.Scope.TOOLS, found.get().scope() );
        assertTrue( service.findById( 999_999 ).isEmpty() );
    }
}

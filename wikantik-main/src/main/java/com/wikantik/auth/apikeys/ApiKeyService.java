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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DB-backed store for API keys used by the MCP and OpenAPI tool servers.
 *
 * <p>Each key is bound to a Wikantik principal ({@code login_name}). The
 * access filters resolve an incoming Bearer token by SHA-256 hashing it and
 * looking it up in {@code api_keys}; on match they install that principal on
 * the request so JAAS/ACL enforcement downstream behaves exactly as it would
 * for that user's interactive session.
 *
 * <p>Tokens are generated as {@code wkk_} + 32 random bytes, base64url
 * encoded. Plaintext is shown to the operator once at creation time and is
 * never persisted — only the SHA-256 hash is stored. SHA-256 is sufficient
 * because the token carries 256 bits of entropy; a slow hash (bcrypt) would
 * add cost without security benefit against a brute-force attacker.
 */
public class ApiKeyService {

    private static final Logger LOG = LogManager.getLogger( ApiKeyService.class );

    /** Prefix for generated plaintext tokens — makes secret-scanning and debugging easier. */
    public static final String TOKEN_PREFIX = "wkk_";

    private static final String TABLE = "api_keys";
    private static final int TOKEN_BYTES = 32;

    private final DataSource dataSource;
    private final SecureRandom rng = new SecureRandom();

    /** Short TTL: a revoked key keeps working at most this long. (Operator chose short-TTL-only.) */
    private static final long VERIFY_TTL_SECONDS = 60L;

    private final Cache< String, Optional< Record > > verifyCache = Caffeine.newBuilder()
            .expireAfterWrite( Duration.ofSeconds( VERIFY_TTL_SECONDS ) )
            .maximumSize( 10_000 )
            .recordStats()
            .build();

    /** Off-request-thread last_used_at writer so the metadata UPDATE never blocks a worker. */
    private final ExecutorService touchExecutor = Executors.newSingleThreadExecutor( r -> {
        final Thread t = new Thread( r, "apikey-touch" );
        t.setDaemon( true );
        return t;
    } );

    /**
     * Reverse index: {@code id → key_hash} so {@link #revoke} can evict the specific
     * cache entry and take effect immediately rather than waiting for TTL expiry.
     * Populated lazily on cache miss; entries survive until the verifyCache evicts them.
     */
    private final ConcurrentHashMap< Integer, String > idToHash = new ConcurrentHashMap<>();

    public ApiKeyService( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /** Scopes a generated key can be restricted to. */
    public enum Scope {
        MCP( "mcp" ),
        TOOLS( "tools" ),
        ALL( "all" );

        private final String wire;
        Scope( final String wire ) { this.wire = wire; }
        public String wire() { return wire; }
        public boolean matches( final Scope required ) {
            return this == ALL || this == required;
        }
        public static Scope fromWire( final String wire ) {
            if ( wire == null ) return ALL;
            for ( final Scope s : values() ) {
                if ( s.wire.equalsIgnoreCase( wire ) ) return s;
            }
            throw new IllegalArgumentException( "Unknown scope: " + wire );
        }
    }

    /** A key record as stored (no plaintext). */
    public record Record(
            int id,
            String keyHash,
            String principalLogin,
            String label,
            Scope scope,
            Instant createdAt,
            String createdBy,
            Instant lastUsedAt,
            Instant revokedAt,
            String revokedBy
    ) {
        public boolean isActive() { return revokedAt == null; }
    }

    /** Result of a successful {@link #generate} call: the plaintext token + its stored record. */
    public record Generated( String plaintext, Record record ) { }

    /**
     * Generates a new random token, persists its hash, and returns both the
     * plaintext (for one-time display to the operator) and the stored record.
     */
    public Generated generate( final String principalLogin,
                               final String label,
                               final Scope scope,
                               final String createdBy ) {
        if ( principalLogin == null || principalLogin.isBlank() ) {
            throw new IllegalArgumentException( "principalLogin is required" );
        }
        final String plaintext = newToken();
        final String hash = sha256Hex( plaintext );
        final Instant createdAt = Instant.now();
        final String sql = "INSERT INTO " + TABLE
                + " (key_hash, principal_login, label, scope, created_at, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, hash );
            ps.setString( 2, principalLogin );
            ps.setString( 3, label );
            ps.setString( 4, scope.wire() );
            ps.setTimestamp( 5, Timestamp.from( createdAt ) );
            ps.setString( 6, createdBy );
            ps.executeUpdate();
            try ( ResultSet rs = ps.getGeneratedKeys() ) {
                if ( !rs.next() ) {
                    throw new SQLException( "INSERT yielded no generated key" );
                }
                final int id = rs.getInt( 1 );
                final Record record = new Record( id, hash, principalLogin, label,
                        scope, createdAt, createdBy, null, null, null );
                return new Generated( plaintext, record );
            }
        } catch ( final SQLException e ) {
            // LOG.error justified: key generation failure blocks operator admin work and becomes HTTP 500.
            LOG.error( "Failed to generate API key for {}: {}", principalLogin, e.getMessage() );
            throw new IllegalStateException( "API key generation failed", e );
        }
    }

    /**
     * Looks up an active key by its plaintext bearer token. Returns empty for
     * unknown tokens or revoked keys. Updates {@code last_used_at} asynchronously
     * on a cache miss (approximately once per {@value VERIFY_TTL_SECONDS} seconds
     * per key); cache hits do zero DB work.
     */
    public Optional< Record > verify( final String plaintext ) {
        if ( plaintext == null || plaintext.isEmpty() ) {
            return Optional.empty();
        }
        final String hash = sha256Hex( plaintext );
        return verifyCache.get( hash, h -> {
            final Optional< Record > looked = lookupByHash( h );
            looked.ifPresent( rec -> touchExecutor.submit( () -> touchLastUsed( rec.id() ) ) );
            return looked;
        } );
    }

    /** DB lookup for a token hash; no caching, no touch. Populates {@link #idToHash} on hit. */
    private Optional< Record > lookupByHash( final String hash ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE + " WHERE key_hash = ? AND revoked_at IS NULL";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hash );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    return Optional.empty();
                }
                final Record record = readRow( rs );
                idToHash.put( record.id(), hash );
                return Optional.of( record );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "API key verify failed: {}", e.getMessage() );
            return Optional.empty();
        }
    }

    /** Test/metrics hook: stats for the verify cache. */
    public CacheStats verifyCacheStats() {
        return verifyCache.stats();
    }

    /** Lists all keys (active and revoked), newest first. */
    public List< Record > list() {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE + " ORDER BY created_at DESC";
        final List< Record > out = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                out.add( readRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "API key list failed: {}", e.getMessage() );
        }
        return out;
    }

    /**
     * Marks a key as revoked. No-op if the key is already revoked or does
     * not exist. Returns {@code true} if the call actually revoked the key.
     * Immediately evicts the key from the verify cache so the revocation
     * takes effect on the next request rather than waiting for TTL expiry.
     */
    public boolean revoke( final int id, final String revokedBy ) {
        final String sql = "UPDATE " + TABLE
                + " SET revoked_at = ?, revoked_by = ?"
                + " WHERE id = ? AND revoked_at IS NULL";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
            ps.setString( 2, revokedBy );
            ps.setInt( 3, id );
            final boolean revoked = ps.executeUpdate() > 0;
            if ( revoked ) {
                final String hash = idToHash.remove( id );
                if ( hash != null ) {
                    verifyCache.invalidate( hash );
                }
            }
            return revoked;
        } catch ( final SQLException e ) {
            LOG.warn( "API key revoke failed for id={}: {}", id, e.getMessage() );
            return false;
        }
    }

    /**
     * Soft-revokes all active API keys owned by {@code principalLogin}.
     *
     * <p>Intended for use during account deletion so that outstanding bearer
     * tokens stop working immediately rather than lingering until their
     * natural expiry. Already-revoked keys are unaffected (WHERE clause
     * gates on {@code revoked_at IS NULL}). The in-process verify cache is
     * also cleared for any key whose id is tracked in {@link #idToHash}.</p>
     *
     * <p>This operation is best-effort: if the UPDATE fails (e.g. the
     * DataSource is unavailable) a warning is logged and the method returns
     * without throwing so that the caller's deletion flow is not blocked.</p>
     */
    public void revokeAllForPrincipal( final String principalLogin ) {
        final String sql = "UPDATE " + TABLE
                + " SET revoked_at = ?"
                + " WHERE principal_login = ? AND revoked_at IS NULL";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
            ps.setString( 2, principalLogin );
            final int count = ps.executeUpdate();
            // Evict any cached verify entries whose id we know so the revocation
            // takes effect without waiting for TTL expiry.
            idToHash.forEach( ( id, hash ) -> verifyCache.invalidate( hash ) );
            idToHash.clear();
            LOG.info( "Revoked {} API key(s) for principal '{}'", count, principalLogin );
        } catch ( final SQLException e ) {
            LOG.warn( "revokeAllForPrincipal failed for '{}': {}", principalLogin, e.getMessage(), e );
        }
    }

    /** Generates a new plaintext token: prefix + 32 random bytes (base64url). */
    private String newToken() {
        final byte[] raw = new byte[ TOKEN_BYTES ];
        rng.nextBytes( raw );
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString( raw );
    }

    private static Record readRow( final ResultSet rs ) throws SQLException {
        return new Record(
                rs.getInt( "id" ),
                rs.getString( "key_hash" ),
                rs.getString( "principal_login" ),
                rs.getString( "label" ),
                Scope.fromWire( rs.getString( "scope" ) ),
                rs.getTimestamp( "created_at" ).toInstant(),
                rs.getString( "created_by" ),
                toInstant( rs.getTimestamp( "last_used_at" ) ),
                toInstant( rs.getTimestamp( "revoked_at" ) ),
                rs.getString( "revoked_by" )
        );
    }

    private static Instant toInstant( final Timestamp ts ) {
        return ts != null ? ts.toInstant() : null;
    }

    private void touchLastUsed( final int id ) {
        final String sql = "UPDATE " + TABLE + " SET last_used_at = ? WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
            ps.setInt( 2, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Could not update last_used_at for id={}: {}", id, e.getMessage() );
        }
    }

    /** Public so filters can precompute constant-time hash comparisons if needed. */
    public static String sha256Hex( final String input ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            final byte[] digest = md.digest( input.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( digest.length * 2 );
            for ( final byte b : digest ) {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}

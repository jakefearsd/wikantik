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
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private final Jdbc jdbc;
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
        this.jdbc = new Jdbc( dataSource );
    }

    /**
     * Scopes a generated key can be restricted to.
     *
     * <p>The MCP scopes form a hierarchy (via {@link #mcpRank}) so a higher-privilege
     * key satisfies a lower-privilege requirement: {@code MCP_READ ⊂ MCP}. {@code MCP}
     * is the historical broad admin scope; its wire value stays {@code "mcp"} so keys
     * minted before the split remain full-admin. {@code MCP_READ} is confined to the
     * read-only, ACL-gated {@code /knowledge-mcp} endpoint. {@code TOOLS} (the OpenAPI
     * {@code /tools/*} surface) is orthogonal and matches only itself; {@code ALL}
     * matches everything.</p>
     *
     * <p>A third, intermediate {@code MCP_CONTENT} tier (page + KG-curation read/write,
     * no destructive/admin capability) is designed but not yet added: it requires
     * <em>per-tool</em> enforcement on the shared admin endpoint, which cannot ride a
     * request-thread ThreadLocal because the MCP SDK dispatches tool calls on a
     * separate scheduler thread — the scope must be propagated through the MCP session
     * instead. See the security-audit memory for the full spec.</p>
     */
    public enum Scope {
        /** Knowledge consumer: read + search over ACL-gated content ({@code /knowledge-mcp}). */
        MCP_READ( "mcp_read", 1 ),
        /** Full admin MCP surface (historical {@code "mcp"} wire value; pre-split keys stay here). */
        MCP( "mcp", 3 ),
        TOOLS( "tools", 0 ),
        ALL( "all", 0 );

        private final String wire;
        /** Rank within the MCP family (1..3); {@code 0} for non-MCP scopes. */
        private final int mcpRank;

        Scope( final String wire, final int mcpRank ) { this.wire = wire; this.mcpRank = mcpRank; }
        public String wire() { return wire; }

        private boolean isMcpFamily() { return mcpRank > 0; }

        /**
         * True if a key holding <em>this</em> scope may access a surface/tool that
         * requires {@code required}. {@code ALL} covers everything; within the MCP
         * family a higher rank covers a lower one; otherwise the scopes must be equal.
         */
        public boolean matches( final Scope required ) {
            if ( this == ALL ) {
                return true;
            }
            if ( this.isMcpFamily() && required.isMcpFamily() ) {
                return this.mcpRank >= required.mcpRank;
            }
            return this == required;
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
        // RETURNING id replaces Statement.RETURN_GENERATED_KEYS, which the Jdbc primitive has no
        // equivalent for (its surface is query/queryOne/update/batch/forEachRow/execute — none
        // expose generated-key retrieval). Postgres-only syntax, but this codebase targets
        // Postgres exclusively (bin/db/migrations use ON CONFLICT/::vector throughout), so this
        // is not a new dialect dependency.
        final String sql = "INSERT INTO " + TABLE
                + " (key_hash, principal_login, label, scope, created_at, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
        try {
            final int id = jdbc.queryOne( sql, ps -> {
                ps.setString( 1, hash );
                ps.setString( 2, principalLogin );
                ps.setString( 3, label );
                ps.setString( 4, scope.wire() );
                ps.setTimestamp( 5, Timestamp.from( createdAt ) );
                ps.setString( 6, createdBy );
            }, rs -> rs.getInt( 1 ) ).orElseThrow( () -> new SQLException( "INSERT yielded no generated key" ) );
            final Record record = new Record( id, hash, principalLogin, label,
                    scope, createdAt, createdBy, null, null, null );
            return new Generated( plaintext, record );
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
        try {
            final Optional< Record > found = jdbc.queryOne( sql, ps -> ps.setString( 1, hash ), ApiKeyService::readRow );
            found.ifPresent( record -> idToHash.put( record.id(), hash ) );
            return found;
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
        try {
            return jdbc.query( sql, SqlBinder.NONE, ApiKeyService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "API key list failed: {}", e.getMessage() );
            return new ArrayList<>();
        }
    }

    /** Lists a principal's ACTIVE keys, newest first. */
    public List< Record > listByPrincipal( final String principalLogin ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE
                + " WHERE principal_login = ? AND revoked_at IS NULL"
                + " ORDER BY created_at DESC";
        try {
            return jdbc.query( sql, ps -> ps.setString( 1, principalLogin ), ApiKeyService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "API key listByPrincipal failed for '{}': {}", principalLogin, e.getMessage() );
            return new ArrayList<>();
        }
    }

    /** Looks up a key by id (active or revoked); empty if not found. */
    public Optional< Record > findById( final int id ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE + " WHERE id = ?";
        try {
            return jdbc.queryOne( sql, ps -> ps.setInt( 1, id ), ApiKeyService::readRow );
        } catch ( final SQLException e ) {
            LOG.warn( "API key findById failed for id={}: {}", id, e.getMessage() );
            return Optional.empty();
        }
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
        try {
            final boolean revoked = jdbc.update( sql, ps -> {
                ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
                ps.setString( 2, revokedBy );
                ps.setInt( 3, id );
            } ) > 0;
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
        try {
            final int count = jdbc.update( sql, ps -> {
                ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
                ps.setString( 2, principalLogin );
            } );
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
        try {
            jdbc.update( sql, ps -> {
                ps.setTimestamp( 1, Timestamp.from( Instant.now() ) );
                ps.setInt( 2, id );
            } );
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

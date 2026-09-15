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
package com.wikantik.auth.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.wikantik.util.CryptoUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Short-TTL cache of successful bcrypt password verifications — mirrors the pattern
 * established by {@code ApiKeyService.verifyCache}. Reached via {@code BasicAuthFilter} →
 * {@code DefaultAuthenticationManager.login} → {@code UserDatabaseLoginModule.login} →
 * {@link AbstractUserDatabase#validatePassword}, this is the fast path for stateless HTTP Basic
 * clients (monitoring pollers, cron jobs, CI scripts) that resend credentials on every request
 * and would otherwise pay a full ~150-250ms bcrypt key-stretch per call.
 *
 * <p><b>Cache key</b> is {@code loginName + ' ' + sha256Hex(password || storedPassword)} — see
 * {@link #cacheKey}. Binding the key to the <em>stored hash</em>, not just the login name, is
 * the load-bearing part: when a password changes, the stored bcrypt hash (and its salt)
 * changes, so the key changes, so the old cached entry becomes unreachable and the new
 * credential is verified for real. A password change therefore takes effect
 * <em>immediately</em>, never waiting out the TTL — every call still fetches the current
 * profile and recomputes the key from its current stored hash; only the bcrypt verify itself
 * is skipped on a hit.</p>
 *
 * <p><b>Only successes are cached</b> — {@link #tryVerify} returns empty for anything not
 * eligible, and {@link #verifyBcrypt} returns {@code null} (never cached by Caffeine) on a
 * failed verify. bcrypt's cost is a deliberate brute-force deterrent; caching a negative
 * verdict would blunt it, so a wrong password always pays full price. The caller
 * ({@link AbstractUserDatabase#validatePassword}) is responsible for only invoking this cache
 * for stored hashes that are already bcrypt — the legacy-hash transparent-migration branch is
 * never routed through here, so migration still happens exactly once.</p>
 *
 * <p><b>Cannot bypass account lockout</b>: {@code UserDatabaseLoginModule.login()} checks
 * {@code profile.isLocked()} itself, AFTER calling {@code validatePassword}, against a
 * freshly-fetched profile. A cached "password is correct" verdict says nothing about lock
 * state, so caching here has no bearing on lockout enforcement.</p>
 *
 * <p>The cached value is a boolean verdict only; the key is a one-way SHA-256 digest and holds
 * no reversible credential material — the plaintext password is never stored.</p>
 *
 * <p>Configurable via the {@value #TTL_PROPERTY} system property (default
 * {@value #DEFAULT_TTL_SECONDS}s); a value {@code <= 0} disables the cache entirely — every
 * call then pays the full bcrypt cost.</p>
 */
final class PasswordVerifyCache {

    /** System property that overrides {@link #DEFAULT_TTL_SECONDS}. */
    static final String TTL_PROPERTY = "wikantik.auth.password.verifyCache.ttlSeconds";

    /** Default TTL, in seconds, for the successful-password-verification cache. */
    static final long DEFAULT_TTL_SECONDS = 60L;

    /** {@code null} when the cache is disabled (ttlSeconds <= 0). */
    private final Cache< String, Boolean > cache;

    PasswordVerifyCache() {
        this( resolveTtlSeconds() );
    }

    /** Package-private: lets tests exercise a specific TTL without the system property. */
    PasswordVerifyCache( final long ttlSeconds ) {
        this.cache = ttlSeconds <= 0 ? null : Caffeine.newBuilder()
                .expireAfterWrite( Duration.ofSeconds( ttlSeconds ) )
                .maximumSize( 10_000 )
                .recordStats()
                .build();
    }

    static long resolveTtlSeconds() {
        final String raw = System.getProperty( TTL_PROPERTY );
        if( raw == null || raw.isBlank() ) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            return Long.parseLong( raw.trim() );
        } catch( final NumberFormatException e ) {
            AbstractUserDatabase.LOG.warn( "Invalid {}='{}' — using default {}s", TTL_PROPERTY, raw,
                    DEFAULT_TTL_SECONDS );
            return DEFAULT_TTL_SECONDS;
        }
    }

    /** Test/metrics hook: stats for the password verification cache; empty stats when caching is disabled. */
    CacheStats stats() {
        return cache != null ? cache.stats() : CacheStats.empty();
    }

    /**
     * Attempts a cached bcrypt verification of {@code password} against {@code storedPassword}
     * for {@code loginName}. Returns {@link Optional#empty()} when caching is disabled or the
     * cache key could not be derived (SHA-256 unavailable) — the caller must fall through to a
     * direct verify in that case, exactly as if this cache did not exist. A present value is
     * the authoritative, cached verdict.
     *
     * <p>Uses Caffeine's {@code get(key, loader)}, not {@code getIfPresent} + a later
     * {@code put}: the loader runs under a per-key lock, so requests that pile up the instant an
     * entry expires share ONE bcrypt verify instead of each recomputing the same hash.</p>
     */
    Optional< Boolean > tryVerify( final String loginName, final String password, final String storedPassword ) {
        if( cache == null ) {
            return Optional.empty();
        }
        final String key = cacheKey( loginName, password, storedPassword );
        if( key == null ) {
            return Optional.empty();
        }
        return Optional.of( cache.get( key, k -> verifyBcrypt( password, storedPassword ) ) != null );
    }

    /**
     * Builds the cache key for a (login, password, storedHash) triple: {@code loginName + ' ' +
     * sha256Hex(password bytes || storedPassword bytes)}. See the class javadoc for why the key
     * is bound to the stored hash. Never stores or logs the plaintext password itself.
     *
     * @return the cache key, or {@code null} if SHA-256 is unavailable (never happens in
     *         practice — it is a JDK-guaranteed {@link MessageDigest} algorithm — but fails
     *         closed to "bypass the cache" rather than throwing out of {@link #tryVerify}).
     */
    private static String cacheKey( final String loginName, final String password, final String storedPassword ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            md.update( password.getBytes( StandardCharsets.UTF_8 ) );
            md.update( storedPassword.getBytes( StandardCharsets.UTF_8 ) );
            // HexFormat, not String.format in a loop: this runs on EVERY call including
            // cache hits, and 32 String.format invocations is real overhead once the
            // bcrypt verify it guards is no longer being paid.
            return loginName + ' ' + HexFormat.of().formatHex( md.digest() );
        } catch( final NoSuchAlgorithmException e ) {
            AbstractUserDatabase.LOG.warn( "SHA-256 unavailable for password verify cache key — bypassing cache: {}",
                    e.getMessage() );
            return null;
        }
    }

    /**
     * bcrypt verification for the cache loader. Returns {@link Boolean#TRUE} on success and
     * {@code null} on failure, because Caffeine does not store a null: a wrong password is
     * therefore never cached and pays bcrypt's full cost on every attempt. That keeps the
     * brute-force deterrent intact — the cache key binds the password hash, so each distinct
     * guess is a distinct key and buys an attacker nothing.
     *
     * <p>The {@code null}-on-failure return is load-bearing for that Caffeine contract (a
     * mapping-function return of {@code null} is the documented way to opt a
     * {@code get(key, loader)} call out of caching), not an oversight — this method is private
     * and its only caller is the lambda in {@link #tryVerify}.</p>
     *
     * @return {@code TRUE} if the password matches, {@code null} otherwise
     */
    private static Boolean verifyBcrypt( final String password, final String storedPassword ) {
        try {
            return CryptoUtil.verifySaltedPassword( password.getBytes( StandardCharsets.UTF_8 ), storedPassword )
                    ? Boolean.TRUE : null;
        } catch( final NoSuchAlgorithmException e ) {
            // LOG.error justified: cannot happen for bcrypt (no MessageDigest involved), so reaching it means the JVM crypto provider set is broken
            AbstractUserDatabase.LOG.error( "Unsupported algorithm verifying password for a bcrypt hash: {}",
                    e.getMessage(), e );
            return null;
        }
    }
}

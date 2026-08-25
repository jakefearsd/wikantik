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
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.ByteUtils;
import com.wikantik.util.CryptoUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

/**
 * Abstract UserDatabase class that provides convenience methods for finding profiles, building Principal collections and hashing passwords.
 *
 * @since 2.3
 */
public abstract class AbstractUserDatabase implements UserDatabase {

    protected static final Logger LOG = LogManager.getLogger( AbstractUserDatabase.class );
    protected static final String SHA_PREFIX = "{SHA}";
    protected static final String SSHA_PREFIX = "{SSHA}";
    protected static final String SHA256_PREFIX = "{SHA-256}";
    /** Current hash algorithm for new and re-hashed passwords (supersedes salted SHA-256). */
    protected static final String BCRYPT_PREFIX = CryptoUtil.BCRYPT;

    /** System property that overrides {@link #DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS}. */
    private static final String PASSWORD_VERIFY_CACHE_TTL_PROPERTY = "wikantik.auth.password.verifyCache.ttlSeconds";

    /** Default TTL, in seconds, for the successful-password-verification cache. */
    private static final long DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS = 60L;

    /**
     * Short-TTL cache of successful bcrypt password verifications — mirrors the pattern
     * established by {@code ApiKeyService.verifyCache}. Reached via {@code BasicAuthFilter} →
     * {@code DefaultAuthenticationManager.login} → {@code UserDatabaseLoginModule.login} →
     * {@link #validatePassword}, this is the fast path for stateless HTTP Basic clients
     * (monitoring pollers, cron jobs, CI scripts) that resend credentials on every request and
     * would otherwise pay a full ~150-250ms bcrypt key-stretch per call.
     *
     * <p><b>Cache key</b> is {@code loginName + ' ' + sha256Hex(password || storedPassword)} —
     * see {@link #passwordVerifyCacheKey}. Binding the key to the <em>stored hash</em>, not just
     * the login name, is the load-bearing part: when a password changes, the stored bcrypt hash
     * (and its salt) changes, so the key changes, so the old cached entry becomes unreachable and
     * the new credential is verified for real. A password change therefore takes effect
     * <em>immediately</em>, never waiting out the TTL — every call still fetches the current
     * profile and recomputes the key from its current stored hash; only the bcrypt verify itself
     * is skipped on a hit.</p>
     *
     * <p><b>Only successes are cached</b> — see {@link #validatePassword}. bcrypt's cost is a
     * deliberate brute-force deterrent; caching a negative verdict would blunt it, so a wrong
     * password always pays full price. The legacy-hash transparent-migration branch (which
     * mutates and saves the profile) is also never cached — only entries whose stored hash was
     * <em>already</em> bcrypt are eligible, so migration still happens exactly once.</p>
     *
     * <p><b>Cannot bypass account lockout</b>: {@code UserDatabaseLoginModule.login()} checks
     * {@code profile.isLocked()} itself, AFTER calling {@link #validatePassword}, against a
     * freshly-fetched profile. A cached "password is correct" verdict says nothing about lock
     * state, so caching here has no bearing on lockout enforcement.</p>
     *
     * <p>The cached value is a boolean verdict only; the key is a one-way SHA-256 digest and
     * holds no reversible credential material — the plaintext password is never stored.</p>
     *
     * <p>Configurable via the {@value #PASSWORD_VERIFY_CACHE_TTL_PROPERTY} system property
     * (default {@value #DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS}s); a value {@code <= 0}
     * disables the cache entirely — every call then pays the full bcrypt cost.</p>
     */
    private final Cache< String, Boolean > passwordVerifyCache = buildPasswordVerifyCache();

    private static Cache< String, Boolean > buildPasswordVerifyCache() {
        final long ttlSeconds = resolvePasswordVerifyCacheTtlSeconds();
        if( ttlSeconds <= 0 ) {
            return null;
        }
        return Caffeine.newBuilder()
                .expireAfterWrite( Duration.ofSeconds( ttlSeconds ) )
                .maximumSize( 10_000 )
                .recordStats()
                .build();
    }

    private static long resolvePasswordVerifyCacheTtlSeconds() {
        final String raw = System.getProperty( PASSWORD_VERIFY_CACHE_TTL_PROPERTY );
        if( raw == null || raw.isBlank() ) {
            return DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS;
        }
        try {
            return Long.parseLong( raw.trim() );
        } catch( final NumberFormatException e ) {
            LOG.warn( "Invalid {}='{}' — using default {}s", PASSWORD_VERIFY_CACHE_TTL_PROPERTY, raw,
                    DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS );
            return DEFAULT_PASSWORD_VERIFY_CACHE_TTL_SECONDS;
        }
    }

    /** Test/metrics hook: stats for the password verification cache; empty stats when caching is disabled. */
    public CacheStats passwordVerifyCacheStats() {
        return passwordVerifyCache != null ? passwordVerifyCache.stats() : CacheStats.empty();
    }

    /**
     * Builds the cache key for a (login, password, storedHash) triple: {@code loginName + ' ' +
     * sha256Hex(password bytes || storedPassword bytes)}. See {@link #passwordVerifyCache} for why
     * the key is bound to the stored hash. Never stores or logs the plaintext password itself.
     *
     * @return the cache key, or {@code null} if SHA-256 is unavailable (never happens in practice —
     *         it is a JDK-guaranteed {@link MessageDigest} algorithm — but fails closed to "bypass
     *         the cache" rather than throwing out of {@link #validatePassword}).
     */
    private String passwordVerifyCacheKey( final String loginName, final String password, final String storedPassword ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            md.update( password.getBytes( StandardCharsets.UTF_8 ) );
            md.update( storedPassword.getBytes( StandardCharsets.UTF_8 ) );
            // HexFormat, not String.format in a loop: this runs on EVERY call including
            // cache hits, and 32 String.format invocations is real overhead once the
            // bcrypt verify it guards is no longer being paid.
            return loginName + ' ' + HexFormat.of().formatHex( md.digest() );
        } catch( final NoSuchAlgorithmException e ) {
            LOG.warn( "SHA-256 unavailable for password verify cache key — bypassing cache: {}", e.getMessage() );
            return null;
        }
    }

    /**
     * Looks up and returns the first {@link UserProfile} in the user database that whose login name, full name, or wiki name matches the
     * supplied string. This method provides a "forgiving" search algorithm for resolving principal names when the exact profile attribute
     * that supplied the name is unknown.
     *
     * @param index the login name, full name, or wiki name
     * @see com.wikantik.auth.user.UserDatabase#find(java.lang.String)
     */
    @Override
    public UserProfile find( final String index ) throws NoSuchPrincipalException {
        UserProfile profile = null;

        // Try finding by full name
        try {
            profile = findByFullName( index );
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "find('{}'): no match by full name — trying wiki name", index );
        }
        if( profile != null ) {
            return profile;
        }

        // Try finding by wiki name
        try {
            profile = findByWikiName( index );
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "find('{}'): no match by wiki name — trying login name", index );
        }
        if( profile != null ) {
            return profile;
        }

        // Try finding by login name
        try {
            profile = findByLoginName( index );
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "find('{}'): no match by login name — giving up", index );
        }
        if( profile != null ) {
            return profile;
        }

        throw new NoSuchPrincipalException( "Not in database: " + index );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.auth.user.UserDatabase#findByEmail(java.lang.String)
     */
    @Override
    public abstract UserProfile findByEmail( String index ) throws NoSuchPrincipalException;

    /**
     * {@inheritDoc}
     * @see com.wikantik.auth.user.UserDatabase#findByFullName(java.lang.String)
     */
    @Override
    public abstract UserProfile findByFullName( String index ) throws NoSuchPrincipalException;

    /**
     * {@inheritDoc}
     * @see com.wikantik.auth.user.UserDatabase#findByLoginName(java.lang.String)
     */
    @Override
    public abstract UserProfile findByLoginName( String index ) throws NoSuchPrincipalException;

    /**
     * {@inheritDoc}
     * @see com.wikantik.auth.user.UserDatabase#findByWikiName(java.lang.String)
     */
    @Override
    public abstract UserProfile findByWikiName( String index ) throws NoSuchPrincipalException;

    /**
     * <p>Looks up the Principals representing a user from the user database. These
     * are defined as a set of WikiPrincipals manufactured from the login name,
     * full name, and wiki name. If the user database does not contain a user
     * with the supplied identifier, throws a {@link NoSuchPrincipalException}.</p>
     * <p>When this method creates WikiPrincipals, the Principal containing
     * the user's full name is marked as containing the common name (see
     * {@link com.wikantik.auth.WikiPrincipal#WikiPrincipal(String, String)}).
     * @param identifier the name of the principal to retrieve; this corresponds to
     *            value returned by the user profile's
     *            {@link UserProfile#getLoginName()}method.
     * @return the array of Principals representing the user
     * @see com.wikantik.auth.user.UserDatabase#getPrincipals(java.lang.String)
     * @throws NoSuchPrincipalException If the user database does not contain user with the supplied identifier
     */
    @Override
    public Principal[] getPrincipals( final String identifier ) throws NoSuchPrincipalException {
        final UserProfile profile = findByLoginName( identifier );
        final var principals = new ArrayList< Principal >();
        if( profile.getLoginName() != null && !profile.getLoginName().isEmpty() ) {
            principals.add( new WikiPrincipal( profile.getLoginName(), WikiPrincipal.LOGIN_NAME ) );
        }
        if( profile.getFullname() != null && !profile.getFullname().isEmpty() ) {
            principals.add( new WikiPrincipal( profile.getFullname(), WikiPrincipal.FULL_NAME ) );
        }
        if( profile.getWikiName() != null && !profile.getWikiName().isEmpty() ) {
            principals.add( new WikiPrincipal( profile.getWikiName(), WikiPrincipal.WIKI_NAME ) );
        }
        return principals.toArray( new Principal[0] );
    }

    /**
     * {@inheritDoc}
     *
     * @see com.wikantik.auth.user.UserDatabase#initialize(com.wikantik.api.core.Engine, java.util.Properties)
     */
    @Override
    public abstract void initialize( Engine engine, Properties props ) throws NoRequiredPropertyException, WikiSecurityException;

    /**
     * Factory method that instantiates a new DefaultUserProfile with a new, distinct unique identifier.
     * 
     * @return A new, empty profile.
     */
    @Override
    public UserProfile newProfile() {
        final UserProfile profile = new DefaultUserProfile();
        profile.setUid( AbstractUserDatabase.generateUid( this ) );
        return profile;
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.auth.user.UserDatabase#save(com.wikantik.auth.user.UserProfile)
     */
    @Override
    public abstract void save( UserProfile profile ) throws WikiSecurityException;

    /**
     * Validates the password for a given user. If the user does not exist in the user database, this method always returns
     * <code>false</code>. If the user exists, the supplied password is compared to the stored password. Note that if the stored password's
     * value starts with <code>{SHA}</code>, the supplied password is hashed prior to the comparison.
     *
     * @param loginName the user's login name
     * @param password the user's password (obtained from user input, e.g., a web form)
     * @return <code>true</code> if the supplied user password matches the stored password
     * @see com.wikantik.auth.user.UserDatabase#validatePassword(java.lang.String, java.lang.String)
     */
    @Override
    public boolean validatePassword( final String loginName, final String password ) {
        final String hashedPassword;
        try {
            final UserProfile profile = findByLoginName( loginName );
            String storedPassword = profile.getPassword();
            boolean verified = false;

            // Fast path: a previously-cached successful verification of this EXACT (password,
            // storedHash) pair, still within TTL. Only bcrypt-stored entries are eligible — see
            // passwordVerifyCache javadoc for the full contract (why the key binds the stored
            // hash, why only successes are cached, and why this can't bypass account lockout).
            String cacheKey = null;
            if( passwordVerifyCache != null && storedPassword.startsWith( BCRYPT_PREFIX ) ) {
                cacheKey = passwordVerifyCacheKey( loginName, password, storedPassword );
                if( cacheKey != null ) {
                    final Boolean cached = passwordVerifyCache.getIfPresent( cacheKey );
                    if( cached != null ) {
                        return cached;
                    }
                }
            }

            // Verify against whichever algorithm the stored hash declares. CryptoUtil dispatches
            // bcrypt ({bcrypt}) and the legacy salted SHA-256 / SHA-1 ({SSHA}) formats.
            if( storedPassword.startsWith( BCRYPT_PREFIX )
                    || storedPassword.startsWith( SHA256_PREFIX )
                    || storedPassword.startsWith( SSHA_PREFIX ) ) {
                verified = CryptoUtil.verifySaltedPassword( password.getBytes( StandardCharsets.UTF_8 ), storedPassword );
            }

            // Use older verification algorithm if password is stored as legacy unsalted {SHA}
            if( storedPassword.startsWith( SHA_PREFIX ) ) {
                storedPassword = storedPassword.substring( SHA_PREFIX.length() );
                hashedPassword = getShaHash( password );
                verified = MessageDigest.isEqual( hashedPassword.getBytes( StandardCharsets.UTF_8 ),
                                                  storedPassword.getBytes( StandardCharsets.UTF_8 ) );
            }

            // Cache only successful verifications against an ALREADY-bcrypt stored hash. Never
            // cache a failure (bcrypt's cost is a deliberate brute-force deterrent; caching a
            // negative verdict would blunt it — a wrong password must always pay full price), and
            // never cache the legacy-migration branch below (it mutates profile.password).
            if( verified && cacheKey != null ) {
                passwordVerifyCache.put( cacheKey, Boolean.TRUE );
            }

            // Transparent migration: on a successful login against any non-bcrypt (legacy) hash,
            // re-hash the just-verified plaintext with bcrypt and persist it. No reset, no password
            // change — the plaintext is only available here, during the successful login.
            if( verified && !storedPassword.startsWith( BCRYPT_PREFIX ) ) {
                profile.setPassword( password );
                save( profile );
            }

            return verified;
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "validatePassword: no profile for login '{}' — returning false", loginName );
        } catch( final NoSuchAlgorithmException e ) {
            LOG.error( "Unsupported algorithm: {}", e.getMessage() );
        } catch( final WikiSecurityException e ) {
            LOG.error( "Could not upgrade SHA password to SSHA because profile could not be saved. Reason: {}", e.getMessage(), e );
        }
        return false;
    }

    /**
     * Generates a new random user identifier (uid) that is guaranteed to be unique.
     * 
     * @param db The database for which the UID should be generated.
     * @return A random, unique UID.
     */
    protected static String generateUid( final UserDatabase db ) {
        // Keep generating UUIDs until we find one that doesn't collide
        String uid;
        boolean collision;
        
        do {
            uid = UUID.randomUUID().toString();
            collision = true;
            try {
                db.findByUid( uid );
            } catch ( final NoSuchPrincipalException e ) {
                collision = false;
            }
        } 
        while ( collision || uid == null );
        return uid;
    }
    
    /**
     * Hashes a password for storage. New and changed passwords are hashed with bcrypt (prefix
     * {@code {bcrypt}}); legacy SHA hashes are migrated to this format on the owner's next login
     * (see {@link #validatePassword}).
     *
     * @param text the text to hash
     * @return the result hash
     */
    protected String getHash( final String text ) {
        return CryptoUtil.getBcryptHash( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    private String getShaHash(final String text ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA" );
            md.update( text.getBytes( StandardCharsets.UTF_8 ) );
            final byte[] digestedBytes = md.digest();
            return ByteUtils.bytes2hex( digestedBytes );
        } catch( final NoSuchAlgorithmException e ) {
            LOG.error( "Error creating SHA password hash:{}", e.getMessage() );
            return text;
        }
    }

    /**
     * Parses a long integer from a supplied string, or returns 0 if not parsable.
     *
     * @param value the string to parse
     * @return the value parsed
     */
    protected long parseLong( final String value ) {
        if( NumberUtils.isParsable( value ) ) {
            return Long.parseLong( value );
        } else {
            return 0L;
        }
    }

}

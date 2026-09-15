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

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.CryptoUtil;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Abstract UserDatabase class that provides convenience methods for finding profiles, building Principal collections and hashing passwords.
 *
 * @since 2.3
 */
public abstract class AbstractUserDatabase implements UserDatabase {

    protected static final Logger LOG = LogManager.getLogger( AbstractUserDatabase.class );
    protected static final String SHA256_PREFIX = "{SHA-256}";
    /** Current hash algorithm for new and re-hashed passwords (supersedes salted SHA-256). */
    protected static final String BCRYPT_PREFIX = CryptoUtil.BCRYPT;

    /**
     * Short-TTL cache of successful bcrypt password verifications, absorbing the per-request
     * bcrypt cost paid by stateless HTTP Basic clients that resend credentials on every call.
     * See {@link PasswordVerifyCache} for the full contract (cache key derivation, why only
     * successes are cached, why it cannot bypass account lockout, and its config property).
     */
    private final PasswordVerifyCache passwordVerifyCache = new PasswordVerifyCache();

    /** Test/metrics hook: stats for the password verification cache; empty stats when caching is disabled. */
    public CacheStats passwordVerifyCacheStats() {
        return passwordVerifyCache.stats();
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
     * <code>false</code>. If the user exists, the supplied password is compared to the stored password, dispatching to whichever
     * algorithm the stored password's prefix declares (bcrypt or legacy salted {@code {SHA-256}}).
     *
     * @param loginName the user's login name
     * @param password the user's password (obtained from user input, e.g., a web form)
     * @return <code>true</code> if the supplied user password matches the stored password
     * @see com.wikantik.auth.user.UserDatabase#validatePassword(java.lang.String, java.lang.String)
     */
    @Override
    public boolean validatePassword( final String loginName, final String password ) {
        try {
            final UserProfile profile = findByLoginName( loginName );
            final String storedPassword = profile.getPassword();
            boolean verified = false;

            // An SSO-provisioned profile can have a null stored password (never a local
            // credential), and a caller can pass a null supplied password (e.g. a change-password
            // form's "current password" field on such an account). Neither is an error — fail
            // closed, quietly, before the cache or CryptoUtil ever sees a null.
            if( storedPassword == null || password == null ) {
                LOG.debug( "validatePassword: null stored or supplied password for login '{}' — returning false", loginName );
                return false;
            }

            // Fast path: a previously-cached successful verification of this EXACT (password,
            // storedHash) pair, still within TTL. Only bcrypt-stored entries are eligible — see
            // PasswordVerifyCache's javadoc for the full contract (why the key binds the stored
            // hash, why only successes are cached, and why this can't bypass account lockout).
            // Empty means "not cacheable right now" (disabled, or no SHA-256) — fall through to
            // the direct verify below exactly as if this cache did not exist.
            if( storedPassword.startsWith( BCRYPT_PREFIX ) ) {
                final Optional< Boolean > cached = passwordVerifyCache.tryVerify( loginName, password, storedPassword );
                if( cached.isPresent() ) {
                    return cached.get();
                }
            }

            // Verify against whichever algorithm the stored hash declares. CryptoUtil dispatches
            // bcrypt ({bcrypt}) and the legacy salted SHA-256 ({SHA-256}) format.
            if( storedPassword.startsWith( BCRYPT_PREFIX ) || storedPassword.startsWith( SHA256_PREFIX ) ) {
                verified = CryptoUtil.verifySaltedPassword( password.getBytes( StandardCharsets.UTF_8 ), storedPassword );
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
            LOG.error( "Could not re-hash legacy password to bcrypt because profile could not be saved. Reason: {}", e.getMessage(), e );
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
     * {@code {bcrypt}}); legacy {@code {SHA-256}} hashes are migrated to this format on the
     * owner's next login (see {@link #validatePassword}).
     *
     * @param text the text to hash
     * @return the result hash
     */
    protected String getHash( final String text ) {
        return CryptoUtil.getBcryptHash( text.getBytes( StandardCharsets.UTF_8 ) );
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

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

import com.wikantik.api.core.Engine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;

import java.security.Principal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * In-memory {@link UserDatabase} for tests, seeded with the same fixture users that the
 * retired {@code userdatabase.xml} provided. It exists so the broad {@code TestEngine}-based
 * suite (which logs in {@code admin} and {@code janne}) keeps running with zero infrastructure
 * after the XML-backed user store was removed in favour of PostgreSQL ({@link JDBCUserDatabase})
 * for production.
 *
 * <p>It extends {@link AbstractUserDatabase} so password validation, {@code find()},
 * {@code getPrincipals()} and {@code newProfile()} are exercised exactly as in production. The
 * persistence seams are backed by a {@link Map}. Crucially — like the old XML store — the
 * {@code find*} methods return <em>fresh copies</em> of the stored profile, so the SSHA
 * password-upgrade round-trip in {@link AbstractUserDatabase#validatePassword} (which mutates a
 * found profile then calls {@link #save}) compares the supplied plaintext against the stored hash
 * and re-hashes correctly, rather than comparing a value against itself.</p>
 */
public class InMemoryUserDatabase extends AbstractUserDatabase {

    /** The canonical store, keyed by login name (exact match, as the XML store used). */
    private final Map< String, UserProfile > byLogin = new LinkedHashMap<>();

    public InMemoryUserDatabase() {
        seed();
    }

    /** Seeds the fixture users. Idempotent — re-seeding only fills in absent entries. */
    private void seed() {
        // login,             wikiName,         fullName,            email,                {hash}password
        put( "janne",   "JanneJalkanen",  "Janne Jalkanen",    "janne@ecyrd.com",    "{SHA-256}AeJQgAgYDAf2WZiqPJ2l6cGdGC/PgWmkjZmkjrBEV6SW/HlclZGlIg==", "-7739839977499061014" );
        put( "user",    "",               "",                  "user@example.com",   "{SSHA}iQWmcKE8PyO965jh4+VNLYbxagaDdS0nC9GmuQ==",                   "-8629747547991531672" );
        put( "admin",   "Administrator",  "Administrator",     "admin@locahost",     "{SSHA}6YNKYMwXICUf5pMvYUZumgbFCxZMT2njtUQtJw==",                   null );
        put( "Alice",   "Alice",          "Alice",             "alice@example.com",  "{SSHA}3V4zI5W6mT+x5NIHKI2KFQIYBdnAYKNOE9Aj+Q==",                   null );
        put( "Bob",     "Bob",            "Bob",               "bob@example.com",    "{SSHA}NP3aAmiwK0gHywTe4qbY6klKDqnZ+F9ym9YiLg==",                   null );
        put( "Charlie", "Charlie",        "Charlie",           "charlie@example.com","{SSHA}wn81B14F9axtTVYsipQKC2OWQHlc6EcpMSe58Q==",                   null );
        put( "Fred",    "FredFlintstone", "Fred Flintstone",   "fred@example.com",   "{SSHA}iDeE9dysPUE28SWd6yeIqiIj9sIVyiMM7VnMKQ==",                   null );
        put( "Biff",    "Biff",           "Biff",              "biff@example.com",   "{SSHA}xKAIienaZZHhKTGCNv5Li6lzeemaSs6ZYXTHFQ==",                   null );
    }

    private void put( final String login, final String wikiName, final String fullName,
                      final String email, final String password, final String uid ) {
        if ( byLogin.containsKey( login ) ) {
            return;
        }
        putRaw( login, fullName, wikiName, email, password, uid );
    }

    /**
     * Inserts a user with the password value stored <em>verbatim</em> (no hashing). Mirrors loading
     * such a record from the old XML store, which kept the password exactly as written. Tests use it
     * to inject pre-hashed or legacy-format ({@code {SHA}}) credentials that exercise
     * {@link AbstractUserDatabase#validatePassword}'s legacy-upgrade branch.
     */
    public void putRaw( final String login, final String fullName, final String wikiName,
                        final String email, final String rawPassword, final String uid ) {
        final UserProfile p = new DefaultUserProfile();
        p.setUid( uid != null ? uid : generateUid( this ) );
        p.setLoginName( login );
        // setFullname recomputes the wiki name, so it must precede setWikiName (see copy()).
        p.setFullname( fullName );
        p.setWikiName( wikiName );
        p.setEmail( email );
        p.setPassword( rawPassword );
        p.setCreated( new Date( 0L ) );
        p.setLastModified( new Date( 0L ) );
        byLogin.put( login, p );
    }

    /** Returns an independent copy so callers' mutations don't leak into the store before {@link #save}. */
    private static UserProfile copy( final UserProfile s ) {
        final UserProfile p = new DefaultUserProfile();
        p.setUid( s.getUid() );
        p.setLoginName( s.getLoginName() );
        // setFullname recomputes the wiki name from the full name, so it must run BEFORE
        // setWikiName — otherwise an explicitly de-duplicated wiki name (e.g. "JakeFear2")
        // gets clobbered back to the full-name derivation. Mirrors the XML store, which
        // persisted the wiki-name attribute as written.
        p.setFullname( s.getFullname() );
        p.setWikiName( s.getWikiName() );
        p.setEmail( s.getEmail() );
        p.setPassword( s.getPassword() );
        p.setBio( s.getBio() );
        p.setCreated( s.getCreated() );
        p.setLastModified( s.getLastModified() );
        p.setLockExpiry( s.getLockExpiry() );
        p.setPasswordMustChange( s.isPasswordMustChange() );
        p.getAttributes().putAll( s.getAttributes() );
        return p;
    }

    @Override
    public void initialize( final Engine engine, final Properties props ) {
        seed();
    }

    @Override
    public UserProfile findByEmail( final String index ) throws NoSuchPrincipalException {
        return findBy( p -> index != null && index.equals( p.getEmail() ), index );
    }

    @Override
    public UserProfile findByFullName( final String index ) throws NoSuchPrincipalException {
        return findBy( p -> index != null && index.equals( p.getFullname() ), index );
    }

    @Override
    public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException {
        return findBy( p -> index != null && index.equals( p.getLoginName() ), index );
    }

    @Override
    public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
        return findBy( p -> index != null && index.equals( p.getWikiName() ), index );
    }

    @Override
    public UserProfile findByUid( final String uid ) throws NoSuchPrincipalException {
        return findBy( p -> uid != null && uid.equals( p.getUid() ), uid );
    }

    private interface Match { boolean matches( UserProfile p ); }

    private UserProfile findBy( final Match m, final String index ) throws NoSuchPrincipalException {
        for ( final UserProfile p : byLogin.values() ) {
            if ( m.matches( p ) ) {
                return copy( p );
            }
        }
        throw new NoSuchPrincipalException( "Not in database: " + index );
    }

    @Override
    public Principal[] getWikiNames() {
        final SortedSet< WikiPrincipal > principals = new TreeSet<>();
        for ( final UserProfile p : byLogin.values() ) {
            final String wikiName = p.getWikiName();
            if ( wikiName != null && !wikiName.isEmpty() ) {
                principals.add( new WikiPrincipal( wikiName, WikiPrincipal.WIKI_NAME ) );
            }
        }
        return principals.toArray( new Principal[0] );
    }

    @Override
    public synchronized void save( final UserProfile profile ) throws WikiSecurityException {
        final String login = profile.getLoginName();
        final UserProfile existing = byLogin.get( login );
        final Date modDate = new Date( System.currentTimeMillis() );

        final UserProfile stored = copy( profile );
        if ( existing == null ) {
            stored.setCreated( modDate );
        } else {
            stored.setCreated( existing.getCreated() );
        }

        // Mirror the retired XML store: hash the password only when it differs from the stored value.
        final String newPassword = profile.getPassword();
        if ( newPassword != null && !newPassword.isEmpty() ) {
            final String oldPassword = existing == null ? "" : existing.getPassword();
            if ( !newPassword.equals( oldPassword ) ) {
                stored.setPassword( getHash( newPassword ) );
            }
        }
        stored.setLastModified( modDate );
        byLogin.put( login, stored );

        // Keep the caller's object timestamps consistent, as the XML store did.
        if ( existing == null ) {
            profile.setCreated( modDate );
        }
        profile.setLastModified( modDate );
    }

    @Override
    public synchronized void deleteByLoginName( final String loginName ) throws WikiSecurityException {
        if ( byLogin.remove( loginName ) == null ) {
            throw new NoSuchPrincipalException( "Not in database: " + loginName );
        }
    }

    @Override
    public synchronized void rename( final String loginName, final String newName )
            throws DuplicateUserException, WikiSecurityException {
        final UserProfile profile = byLogin.get( loginName );
        if ( profile == null ) {
            throw new NoSuchPrincipalException( "Not in database: " + loginName );
        }
        if ( byLogin.containsKey( newName ) ) {
            throw new DuplicateUserException( "security.error.cannot.rename", newName );
        }
        byLogin.remove( loginName );
        profile.setLoginName( newName );
        profile.setLastModified( new Date( System.currentTimeMillis() ) );
        byLogin.put( newName, profile );
    }
}

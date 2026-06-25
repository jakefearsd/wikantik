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

import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.Users;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for {@link AbstractUserDatabase}, driven through the {@link InMemoryUserDatabase}
 * test double (seeded with the same fixture users the retired XML store provided). Targets:
 * <ul>
 *   <li>{@code find()} falling through all three tries before throwing</li>
 *   <li>{@code find()} succeeding on each individual path (fullname, wikiname, loginname)</li>
 *   <li>{@code getPrincipals()} with partial and complete profile data</li>
 *   <li>{@code validatePassword()} with a stored SHA-1 prefix ({SHA}) — covers the legacy
 *       upgrade branch</li>
 *   <li>{@code getShaHash()} via {@code validatePassword()} with a SHA-prefixed password</li>
 * </ul>
 */
class AbstractUserDatabaseCITest {

    private InMemoryUserDatabase db;

    @BeforeEach
    void setUp() {
        db = new InMemoryUserDatabase();
    }

    // --- find() by full name ---

    @Test
    void testFindByFullNameSucceeds() throws NoSuchPrincipalException {
        // "Janne Jalkanen" is the full name for login "janne"
        final UserProfile profile = db.find( "Janne Jalkanen" );
        assertEquals( "janne", profile.getLoginName() );
    }

    // --- find() by wiki name ---

    @Test
    void testFindByWikiNameSucceeds() throws NoSuchPrincipalException {
        final UserProfile profile = db.find( "JanneJalkanen" );
        assertEquals( "janne", profile.getLoginName() );
    }

    // --- find() by login name ---

    @Test
    void testFindByLoginNameSucceeds() throws NoSuchPrincipalException {
        final UserProfile profile = db.find( "janne" );
        assertEquals( "janne", profile.getLoginName() );
    }

    // --- find() throws when nothing matches ---

    @Test
    void testFindThrowsWhenNotFound() {
        assertThrows( NoSuchPrincipalException.class, () -> db.find( "no-such-user-xyz" ) );
    }

    // --- getPrincipals() returns login, full, and wiki name principals ---

    @Test
    void testGetPrincipalsReturnsAllThree() throws NoSuchPrincipalException {
        final Principal[] principals = db.getPrincipals( "janne" );
        assertEquals( 3, principals.length );
        boolean hasLogin = false, hasFull = false, hasWiki = false;
        for ( final Principal p : principals ) {
            final String name = p.getName();
            if ( "janne".equals( name ) ) hasLogin = true;
            if ( "Janne Jalkanen".equals( name ) ) hasFull = true;
            if ( "JanneJalkanen".equals( name ) ) hasWiki = true;
        }
        assertTrue( hasLogin, "login name principal missing" );
        assertTrue( hasFull, "full name principal missing" );
        assertTrue( hasWiki, "wiki name principal missing" );
    }

    // --- getPrincipals() throws when user not found ---

    @Test
    void testGetPrincipalsThrowsWhenUserNotFound() {
        assertThrows( NoSuchPrincipalException.class, () -> db.getPrincipals( "nonexistent" ) );
    }

    // --- validatePassword() returns false for non-existent user ---

    @Test
    void testValidatePasswordReturnsFalseForNonExistentUser() {
        assertFalse( db.validatePassword( "nonexistent", "password" ) );
    }

    // --- validatePassword() returns false for wrong password (SSHA/SHA-256 path) ---

    @Test
    void testValidatePasswordReturnsFalseForWrongPassword() {
        assertFalse( db.validatePassword( "janne", "wrongpassword" ) );
    }

    // --- validatePassword() succeeds with correct password (SSHA/SHA-256 path) ---

    @Test
    void testValidatePasswordReturnsTrueForCorrectPassword() {
        assertTrue( db.validatePassword( "janne", Users.JANNE_PASS ) );
    }

    // --- transparent migration: a successful login on a legacy SHA hash re-hashes it to bcrypt ---

    @Test
    void testValidatePasswordUpgradesLegacySha256ToBcrypt() throws Exception {
        // janne is seeded with a legacy {SHA-256} hash (see InMemoryUserDatabase).
        assertTrue( db.findByLoginName( "janne" ).getPassword().startsWith( "{SHA-256}" ),
                    "precondition: janne starts on a legacy {SHA-256} hash" );

        // A normal, successful login verifies via SHA-256 — exactly as before...
        assertTrue( db.validatePassword( "janne", Users.JANNE_PASS ) );

        // ...and transparently re-hashes the stored credential to bcrypt. No reset, no password
        // change, no user action — the plaintext is in hand during the successful login.
        assertTrue( db.findByLoginName( "janne" ).getPassword().startsWith( "{bcrypt}" ),
                    "a successful login on a legacy SHA-256 hash must upgrade it to bcrypt" );

        // The upgraded bcrypt hash still verifies the same, unchanged password.
        assertTrue( db.validatePassword( "janne", Users.JANNE_PASS ),
                    "the same password must verify against the upgraded bcrypt hash" );
    }

    // --- validatePassword() with legacy SHA prefix — covers getShaHash() branch ---

    @Test
    void testValidatePasswordWithLegacyShaPrefix() {
        // Seed a user whose stored credential is a {SHA}-prefixed hash, verbatim, so the
        // legacy upgrade branch in AbstractUserDatabase.validatePassword() is exercised.
        final String shaHash = computeSha1Hex( "legacypass" );
        final InMemoryUserDatabase legacyDb = new InMemoryUserDatabase();
        legacyDb.putRaw( "legacyuser", "Legacy User", "LegacyUser", "legacy@example.com",
                         "{SHA}" + shaHash, "uid-legacy-1" );

        assertTrue( legacyDb.validatePassword( "legacyuser", "legacypass" ),
                    "Legacy {SHA} password should validate correctly" );
    }

    // --- validatePassword() with legacy {SHA} rejects wrong password ---

    @Test
    void testValidatePasswordWithLegacyShaPrefixRejectsWrongPassword() {
        final String shaHash = computeSha1Hex( "legacypass" );
        final InMemoryUserDatabase legacyDb = new InMemoryUserDatabase();
        legacyDb.putRaw( "legacybaduser", "Legacy Bad User", "LegacyBadUser", "legacybad@example.com",
                         "{SHA}" + shaHash, "uid-legacy-2" );

        assertFalse( legacyDb.validatePassword( "legacybaduser", "wrongpass" ),
                     "Legacy {SHA} validation must reject a completely different password" );
        assertFalse( legacyDb.validatePassword( "legacybaduser", "legacypasx" ),
                     "Legacy {SHA} validation must reject a single-character-mutated password" );
    }

    // --- newProfile() returns a profile with a non-null uid ---

    @Test
    void testNewProfileHasNonNullUid() {
        final UserProfile profile = db.newProfile();
        assertNotNull( profile.getUid() );
        assertFalse( profile.getUid().isEmpty() );
    }

    // --- Helper: compute SHA-1 hex string ---

    private static String computeSha1Hex( final String text ) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance( "SHA" );
            md.update( text.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
            final byte[] digest = md.digest();
            final StringBuilder sb = new StringBuilder();
            for ( final byte b : digest ) {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        } catch ( final java.security.NoSuchAlgorithmException e ) {
            throw new RuntimeException( e );
        }
    }
}

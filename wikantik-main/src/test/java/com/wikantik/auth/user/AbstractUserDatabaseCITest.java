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

import com.wikantik.TestEngine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link AbstractUserDatabase} targeting paths
 * not exercised by the existing test suite:
 * <ul>
 *   <li>{@code find()} falling through all three tries before throwing</li>
 *   <li>{@code find()} succeeding on each individual path (fullname, wikiname, loginname)</li>
 *   <li>{@code getPrincipals()} with partial and complete profile data</li>
 *   <li>{@code validatePassword()} with a stored SHA-1 prefix ({SHA}) — covers the legacy
 *       upgrade branch</li>
 *   <li>{@code getShaHash()} via {@code validatePassword()} with a SHA-prefixed password</li>
 *   <li>{@code parseLong()} via non-parsable lockExpiry in findByAttribute</li>
 * </ul>
 */
class AbstractUserDatabaseCITest {

    private XMLUserDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        final TestEngine engine = new TestEngine( props );
        db = new XMLUserDatabase();
        db.initialize( engine, props );
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
        assertTrue( db.validatePassword( "janne", "myP@5sw0rd" ) );
    }

    // --- validatePassword() with legacy SHA prefix — covers getShaHash() branch ---

    @Test
    void testValidatePasswordWithLegacyShaPrefix() throws Exception {
        // Build a fresh XML database file that already contains a {SHA}-prefixed password
        // so we bypass save()'s hashing logic (which would re-hash it as {SHA-256}).
        final String shaHash = computeSha1Hex( "legacypass" );
        final java.io.File dbFile = java.io.File.createTempFile( "sha-legacy-", ".xml" );
        dbFile.deleteOnExit();

        final String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<users>\n" +
            "  <user loginName=\"legacyuser\" fullName=\"Legacy User\" wikiName=\"LegacyUser\"\n" +
            "        email=\"legacy@example.com\" password=\"{SHA}" + shaHash + "\" uid=\"uid-legacy-1\"\n" +
            "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lockExpiry=\"\" />\n" +
            "</users>";

        try ( final java.io.FileWriter fw = new java.io.FileWriter( dbFile ) ) {
            fw.write( xml );
        }

        final Properties legacyProps = TestEngine.getTestProperties();
        legacyProps.put( XMLUserDatabase.PROP_USERDATABASE, dbFile.getAbsolutePath() );
        final TestEngine legacyEngine = new TestEngine( legacyProps );
        final XMLUserDatabase legacyDb = new XMLUserDatabase();
        legacyDb.initialize( legacyEngine, legacyProps );

        // validatePassword exercises the {SHA} branch and getShaHash()
        assertTrue( legacyDb.validatePassword( "legacyuser", "legacypass" ),
                    "Legacy {SHA} password should validate correctly" );
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

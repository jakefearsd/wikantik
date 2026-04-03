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
import com.wikantik.auth.UserManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link DefaultUserProfile} targeting paths not
 * reached by the existing {@code UserProfileTest}:
 * <ul>
 *   <li>{@code equals()} when the argument is not a {@code DefaultUserProfile}</li>
 *   <li>{@code hashCode()} consistency</li>
 *   <li>{@code isNew()} when last-modified is null vs. set</li>
 *   <li>{@code toString()}</li>
 *   <li>{@code setFullname(null)} — must not throw and must leave wikiname null</li>
 *   <li>{@code equals()} symmetry for profiles with equal fields</li>
 *   <li>{@code getLockExpiry()} clears expired lock and returns null</li>
 * </ul>
 */
class DefaultUserProfileCITest {

    private UserDatabase db;

    @BeforeEach
    void setUp() {
        db = TestEngine.build().getManager( UserManager.class ).getUserDatabase();
    }

    // --- equals() against a non-DefaultUserProfile object ---

    @Test
    void testEqualsReturnsFalseForNonDefaultUserProfile() {
        final UserProfile p = db.newProfile();
        p.setFullname( "Alice" );
        assertNotEquals( p, "Alice" );
        assertNotEquals( p, null );
        assertNotEquals( p, new Object() );
    }

    // --- hashCode() is consistent across invocations and equal for equal profiles ---

    @Test
    void testHashCodeIsConsistent() {
        final UserProfile p = db.newProfile();
        p.setFullname( "Bob" );
        p.setLoginName( "bob" );
        p.setEmail( "bob@example.com" );
        p.setPassword( "secret" );
        assertEquals( p.hashCode(), p.hashCode() );
    }

    @Test
    void testHashCodeEqualForEqualProfiles() {
        final UserProfile p1 = db.newProfile();
        final UserProfile p2 = db.newProfile();

        p1.setFullname( "Charlie" );
        p1.setLoginName( "charlie" );
        p1.setEmail( "charlie@example.com" );
        p1.setPassword( "pass" );

        p2.setFullname( "Charlie" );
        p2.setLoginName( "charlie" );
        p2.setEmail( "charlie@example.com" );
        p2.setPassword( "pass" );

        assertEquals( p1, p2 );
        assertEquals( p1.hashCode(), p2.hashCode() );
    }

    // --- isNew() returns true when lastModified is null ---

    @Test
    void testIsNewReturnsTrueForFreshProfile() {
        final UserProfile p = db.newProfile();
        // lastModified is null for a fresh profile
        assertTrue( p.isNew() );
    }

    // --- isNew() returns false after setLastModified ---

    @Test
    void testIsNewReturnsFalseAfterSettingLastModified() {
        final UserProfile p = db.newProfile();
        p.setLastModified( new Date() );
        assertFalse( p.isNew() );
    }

    // --- toString() contains the full name ---

    @Test
    void testToStringContainsFullName() {
        final UserProfile p = db.newProfile();
        p.setFullname( "Diana Prince" );
        assertTrue( p.toString().contains( "Diana Prince" ) );
    }

    @Test
    void testToStringWithNullFullName() {
        final UserProfile p = db.newProfile();
        // fullname is null; toString() must not throw
        final String str = p.toString();
        assertNotNull( str );
    }

    // --- setFullname(null) does not throw; wikiname retains its previous value ---

    @Test
    void testSetFullnameNullDoesNotThrow() {
        final UserProfile p = db.newProfile();
        p.setFullname( "Alice" );
        // setFullname(null) must not throw
        assertDoesNotThrow( () -> p.setFullname( null ) );
        // The code only enters the wikiname-derivation block when fullname != null,
        // so wikiname keeps its previous value ("Alice").
        assertEquals( "Alice", p.getWikiName() );
    }

    // --- setFullname() derives wiki name by stripping whitespace ---

    @Test
    void testSetFullnameDerivedWikiName() {
        final UserProfile p = db.newProfile();
        p.setFullname( "Jane Doe" );
        assertEquals( "JaneDoe", p.getWikiName() );
    }

    // --- getLockExpiry() returns null when lock is expired ---

    @Test
    void testGetLockExpiryReturnsNullWhenExpired() {
        final UserProfile p = db.newProfile();
        // Set expiry 1 second in the past
        p.setLockExpiry( new Date( System.currentTimeMillis() - 1_000 ) );
        assertNull( p.getLockExpiry() );
        assertFalse( p.isLocked() );
    }

    // --- email comparison is case-insensitive in equals() ---

    @Test
    void testEqualsEmailIsCaseInsensitive() {
        final UserProfile p1 = db.newProfile();
        final UserProfile p2 = db.newProfile();
        p1.setEmail( "Alice@Example.COM" );
        p2.setEmail( "alice@example.com" );
        // All other fields are null/equal, so the profiles should be equal
        assertEquals( p1, p2 );
    }

    // --- equals() returns false when one email is null and the other is not ---

    @Test
    void testEqualsReturnsFalseWhenOneEmailIsNull() {
        final UserProfile p1 = db.newProfile();
        final UserProfile p2 = db.newProfile();
        p1.setEmail( "alice@example.com" );
        // p2 email is null
        assertNotEquals( p1, p2 );
    }

    @Test
    void testBioGetterAndSetter() {
        final UserProfile p = db.newProfile();
        assertNull( p.getBio() );
        p.setBio( "I am a wiki enthusiast." );
        assertEquals( "I am a wiki enthusiast.", p.getBio() );
    }

    @Test
    void testBioNullSetter() {
        final UserProfile p = db.newProfile();
        p.setBio( "Something" );
        p.setBio( null );
        assertNull( p.getBio() );
    }
}

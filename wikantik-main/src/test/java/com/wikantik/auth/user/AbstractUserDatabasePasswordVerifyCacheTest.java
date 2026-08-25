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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the short-TTL successful-password-verification cache in
 * {@link AbstractUserDatabase#validatePassword(String, String)}. Uses the in-memory,
 * Docker-free {@link InMemoryUserDatabase} fixture so these run as plain unit tests.
 */
public class AbstractUserDatabasePasswordVerifyCacheTest {

    private static final String TTL_PROPERTY = "wikantik.auth.password.verifyCache.ttlSeconds";
    private static final String LOGIN = "cachetestuser";
    private static final String CORRECT_PASSWORD = "correct-horse-battery-staple";
    private static final String WRONG_PASSWORD = "definitely-not-it";

    @AfterEach
    void clearOverride() {
        System.clearProperty( TTL_PROPERTY );
    }

    /** Creates a user whose stored password is already bcrypt (no legacy-migration branch involved). */
    private InMemoryUserDatabase newDbWithBcryptUser() throws Exception {
        final InMemoryUserDatabase db = new InMemoryUserDatabase();
        final UserProfile profile = db.newProfile();
        profile.setLoginName( LOGIN );
        profile.setFullname( "Cache Test User" );
        profile.setEmail( "cachetestuser@example.com" );
        profile.setPassword( CORRECT_PASSWORD );
        db.save( profile );
        return db;
    }

    @Test
    void cacheHitAvoidsSecondBcryptVerify() throws Exception {
        final InMemoryUserDatabase db = newDbWithBcryptUser();

        assertTrue( db.validatePassword( LOGIN, CORRECT_PASSWORD ), "first call must verify" );
        assertTrue( db.validatePassword( LOGIN, CORRECT_PASSWORD ), "second call must also verify" );

        assertEquals( 1L, db.passwordVerifyCacheStats().hitCount(),
                "second call should have been satisfied from cache, not a fresh bcrypt verify" );
    }

    @Test
    void failuresAreNeverCached() throws Exception {
        final InMemoryUserDatabase db = newDbWithBcryptUser();

        assertFalse( db.validatePassword( LOGIN, WRONG_PASSWORD ) );
        assertFalse( db.validatePassword( LOGIN, WRONG_PASSWORD ) );
        assertFalse( db.validatePassword( LOGIN, WRONG_PASSWORD ) );

        assertEquals( 0L, db.passwordVerifyCacheStats().hitCount(),
                "a wrong password must never be served from cache" );
    }

    @Test
    void passwordChangeTakesEffectImmediately() throws Exception {
        final InMemoryUserDatabase db = newDbWithBcryptUser();

        // Populate the cache with the original password.
        assertTrue( db.validatePassword( LOGIN, CORRECT_PASSWORD ) );

        // Change the stored password.
        final UserProfile profile = db.findByLoginName( LOGIN );
        final String newPassword = "a-brand-new-password";
        profile.setPassword( newPassword );
        db.save( profile );

        // The OLD password must be rejected on the very next call — no TTL wait.
        assertFalse( db.validatePassword( LOGIN, CORRECT_PASSWORD ),
                "old password must be rejected immediately after a password change" );
        // The new password must work.
        assertTrue( db.validatePassword( LOGIN, newPassword ) );
    }

    @Test
    void killSwitchDisablesCaching() throws Exception {
        System.setProperty( TTL_PROPERTY, "0" );
        final InMemoryUserDatabase db = newDbWithBcryptUser();

        assertTrue( db.validatePassword( LOGIN, CORRECT_PASSWORD ) );
        assertTrue( db.validatePassword( LOGIN, CORRECT_PASSWORD ) );

        assertEquals( 0L, db.passwordVerifyCacheStats().hitCount(),
                "ttlSeconds<=0 must disable caching entirely" );
    }
}

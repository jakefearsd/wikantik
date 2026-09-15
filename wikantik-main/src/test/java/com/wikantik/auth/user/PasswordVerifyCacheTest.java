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

import com.wikantik.util.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link PasswordVerifyCache}'s TTL parsing and cache-key derivation — extracted out of
 * {@link AbstractUserDatabase} for the PMD complexity-gate burn-down. The end-to-end
 * (login/lockout/herd-collapsing) behaviour it backs is covered separately by
 * {@link AbstractUserDatabasePasswordVerifyCacheTest}.
 */
class PasswordVerifyCacheTest {

    private static final String TTL_PROPERTY = PasswordVerifyCache.TTL_PROPERTY;

    @AfterEach
    void clearOverride() {
        System.clearProperty( TTL_PROPERTY );
    }

    @Test
    void resolveTtlSecondsDefaultsWhenPropertyUnset() {
        System.clearProperty( TTL_PROPERTY );
        assertEquals( PasswordVerifyCache.DEFAULT_TTL_SECONDS, PasswordVerifyCache.resolveTtlSeconds() );
    }

    @Test
    void resolveTtlSecondsHonoursValidOverride() {
        System.setProperty( TTL_PROPERTY, "120" );
        assertEquals( 120L, PasswordVerifyCache.resolveTtlSeconds() );
    }

    @Test
    void resolveTtlSecondsFallsBackToDefaultOnInvalidValue() {
        System.setProperty( TTL_PROPERTY, "not-a-number" );
        assertEquals( PasswordVerifyCache.DEFAULT_TTL_SECONDS, PasswordVerifyCache.resolveTtlSeconds() );
    }

    @Test
    void disabledCacheAlwaysReturnsEmptySoCallersFallThrough() {
        final PasswordVerifyCache cache = new PasswordVerifyCache( 0 );
        final String hash = CryptoUtil.getBcryptHash( "hunter2".getBytes( StandardCharsets.UTF_8 ) );

        assertEquals( Optional.empty(), cache.tryVerify( "someone", "hunter2", hash ) );
        assertEquals( 0L, cache.stats().hitCount() );
    }

    @Test
    void hitCachesSuccessfulVerification() {
        final PasswordVerifyCache cache = new PasswordVerifyCache( 60 );
        final String password = "correct-horse-battery-staple";
        final String hash = CryptoUtil.getBcryptHash( password.getBytes( StandardCharsets.UTF_8 ) );

        assertEquals( Optional.of( Boolean.TRUE ), cache.tryVerify( "login", password, hash ) );
        assertEquals( Optional.of( Boolean.TRUE ), cache.tryVerify( "login", password, hash ) );
        assertEquals( 1L, cache.stats().hitCount(), "second call must be satisfied from cache" );
    }

    @Test
    void wrongPasswordIsNeverCached() {
        final PasswordVerifyCache cache = new PasswordVerifyCache( 60 );
        final String hash = CryptoUtil.getBcryptHash( "correct".getBytes( StandardCharsets.UTF_8 ) );

        assertEquals( Optional.of( Boolean.FALSE ), cache.tryVerify( "login", "wrong", hash ) );
        assertEquals( Optional.of( Boolean.FALSE ), cache.tryVerify( "login", "wrong", hash ) );
        assertEquals( 0L, cache.stats().hitCount(), "a wrong password must never be served from cache" );
    }

    @Test
    void differentLoginNamesForTheSameHashDoNotShareACacheEntry() {
        final PasswordVerifyCache cache = new PasswordVerifyCache( 60 );
        final String password = "shared-secret";
        final String hash = CryptoUtil.getBcryptHash( password.getBytes( StandardCharsets.UTF_8 ) );

        cache.tryVerify( "alice", password, hash );
        cache.tryVerify( "bob", password, hash );

        assertEquals( 0L, cache.stats().hitCount(), "distinct login names must derive distinct cache keys" );
    }

    @Test
    void changingTheStoredHashChangesTheCacheKey() {
        final PasswordVerifyCache cache = new PasswordVerifyCache( 60 );
        final String password = "same-password-different-account-state";
        final String originalHash = CryptoUtil.getBcryptHash( password.getBytes( StandardCharsets.UTF_8 ) );
        final String rehash = CryptoUtil.getBcryptHash( password.getBytes( StandardCharsets.UTF_8 ) );

        cache.tryVerify( "login", password, originalHash );
        cache.tryVerify( "login", password, rehash );

        assertEquals( 0L, cache.stats().hitCount(), "a different stored hash (e.g. after a password change) must miss the cache" );
    }
}

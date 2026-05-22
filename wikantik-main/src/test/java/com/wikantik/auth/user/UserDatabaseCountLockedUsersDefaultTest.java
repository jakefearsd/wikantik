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
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link UserDatabase#countLockedUsers()} <em>default</em> implementation in isolation,
 * driving it through a minimal in-memory {@link UserDatabase} that backs only the two methods the
 * default body relies on ({@link UserDatabase#getWikiNames()} and {@link UserDatabase#findByWikiName(String)}).
 */
class UserDatabaseCountLockedUsersDefaultTest {

    /** Minimal UserDatabase exercising only the seams the default countLockedUsers() touches. */
    private static final class InMemoryUserDatabase implements UserDatabase {
        private final Map< String, UserProfile > byWikiName = new LinkedHashMap<>();

        void put( final String wikiName, final UserProfile profile ) {
            byWikiName.put( wikiName, profile );
        }

        @Override
        public Principal[] getWikiNames() {
            return byWikiName.keySet().stream()
                    .map( name -> new WikiPrincipal( name, WikiPrincipal.WIKI_NAME ) )
                    .toArray( Principal[]::new );
        }

        @Override
        public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
            final UserProfile profile = byWikiName.get( index );
            if( profile == null ) {
                throw new NoSuchPrincipalException( "No such wiki name: " + index );
            }
            return profile;
        }

        // ----- unused members; not exercised by the default countLockedUsers() -----
        @Override public void deleteByLoginName( final String loginName ) { }
        @Override public Principal[] getPrincipals( final String identifier ) { return new Principal[0]; }
        @Override public UserProfile find( final String index ) throws NoSuchPrincipalException { throw new NoSuchPrincipalException( index ); }
        @Override public UserProfile findByEmail( final String index ) throws NoSuchPrincipalException { throw new NoSuchPrincipalException( index ); }
        @Override public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException { throw new NoSuchPrincipalException( index ); }
        @Override public UserProfile findByUid( final String uid ) throws NoSuchPrincipalException { throw new NoSuchPrincipalException( uid ); }
        @Override public UserProfile findByFullName( final String index ) throws NoSuchPrincipalException { throw new NoSuchPrincipalException( index ); }
        @Override public void initialize( final Engine engine, final Properties props ) { }
        @Override public UserProfile newProfile() { return mock( UserProfile.class ); }
        @Override public void rename( final String loginName, final String newName ) { }
        @Override public void save( final UserProfile profile ) { }
        @Override public boolean validatePassword( final String loginName, final String password ) { return false; }
    }

    private static UserProfile profileWithLockExpiry( final Date lockExpiry ) {
        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLockExpiry() ).thenReturn( lockExpiry );
        return profile;
    }

    @Test
    void countsOnlyProfilesWithFutureLockExpiry() throws WikiSecurityException {
        final InMemoryUserDatabase db = new InMemoryUserDatabase();
        db.put( "FutureLocked", profileWithLockExpiry( new Date( System.currentTimeMillis() + 3_600_000L ) ) );
        db.put( "PastLocked",   profileWithLockExpiry( new Date( System.currentTimeMillis() - 3_600_000L ) ) );
        db.put( "NeverLocked",  profileWithLockExpiry( null ) );

        assertEquals( 1L, db.countLockedUsers(), "only the future lock_expiry should count as locked" );
    }

    @Test
    void countsZeroWhenNobodyIsLocked() throws WikiSecurityException {
        final InMemoryUserDatabase db = new InMemoryUserDatabase();
        db.put( "AlphaUser", profileWithLockExpiry( null ) );
        db.put( "BetaUser",  profileWithLockExpiry( new Date( System.currentTimeMillis() - 1L ) ) );

        assertEquals( 0L, db.countLockedUsers() );
    }
}

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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link UserDatabase#findAllProfiles()} <em>default</em> implementation in isolation,
 * driving it through a minimal in-memory {@link UserDatabase} that backs only the two methods the
 * default body relies on ({@link UserDatabase#getWikiNames()} and {@link UserDatabase#findByWikiName(String)}).
 */
class UserDatabaseFindAllProfilesDefaultTest {

    /** Minimal UserDatabase exercising only the seams the default findAllProfiles() touches. */
    private static final class InMemoryUserDatabase implements UserDatabase {
        private final Map< String, UserProfile > byWikiName = new LinkedHashMap<>();
        private final Set< String > vanished = new java.util.HashSet<>();

        void put( final String wikiName, final UserProfile profile ) {
            byWikiName.put( wikiName, profile );
        }

        void putVanished( final String wikiName ) {
            vanished.add( wikiName );
        }

        @Override
        public Principal[] getWikiNames() {
            final java.util.List< String > names = new java.util.ArrayList<>( byWikiName.keySet() );
            names.addAll( vanished );
            return names.stream()
                    .map( name -> new WikiPrincipal( name, WikiPrincipal.WIKI_NAME ) )
                    .toArray( Principal[]::new );
        }

        @Override
        public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
            if( vanished.contains( index ) ) {
                throw new NoSuchPrincipalException( "Vanished wiki name: " + index );
            }
            final UserProfile profile = byWikiName.get( index );
            if( profile == null ) {
                throw new NoSuchPrincipalException( "No such wiki name: " + index );
            }
            return profile;
        }

        // ----- unused members; not exercised by the default findAllProfiles() -----
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

    private static UserProfile profileWithLoginName( final String loginName ) {
        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLoginName() ).thenReturn( loginName );
        return profile;
    }

    @Test
    void defaultImplementationResolvesEveryWikiName() throws Exception {
        final InMemoryUserDatabase db = new InMemoryUserDatabase();
        db.put( "WikiOne", profileWithLoginName( "u1" ) );
        db.put( "WikiTwo", profileWithLoginName( "u2" ) );

        final Collection< UserProfile > all = db.findAllProfiles();
        assertEquals( 2, all.size() );
        assertEquals( Set.of( "u1", "u2" ),
            all.stream().map( UserProfile::getLoginName ).collect( Collectors.toSet() ) );
    }

    @Test
    void vanishedProfileIsSkippedNotFatal() throws Exception {
        final InMemoryUserDatabase db = new InMemoryUserDatabase();
        db.put( "WikiOne", profileWithLoginName( "u1" ) );
        db.put( "WikiTwo", profileWithLoginName( "u2" ) );
        db.putVanished( "WikiThree" );

        assertEquals( 2, db.findAllProfiles().size() );
    }
}

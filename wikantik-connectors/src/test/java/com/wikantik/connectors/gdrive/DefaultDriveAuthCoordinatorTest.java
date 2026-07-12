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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.CredentialStore;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultDriveAuthCoordinatorTest {

    static final class FakeOAuth implements DriveOAuthService {
        boolean throwOnExchange = false;
        public String authorizationUrl( String cid, String redirect, String state ) {
            return "https://accounts.google.com/o/oauth2/auth?client_id=" + cid + "&state=" + state;
        }
        public String exchangeCodeForRefreshToken( String cid, String csec, String redirect, String code ) throws IOException {
            if ( throwOnExchange ) throw new IOException( "bad code" );
            return "REFRESH-for-" + code;
        }
    }
    /** In-memory store; enabled togglable. */
    static final class FakeStore implements CredentialStore {
        final Map<String,String> saved = new HashMap<>();
        boolean enabled = true;
        public boolean enabled() { return enabled; }
        public void put( String id, String name, String secret ) { saved.put( id + "/" + name, secret ); }
        public Optional<String> get( String id, String name ) { return Optional.ofNullable( saved.get( id + "/" + name ) ); }
        public List<String> list( String id ) { return List.of(); }
        public void delete( String id, String name ) { saved.remove( id + "/" + name ); }
    }
    static DriveConfig cfg() { return new DriveConfig( List.of( "root" ), 500, "cid", "csec", "https://w/cb", "text/markdown" ); }

    @Test void authorizationUrlForKnownIdElseEmpty() {
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), new FakeStore() );
        assertTrue( c.authorizationUrl( "gd", "st8" ).orElseThrow().contains( "state=st8" ) );
        assertTrue( c.authorizationUrl( "unknown", "st8" ).isEmpty() );
    }
    @Test void completeAuthorizationExchangesAndStoresRefreshToken() {
        FakeStore store = new FakeStore();
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), store );
        assertTrue( c.completeAuthorization( "gd", "CODE1" ) );
        assertEquals( "REFRESH-for-CODE1", store.get( "gd", "refresh_token" ).orElseThrow() );
    }
    @Test void exchangeFailureReturnsFalseAndStoresNothing() {
        FakeStore store = new FakeStore();
        FakeOAuth oauth = new FakeOAuth(); oauth.throwOnExchange = true;
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), oauth, store );
        assertFalse( c.completeAuthorization( "gd", "CODE1" ) );
        assertTrue( store.get( "gd", "refresh_token" ).isEmpty() );
    }
    @Test void disabledStoreReturnsFalse() {
        FakeStore store = new FakeStore(); store.enabled = false;
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), store );
        assertFalse( c.completeAuthorization( "gd", "CODE1" ) );
        assertTrue( store.get( "gd", "refresh_token" ).isEmpty() );
    }
    @Test void unknownIdCompleteReturnsFalse() {
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), new FakeStore() );
        assertFalse( c.completeAuthorization( "nope", "CODE1" ) );
    }
}

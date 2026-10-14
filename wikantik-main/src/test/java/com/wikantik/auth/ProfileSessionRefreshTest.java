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
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A profile saved outside the user's own session must show up in that user's live session at once. */
class ProfileSessionRefreshTest {

    private TestEngine engine;
    private SessionMonitor monitor;
    private UserDatabase db;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        monitor = SessionMonitor.getInstance( engine );
        db = engine.getManager( UserManager.class ).getUserDatabase();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private Session login( final String sessionId, final String login, final String password ) throws Exception {
        final Session session = monitor.find( sessionId );
        engine.getManager( AuthenticationManager.class ).login( session, HttpMockFactory.createHttpRequest(), login, password );
        assertTrue( session.isAuthenticated(), login + " must be authenticated" );
        return session;
    }

    private void rename( final String login, final String newFullName ) throws Exception {
        final UserProfile p = db.findByLoginName( login );
        p.setFullname( newFullName );
        db.save( p );
    }

    private static Set< String > names( final Session session ) {
        return Arrays.stream( session.getPrincipals() ).map( Principal::getName ).collect( Collectors.toSet() );
    }

    @Test
    void refreshProfileUpdatesThatUsersLivePrincipalsOnly() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );   // "Fred Flintstone" / FredFlintstone
        final Session bob = login( "refresh-bob", Users.BOB, Users.BOB_PASS );
        assertEquals( "Fred Flintstone", fred.getUserPrincipal().getName() );

        rename( Users.FRED, "Fred Astaire" );
        rename( Users.BOB, "Bobby Tables" );
        assertEquals( "Fred Flintstone", fred.getUserPrincipal().getName(), "stale until refreshed" );

        assertEquals( 1, monitor.refreshProfile( Users.FRED ) );

        assertEquals( "Fred Astaire", fred.getUserPrincipal().getName(), "the new display name applies immediately" );
        assertEquals( Users.FRED, fred.getLoginPrincipal().getName(), "the login principal never changes" );
        assertTrue( fred.isAuthenticated() );
        assertTrue( names( fred ).containsAll( Set.of( "Fred Astaire", "FredAstaire", Users.FRED ) ), names( fred ).toString() );
        assertFalse( names( fred ).contains( "Fred Flintstone" ) || names( fred ).contains( "FredFlintstone" ),
                "the stale full/wiki name principals must be gone: " + names( fred ) );
        assertEquals( "Bob", bob.getUserPrincipal().getName(), "another user's session is not touched" );
    }

    @Test
    void refreshProfileIgnoresGuestsAndUnknownLogins() throws Exception {
        monitor.find( "refresh-guest" );
        login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        assertEquals( 0, monitor.refreshProfile( "nobody" ) );
        assertEquals( 0, monitor.refreshProfile( null ) );
        assertEquals( 1, monitor.refreshProfile( Users.FRED ), "only the authenticated session counts" );
    }

    @Test
    void aBlankDisplayNameFallsBackToTheLoginPrincipal() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        rename( Users.FRED, "" );
        monitor.refreshProfile( Users.FRED );
        assertEquals( Users.FRED, fred.getUserPrincipal().getName() );
    }

    @Test
    void profileSavedRefreshesAndNeverThrows() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        rename( Users.FRED, "Fred Astaire" );

        ProfileChangeNotifier.profileSaved( engine, Users.FRED );
        assertEquals( "Fred Astaire", fred.getUserPrincipal().getName() );

        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( null, Users.FRED ) );
        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( engine, null ) );
        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( engine, "  " ) );
    }

    @Test
    void profileSavedAlsoInvalidatesThePublicIdentityCache() throws Exception {
        final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
        assertEquals( "Fred Flintstone", identity.displayName( Users.FRED ) );
        rename( Users.FRED, "Fred Astaire" );
        assertEquals( "Fred Flintstone", identity.displayName( Users.FRED ), "still served from the 60 s snapshot" );

        ProfileChangeNotifier.profileSaved( engine, Users.FRED );

        assertEquals( "Fred Astaire", identity.displayName( Users.FRED ), "a profile save must not wait for the TTL" );
    }

    @Test
    void profileDeletedInvalidatesThePublicIdentityCache() throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( "gone-user" );
        p.setFullname( "Gone User" );
        p.setPassword( "correct-horse-battery-staple-6" );
        db.save( p );
        final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
        assertEquals( "Gone User", identity.displayName( "gone-user" ) );

        db.deleteByLoginName( "gone-user" );
        ProfileChangeNotifier.profileDeleted( engine, "gone-user" );

        assertEquals( "gone-user", identity.displayName( "gone-user" ),
                "no longer an account: a non-email value is an ordinary string and passes through" );
    }
}

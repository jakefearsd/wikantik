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
package com.wikantik.auth.identity;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.WikiEngine;
import com.wikantik.auth.ProfileChangeNotifier;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.Users;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PublicIdentitiesTest {

    @Test
    void aNullOrNonWikiEngineGetsTheDatabaseLessFallback() {
        for ( final Engine engine : new Engine[] { null, Mockito.mock( Engine.class ) } ) {
            final PublicIdentity identity = PublicIdentities.forEngine( engine );
            assertNotNull( identity );
            assertEquals( "Jake Fear", identity.displayName( "Jake Fear" ) );
            assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "x@y.example" ) );
        }
    }

    @Test
    void aRealEngineGetsTheWiredService() {
        final TestEngine engine = TestEngine.build();
        try {
            final PublicIdentity wired = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            assertNotNull( wired, "AuthSubsystemFactory wires one" );
            assertSame( wired, PublicIdentities.forEngine( engine ) );
            assertEquals( "Fred Flintstone", wired.displayName( Users.FRED ), "login -> the seeded account's full name" );
        } finally {
            engine.stop();
        }
    }

    /** Puts the engine back into its mid-initialisation state: managers registered, typed auth snapshot not yet built. */
    private static AuthSubsystem.Services clearAuthSnapshot( final WikiEngine engine ) throws ReflectiveOperationException {
        final Field field = WikiEngine.class.getDeclaredField( "authSubsystem" );
        field.setAccessible( true );
        final AuthSubsystem.Services wired = ( AuthSubsystem.Services ) field.get( engine );
        field.set( engine, null );
        return wired;
    }

    @Test
    void beforeTheAuthSnapshotIsSetEveryLookupSharesTheEnginesOneIdentity() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            final PublicIdentity wired = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            clearAuthSnapshot( engine );

            final PublicIdentity first = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            final PublicIdentity second = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            assertSame( first, second, "a throwaway per lookup would load the account table once per lookup" );
            assertSame( wired, first, "and it is the instance the engine goes on to wire" );
            assertSame( first, PublicIdentities.forEngine( engine ) );

            // A profile change reaches the instance the fallback returned.
            assertEquals( "Fred Flintstone", first.displayName( Users.FRED ) );
            final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
            final UserProfile fred = db.findByLoginName( Users.FRED );
            fred.setFullname( "Fred Astaire" );
            db.save( fred );
            assertEquals( "Fred Flintstone", first.displayName( Users.FRED ), "still inside the snapshot" );
            ProfileChangeNotifier.profileSaved( engine, Users.FRED );
            assertEquals( "Fred Astaire", first.displayName( Users.FRED ) );
        } finally {
            engine.stop();
        }
    }

    @Test
    void theIdentityIsPerEngine() {
        final TestEngine one = TestEngine.build();
        final TestEngine two = TestEngine.build();
        try {
            assertNotSame( AuthSubsystemBridge.fromLegacyEngine( one ).publicIdentity(),
                    AuthSubsystemBridge.fromLegacyEngine( two ).publicIdentity() );
        } finally {
            two.stop();
            one.stop();
        }
    }
}

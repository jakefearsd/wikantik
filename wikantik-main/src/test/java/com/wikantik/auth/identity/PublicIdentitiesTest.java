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
import com.wikantik.auth.Users;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}

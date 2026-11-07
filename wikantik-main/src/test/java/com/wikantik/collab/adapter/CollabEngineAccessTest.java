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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.testing.InMemoryCollabStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** MultiEdit Phase 3 Task 8: the static answers the page surfaces read, and their unwired defaults. */
class CollabEngineAccessTest {

    private static final String CID = "01HZZZZZZZZZZZZZZZZZZZZZZZ";

    @Test
    void draftActiveIsFalseWhenUnwired() {
        final TestEngine engine = TestEngine.build();
        try {
            assertFalse( CollabEngineAccess.draftActive( engine, "AnyPage" ) );
            assertTrue( CollabEngineAccess.contributorsByVersion( engine, CID ).isEmpty() );
        } finally {
            engine.stop();
        }
        // An engine that is not a WikiEngine, and a null engine, are "not wired" too.
        assertFalse( CollabEngineAccess.draftActive( mock( Engine.class ), "AnyPage" ) );
        assertTrue( CollabEngineAccess.contributorsByVersion( mock( Engine.class ), CID ).isEmpty() );
        assertFalse( CollabEngineAccess.draftActive( null, "AnyPage" ) );
    }

    @Test
    void draftActiveAndContributorsComeFromTheWiredRegistry() throws Exception {
        final TestEngine engine = TestEngine.build();
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabWiring wiring = CollabTestWiring.wire( engine, store );
        try {
            engine.saveText( "CollabAccessPage", "hello\n" );
            assertFalse( CollabEngineAccess.draftActive( engine, "CollabAccessPage" ) );

            final Session admin = engine.adminSession();
            final String key = AuthorKeys.authorKeyFor( admin );
            assertInstanceOf( Joined.class, CollabEngineAccess.registry( engine ).join( "CollabAccessPage",
                new JoinRequest( key, null, admin, "Admin", null, null ) ) );
            assertTrue( CollabEngineAccess.draftActive( engine, "CollabAccessPage" ), "a participant makes it active" );

            store.recordContributors( CID, 2, Set.of( "bob", "alice" ), true );
            assertEquals( Map.of( 2, List.of( "alice", "bob" ) ), CollabEngineAccess.contributorsByVersion( engine, CID ) );
        } finally {
            wiring.close();
            engine.stop();
        }
    }
}

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
package com.wikantik.collab;

import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Final review item 2: a push the server stored whose answer was lost must not come back to its author as "text that
 * did not reach the draft" after a restart or an idle unload. The rejoin names its prior clientID; the session reloaded
 * from the store reclaims it when the log holds that clientID's updates under the same author, so the client's
 * unconfirmed updates arrive as confirmations instead of a resync. Author-bound: nobody adopts another's clientID.
 */
class CollabClientIdReclaimTest {

    private static final String PAGE = "Page";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Everyone may edit and create; the pages carry no access rules. */
    private static final CollabPermissions ALL = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, "hello\n", Map.of() );
        clock = new MutableClock( T0 );
    }

    /** A registry on the shared store: a new one is a restarted node, its sessions reloaded from the store. */
    private CollabSessionRegistry node() {
        return new CollabSessionRegistry( store, writer, ALL, key -> null, clock,
            new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private static Joined join( final CollabSessionRegistry registry, final String author, final String prior ) {
        return assertInstanceOf( Joined.class, registry.join( PAGE,
            new JoinRequest( author, prior, author, author, null, null ) ) );
    }

    /** Alice's push is stored (its answer is lost to her); then the node restarts. */
    private Joined aliceTypedThenTheNodeRestarted() {
        final Joined alice = join( node(), "alice", null );
        final CollabSession.View v = alice.session().view();
        assertEquals( 200, alice.session().push( alice.response().participantId(), "alice", v.head(),
            List.of( new Update( alice.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, "typed " ) ) ) )
            .status() );
        clock.advance( Duration.ofSeconds( 1 ) );   // a new load: a new id tag
        return alice;
    }

    @Test
    void aRejoinAfterAReloadReclaimsItsClientIdAndItsUpdatesComeBackAsConfirmations() {
        final Joined before = aliceTypedThenTheNodeRestarted();
        final CollabSessionRegistry restarted = node();

        final Joined again = join( restarted, "alice", before.response().clientId() );

        assertEquals( before.response().clientId(), again.response().clientId(), "no resync: the same clientID" );
        assertEquals( before.response().sessionId(), again.response().sessionId() );
        // the client still holds its push as unconfirmed at version 0: pulling from there confirms it
        final PullResponse pulled = again.session().pullOrPark( again.response().participantId(), "alice",
            new PullCursor( 0, 0, 0, 0 ), null, r -> { } );
        assertEquals( 200, pulled.status() );
        assertEquals( List.of( before.response().clientId() ),
            pulled.updates().stream().map( Update::clientId ).toList() );
    }

    @Test
    void anotherAuthorNeverAdoptsTheClientId() {
        final Joined before = aliceTypedThenTheNodeRestarted();
        final CollabSessionRegistry restarted = node();

        final Joined bob = join( restarted, "bob", before.response().clientId() );

        assertNotEquals( before.response().clientId(), bob.response().clientId() );
        // and alice still reclaims hers afterwards
        assertEquals( before.response().clientId(), join( restarted, "alice", before.response().clientId() )
            .response().clientId() );
    }

    @Test
    void aClientIdUnknownToTheLogIsNotReclaimed() {
        aliceTypedThenTheNodeRestarted();
        final CollabSessionRegistry restarted = node();

        final Joined stale = join( restarted, "alice", "no-such-client" );

        assertNotEquals( "no-such-client", stale.response().clientId() );
    }
}

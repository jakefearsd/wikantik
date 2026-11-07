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
package com.wikantik.collab.testing;

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PublishResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two publish-attribution defects the Phase 3 Task 6 fuzz (invariants 10 and 11) found, as direct scenarios; their
 * shrunk traces are replayed by {@link CollabRegressionTracesTest} from {@code collab/regressions/session/}.
 *
 * <ul>
 *   <li><b>D1</b> (AGENT_HEAVY seed 179): a save that stores nothing new ({@code PublishOutcome.unchanged}: normalising
 *   or a save filter made the text the latest version's) must credit nobody and leave the existing version's rows alone
 *   (ruling R2: rewrites credit nobody); the absorbed edits' authors stay pending for the next real publish.</li>
 *   <li><b>D2</b> (PUBLISH_STORM seed 103, TIGHT seed 2): a publish whose ledger rows were written before a failed commit
 *   is claimed on retry by the version its save wrote (stored on the marker), never by S_V's content hash, which a
 *   save filter breaks; when the page has moved on and the version is no longer the publisher's, the early rows are
 *   removed, so credit never duplicates.</li>
 * </ul>
 */
class PublishAttributionTest {

    private static final String PAGE = "P";
    private static final String CID = "p-id";
    private static final String A = "author-a";
    private static final String B = "author-b";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    private ScriptedPageWriter writer;
    private InMemoryCollabStore store;

    @BeforeEach
    void setUp() {
        writer = new ScriptedPageWriter();
        store = new InMemoryCollabStore();
    }

    private CollabSessionSimServer server( final String body, final String rewrite ) {
        writer.seed( PAGE, body, Map.of( "canonical_id", CID ) );
        writer.rewriteOnSave( SimEnv.rewrite( rewrite ) );
        return CollabSessionSimServer.create( PAGE, store, writer, new MutableClock( T0 ), 64 * 1024, 4, 500 );
    }

    private CollabSessionSimServer reloaded() {
        return CollabSessionSimServer.reload( PAGE, store, writer, new MutableClock( T0 ), 64 * 1024, 4, 500 );
    }

    /** Inserts {@code text} at {@code at} as {@code author}'s participant, on the head; asserts it is accepted. */
    private static void type( final SimServer s, final Joined j, final String author, final int at, final String text ) {
        final InvariantView v = s.view();
        assertEquals( 200, s.push( j.participantId(), author, v.head(), List.of( new Update( j.clientId(),
            ChangeSet.replace( v.doc().length(), at, at, text ) ) ) ).status() );
    }

    private SessionRecord record( final SimServer s ) {
        return store.session( s.view().sessionId() ).orElseThrow();
    }

    private String stored() {
        return CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() );
    }

    // ---- D1 ------------------------------------------------------------------------------------------------------

    @Test
    void aSaveThatStoresNothingNewLeavesTheExistingVersionsRows() {
        final CollabSessionSimServer s = server( "hello\n", "strip-trailing-spaces" );
        final Joined a = s.join( A );
        type( s, a, A, 5, "X" );
        assertEquals( 200, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status() );
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ) );
        final Joined b = s.join( B );
        type( s, b, B, 6, " " );   // a trailing space: the save filter strips it, so the save stores version 2's text

        final PublishResult r = s.publish( b.participantId(), B, s.view().head(), "spaces" );

        assertEquals( 200, r.status(), r.reason() );
        assertEquals( 2, r.pageVersion(), "answered as today: the latest version" );
        assertEquals( 2, writer.latest( PAGE ).orElseThrow().version(), "nothing new was stored" );
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ), "version 2 keeps the rows of the publish that wrote it" );
        assertEquals( Set.of( B ), s.contributors(), "bob's absorbed edit was not published: he stays pending" );
        assertNull( record( s ).pendingPublishVersion() );
    }

    @Test
    void anAbsorbedEditsAuthorIsCreditedByTheNextRealPublish() {
        final CollabSessionSimServer s = server( "hello\n", "strip-trailing-spaces" );
        final Joined b = s.join( B );
        type( s, b, B, 5, " " );
        assertEquals( 200, s.publish( b.participantId(), B, s.view().head(), "spaces" ).status() );
        assertEquals( Map.of(), s.ledger( CID ), "the unchanged save credits nobody" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );

        assertEquals( 200, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status() );

        assertEquals( Map.of( 2, List.of( A, B ) ), s.ledger( CID ) );
        assertEquals( Set.of(), s.contributors() );
    }

    // ---- D2 ------------------------------------------------------------------------------------------------------

    @Test
    void anUnfinishedPublishWhoseSaveWasRewrittenIsFinishedOnRetry() {
        final CollabSessionSimServer s = server( "hello \n", "strip-trailing-spaces" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertEquals( 503, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status(),
            "the save succeeded but its commit failed: owed" );
        assertEquals( 2, record( s ).pendingPublishSavedVersion(), "the version the save wrote is on the marker" );

        s.settle();

        assertNull( record( s ).pendingPublishVersion() );
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ) );
        assertEquals( Set.of(), s.contributors(), "the publish of version 2 credited alice: she is not owed it again" );
        assertEquals( "Xhello\n", stored() );
        assertEquals( stored(), s.view().doc(), "the rewrite adopted as the publisher's own: nothing applied twice" );
        assertEquals( 2, s.view().basePageVersion() );
    }

    @Test
    void aRewrittenUnfinishedPublishIsClaimedByItsSavedVersionAfterACrash() {
        final CollabSessionSimServer s = server( "hello \n", "strip-trailing-spaces" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertEquals( 503, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status() );

        final CollabSessionSimServer after = reloaded();   // the in-memory ticket is gone; the stored marker is not
        after.settle();

        assertNull( record( after ).pendingPublishVersion() );
        assertEquals( Map.of( 2, List.of( A ) ), after.ledger( CID ) );
        assertEquals( Set.of(), after.contributors() );
        assertEquals( stored(), after.view().doc(), "claimed, not merged as an outside write onto its own edits" );
        assertEquals( 2, after.view().basePageVersion() );
    }

    @Test
    void aPublishLostWithItsPageDoesNotKeepItsRowsAndItsContributors() {
        final CollabSessionSimServer s = server( "hello\n", "none" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertEquals( 503, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status() );
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ), "credit is written before the commit" );
        writer.delete( PAGE );
        s.deleted();
        final int recreated = writer.externalSave( PAGE, s.view().doc(), Map.of( "canonical_id", CID ), "recreator" );
        s.externalSaved( recreated );

        s.settle();

        assertNull( record( s ).pendingPublishVersion() );
        assertEquals( Map.of(), s.ledger( CID ), "the unclaimed publish's early rows are gone" );
        assertTrue( s.contributors().contains( A ), "and alice is still owed her credit" );
    }
}

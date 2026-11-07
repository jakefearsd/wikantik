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

import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.api.pages.VersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubCollabAuthorityTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private StubCollabAuthority server;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of() );
        clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        server = new StubCollabAuthority( PAGE, store, writer, clock, 1000, 3 );
    }

    private static SimServer.Update typeAt( final SimServer.Joined j, final String doc, final int at, final String text ) {
        return new SimServer.Update( j.clientId(), ChangeSet.replace( doc.length(), at, at, text ) );
    }

    @Test
    void joinReturnsDocAndVersion() {
        final SimServer.Joined j = server.join( "alice" );
        assertEquals( BODY, j.doc() );
        assertEquals( 0, j.version() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void pushAcceptedAdvancesHeadAndComposesLocal() {
        final SimServer.Joined j = server.join( "alice" );
        final SimServer.PushResult r = server.push( j.participantId(), "alice", 0, List.of( typeAt( j, BODY, 0, "X" ) ) );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.version() );
        assertEquals( "Xhello\nworld\n", server.view().doc() );
        assertFalse( server.view().local().empty() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void E8_stalePushIs409() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        final SimServer.PushResult r = server.push( b.participantId(), "bob", 0, List.of( typeAt( b, BODY, 0, "Y" ) ) );
        assertEquals( 409, r.status() );
        assertEquals( 1, r.version() );
        assertEquals( "Xhello\nworld\n", server.view().doc() );
    }

    @Test
    void E10_duplicatedPushAppliedOnce() {
        final SimServer.Joined a = server.join( "alice" );
        final List< SimServer.Update > u = List.of( typeAt( a, BODY, 0, "X" ) );
        assertEquals( 200, server.push( a.participantId(), "alice", 0, u ).status() );
        assertEquals( 409, server.push( a.participantId(), "alice", 0, u ).status() );
        assertEquals( "Xhello\nworld\n", server.view().doc() );
        assertEquals( 1, server.view().head() );
    }

    @Test
    void E14_otherParticipantsClientIdIs400() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        final SimServer.PushResult r = server.push( b.participantId(), "bob", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        assertEquals( 400, r.status() );
        assertEquals( BODY, server.view().doc() );
        assertEquals( 0, server.view().head() );
        assertTrue( store.updatesSince( server.view().sessionId(), 0 ).isEmpty() );
    }

    @Test
    void E15_lengthMismatchIs400AndUnchanged() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Update bad = new SimServer.Update( a.clientId(), ChangeSet.replace( 3, 0, 0, "X" ) );
        final SimServer.PushResult r = server.push( a.participantId(), "alice", 0, List.of( bad ) );
        assertEquals( 400, r.status() );
        assertEquals( BODY, server.view().doc() );
        assertEquals( 0, server.view().head() );
    }

    @Test
    void loneSurrogateIs400() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.PushResult r = server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "\uD83D" ) ) );
        assertEquals( 400, r.status() );
        assertEquals( BODY, server.view().doc() );
    }

    @Test
    void splittingAnExistingPairIs400() {
        final SimServer.Joined a = server.join( "alice" );
        assertEquals( 200, server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "😀" ) ) ).status() );
        final String doc = server.view().doc();
        final SimServer.Update split = new SimServer.Update( a.clientId(), ChangeSet.replace( doc.length(), 1, 1, "x" ) );
        assertEquals( 400, server.push( a.participantId(), "alice", 1, List.of( split ) ).status() );
    }

    @Test
    void oversizePushIs413ButShrinkingIsAllowed() {
        final SimServer.Joined a = server.join( "alice" );
        assertEquals( 413, server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "x".repeat( 1000 ) ) ) ).status() );
        assertEquals( 0, server.view().head() );
        assertFalse( server.view().oversize() );
        assertEquals( 200, server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "x".repeat( 900 ) ) ) ).status() );
    }

    @Test
    void oversizeStateAllowsOnlyNonGrowingPushes() {
        // external merge is Phase 1; get over the limit by shrinking the limit instead
        final StubCollabAuthority tight = new StubCollabAuthority( "P2", store, seeded( "P2", "y".repeat( 20 ) ), clock, 10, 3 );
        final SimServer.Joined t = tight.join( "alice" );
        assertTrue( tight.view().oversize() );
        final String d = tight.view().doc();
        assertEquals( 413, tight.push( t.participantId(), "alice", 0, List.of( typeAt( t, d, 0, "z" ) ) ).status() );
        assertEquals( 200, tight.push( t.participantId(), "alice", 0,
            List.of( new SimServer.Update( t.clientId(), ChangeSet.replace( d.length(), 0, 1, "z" ) ) ) ).status() );
        CollabInvariants.check( tight.view(), true );
    }

    private ScriptedPageWriter seeded( final String page, final String body ) {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( page, body, Map.of() );
        return w;
    }

    @Test
    void pullBehindAnswersImmediately() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        final SimServer.PullResult r = server.pull( b.participantId(), "bob", 0 );
        assertEquals( 200, r.status() );
        assertFalse( r.parked() );
        assertEquals( 1, r.version() );
        assertEquals( 1, r.updates().size() );
        assertEquals( a.clientId(), r.updates().get( 0 ).clientId() );
    }

    @Test
    void pullAtHeadParksAndPushWakesWithSlice() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        final SimServer.PullResult parked = server.pull( b.participantId(), "bob", 0 );
        assertTrue( parked.parked() );
        assertTrue( server.drainWakeups().isEmpty() );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        final List< SimServer.Wakeup > w = server.drainWakeups();
        assertEquals( 1, w.size() );
        assertEquals( b.participantId(), w.get( 0 ).participantId() );
        assertEquals( 1, w.get( 0 ).result().updates().size() );
        assertEquals( 1, w.get( 0 ).result().version() );
        assertTrue( server.drainWakeups().isEmpty() );
    }

    @Test
    void parkedPullTimesOutEmptyAfter25Seconds() {
        final SimServer.Joined b = server.join( "bob" );
        server.pull( b.participantId(), "bob", 0 );
        clock.advance( Duration.ofSeconds( 24 ) );
        server.tick();
        assertTrue( server.drainWakeups().isEmpty() );
        clock.advance( Duration.ofSeconds( 2 ) );
        server.tick();
        final List< SimServer.Wakeup > w = server.drainWakeups();
        assertEquals( 1, w.size() );
        assertEquals( 200, w.get( 0 ).result().status() );
        assertTrue( w.get( 0 ).result().updates().isEmpty() );
        server.tick();
        assertTrue( server.drainWakeups().isEmpty() );
    }

    @Test
    void E32_leavingCompletesParkedWaiterOnce() {
        final SimServer.Joined b = server.join( "bob" );
        server.pull( b.participantId(), "bob", 0 );
        server.leave( b.participantId() );
        assertEquals( 1, server.drainWakeups().size() );
        clock.advance( Duration.ofSeconds( 30 ) );
        server.tick();
        assertTrue( server.drainWakeups().isEmpty() );
    }

    @Test
    void E12_belowRingServedFromStoreWhenContiguous() {
        final SimServer.Joined a = server.join( "alice" );
        String doc = BODY;
        for( int i = 0; i < 5; i++ ) {
            assertEquals( 200, server.push( a.participantId(), "alice", i, List.of( typeAt( a, doc, 0, "a" ) ) ).status() );
            doc = "a" + doc;
        }
        final SimServer.PullResult r = server.pull( a.participantId(), "alice", 0 );
        assertEquals( 200, r.status() );
        assertEquals( 5, r.updates().size() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void E12_belowRingAndTruncatedStoreIs410() {
        final SimServer.Joined a = server.join( "alice" );
        String doc = BODY;
        for( int i = 0; i < 5; i++ ) {
            server.push( a.participantId(), "alice", i, List.of( typeAt( a, doc, 0, "a" ) ) );
            doc = "a" + doc;
        }
        store.truncateUpdatesBelow( server.view().sessionId(), 4 );
        CollabInvariants.check( server.view(), false );
        assertEquals( 410, server.pull( a.participantId(), "alice", 0 ).status() );
        assertEquals( 200, server.pull( a.participantId(), "alice", 3 ).status() );
    }

    @Test
    void storeFailureIs503AndMemoryUnchangedThenRetrySucceeds() {
        final SimServer.Joined a = server.join( "alice" );
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        final List< SimServer.Update > u = List.of( typeAt( a, BODY, 0, "X" ) );
        assertEquals( 503, server.push( a.participantId(), "alice", 0, u ).status() );
        assertEquals( BODY, server.view().doc() );
        assertEquals( 0, server.view().head() );
        assertEquals( 200, server.push( a.participantId(), "alice", 0, u ).status() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void multiUpdatePushChainsAndAssignsContiguousVersions() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Update u1 = typeAt( a, BODY, 0, "X" );
        final SimServer.Update u2 = new SimServer.Update( a.clientId(),
            ChangeSet.replace( BODY.length() + 1, 0, 0, "Y" ) );
        final SimServer.PushResult r = server.push( a.participantId(), "alice", 0, List.of( u1, u2 ) );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.version() );
        assertEquals( "YXhello\nworld\n", server.view().doc() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void publishSavesDocResetsLocalAndAdvancesBase() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        final SimServer.PublishResult r = server.publish( a.participantId(), "alice", 1, "note" );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.pageVersion() );
        assertEquals( "Xhello\nworld\n", server.view().baseBody() );
        assertEquals( 2, server.view().basePageVersion() );
        assertTrue( server.view().local().empty() );
        assertEquals( "Xhello\nworld\n", server.view().doc() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void publishWithStaleVersionIs409() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        assertEquals( 409, server.publish( a.participantId(), "alice", 0, "n" ).status() );
        assertEquals( 1, writer.latest( PAGE ).orElseThrow().version() );
    }

    @Test
    void E21_publishWithNoChangesIsUnchanged() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.PublishResult r = server.publish( a.participantId(), "alice", 0, "n" );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.pageVersion() );
        assertEquals( 1, writer.latest( PAGE ).orElseThrow().version() );
    }

    @Test
    void E1_publishingEmptyDraftIs400() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of(
            new SimServer.Update( a.clientId(), ChangeSet.replace( BODY.length(), 0, BODY.length(), "" ) ) ) );
        assertEquals( "", server.view().doc() );
        assertEquals( 400, server.publish( a.participantId(), "alice", 1, "n" ).status() );
    }

    @Test
    void publishRejectedByWriterLeavesDraftUnchanged() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        writer.failNextPublish( new VersionConflictException( PAGE, 1, 2, false ) );
        assertEquals( 409, server.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertEquals( 1, server.view().basePageVersion() );
        assertEquals( "Xhello\nworld\n", server.view().doc() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void E17_filterRewriteConvergesViaServerUpdate() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        writer.rewriteOnSave( s -> s.replace( "world", "WORLD" ) );
        assertEquals( 200, server.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertEquals( "Xhello\nWORLD\n", server.view().doc() );
        assertEquals( "Xhello\nWORLD\n", server.view().baseBody() );
        assertEquals( 2, server.view().head() );
        final SimServer.PullResult r = server.pull( b.participantId(), "bob", 1 );
        assertEquals( 1, r.updates().size() );
        assertEquals( "server", r.updates().get( 0 ).clientId() );
        assertEquals( "Xhello\nWORLD\n", r.updates().get( 0 ).changes().apply( "Xhello\nworld\n" ) );
        final StoredUpdate su = store.updatesSince( server.view().sessionId(), 1 ).get( 0 );
        assertEquals( "server", su.kind() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void unknownParticipantIs409() {
        assertEquals( 409, server.push( "nobody", "x", 0, List.of() ).status() );
        assertEquals( 409, server.pull( "nobody", "x", 0 ).status() );
        assertEquals( 409, server.publish( "nobody", "x", 0, "n" ).status() );
    }

    @Test
    void reasonsOnRefusals() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.Joined b = server.join( "bob" );
        final SimServer.PushResult ok = server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        assertEquals( null, ok.reason() );
        assertEquals( "stale-base", server.push( b.participantId(), "bob", 0, List.of() ).reason() );
        assertEquals( "rejoin", server.push( "nobody", "x", 0, List.of() ).reason() );
        assertEquals( "stale-base", server.publish( a.participantId(), "alice", 0, "n" ).reason() );
        assertEquals( "rejoin", server.pull( "nobody", "x", 0 ).reason() );
        assertEquals( null, server.pull( b.participantId(), "bob", 0 ).reason() );
    }

    @Test
    void A6_anotherAuthorsParticipantIs403() {
        final SimServer.Joined a = server.join( "alice" );
        final SimServer.PushResult r = server.push( a.participantId(), "mallory", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        assertEquals( 403, r.status() );
        assertEquals( "not-your-participant", r.reason() );
        assertEquals( 403, server.pull( a.participantId(), "mallory", 0 ).status() );
        assertEquals( "not-your-participant", server.publish( a.participantId(), "mallory", 0, "n" ).reason() );
        assertEquals( 0, server.view().head() );
    }

    @Test
    void E21_unchangedFlagOnPublishResult() {
        final SimServer.Joined a = server.join( "alice" );
        assertTrue( server.publish( a.participantId(), "alice", 0, "n" ).unchanged() );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        final SimServer.PublishResult r = server.publish( a.participantId(), "alice", 1, "n" );
        assertFalse( r.unchanged() );
        assertEquals( null, r.reason() );
    }

    @Test
    void secondParkedPullDisplacesFirstWhichIsAnsweredEmpty() {
        final SimServer.Joined b = server.join( "bob" );
        assertTrue( server.pull( b.participantId(), "bob", 0 ).parked() );
        assertTrue( server.pull( b.participantId(), "bob", 0 ).parked() );
        final List< SimServer.Wakeup > w = server.drainWakeups();
        assertEquals( 1, w.size() );
        assertTrue( w.get( 0 ).result().updates().isEmpty() );
        assertFalse( w.get( 0 ).result().parked() );
        clock.advance( Duration.ofSeconds( 30 ) );
        server.tick();
        assertEquals( 1, server.drainWakeups().size() );
    }

    @Test
    void sessionIdIsDeterministicPerPage() {
        final StubCollabAuthority other = new StubCollabAuthority( PAGE, new InMemoryCollabStore(), writer, clock, 1000, 3 );
        assertEquals( server.view().sessionId(), other.view().sessionId() );
    }

    @Test
    void storeFaultAfterPageSavePoisonsTheStub() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        writer.rewriteOnSave( s -> s.replace( "world", "WORLD" ) );
        store.failOn( InMemoryCollabStore.Op.WRITE_SNAPSHOT, 1 );
        final SimServer.PublishResult r = server.publish( a.participantId(), "alice", 1, "n" );
        assertEquals( 503, r.status() );
        assertTrue( server.view().poisoned() );
        assertTrue( server.view().publishPending() );
        CollabInvariants.check( server.view(), true );
        assertEquals( "poisoned", server.push( a.participantId(), "alice", 1, List.of() ).reason() );
        assertEquals( 503, server.pull( a.participantId(), "alice", 0 ).status() );
        assertEquals( "poisoned", server.pull( a.participantId(), "alice", 0 ).reason() );
        assertEquals( "poisoned", server.publish( a.participantId(), "alice", 1, "n" ).reason() );
    }

    @Test
    void persistThenFailPoisonsTheStub() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        writer.persistThenFail( new VersionConflictException( PAGE, 2, 1, false ) );
        assertEquals( 503, server.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertTrue( server.view().poisoned() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void oversizeIsSessionStateEnteredByPublishRewriteAndClearedWhenUnderTheLimit() {
        final SimServer.Joined a = server.join( "alice" );
        server.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "X" ) ) );
        assertFalse( server.view().oversize() );
        writer.rewriteOnSave( s -> s + "y".repeat( 2000 ) );
        assertEquals( 200, server.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertTrue( server.view().oversize() );
        final long ceiling = server.view().oversizeCeiling();
        assertTrue( ceiling > 1000 );
        CollabInvariants.check( server.view(), true );
        final String doc = server.view().doc();
        // growth refused
        assertEquals( 413, server.push( a.participantId(), "alice", server.view().head(),
            List.of( new SimServer.Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 0, "z" ) ) ) ).status() );
        // shrink but stay over: ceiling ratchets down
        assertEquals( 200, server.push( a.participantId(), "alice", server.view().head(),
            List.of( new SimServer.Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 500, "" ) ) ) ).status() );
        assertTrue( server.view().oversize() );
        assertEquals( ceiling - 500, server.view().oversizeCeiling() );
        CollabInvariants.check( server.view(), true );
        // shrink under the limit: state clears
        final String d2 = server.view().doc();
        assertEquals( 200, server.push( a.participantId(), "alice", server.view().head(),
            List.of( new SimServer.Update( a.clientId(), ChangeSet.replace( d2.length(), 0, 1200, "" ) ) ) ).status() );
        assertFalse( server.view().oversize() );
        CollabInvariants.check( server.view(), true );
    }
}

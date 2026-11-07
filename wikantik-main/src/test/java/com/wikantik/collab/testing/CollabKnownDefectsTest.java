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

import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PublishResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Product defects the Phase 3 Task 6 fuzz (invariants 10 and 11) found in {@code com.wikantik.collab} and that are not
 * fixed yet (product code belongs to other tasks). Each is pinned twice, as a direct scenario and as the fuzz's shrunk
 * trace (under {@code src/test/resources/collab/known-defects/session/}, outside the directories
 * {@link CollabRegressionTracesTest} replays), and the fuzz carries a narrow, documented tolerance for each so it stays
 * green on everything else. Once a defect is fixed: enable its tests, move its traces to
 * {@code collab/regressions/session/}, and delete the tolerance ({@link CollabInvariants#tolerateUnchangedSaveCredit},
 * {@link CollabInvariants#tolerateOrphanedRows}) and the scheduler code that reads it.
 */
class CollabKnownDefectsTest {

    private static final String PAGE = "P";
    private static final String CID = "p-id";
    private static final String A = "author-a";
    private static final String B = "author-b";

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
        return CollabSessionSimServer.create( PAGE, store, writer,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ), 64 * 1024, 4, 500 );
    }

    /** Inserts {@code text} at {@code at} as {@code author}'s participant, on the head; asserts it is accepted. */
    private static void type( final SimServer s, final Joined j, final String author, final int at, final String text ) {
        final InvariantView v = s.view();
        assertEquals( 200, s.push( j.participantId(), author, v.head(), List.of( new Update( j.clientId(),
            ChangeSet.replace( v.doc().length(), at, at, text ) ) ) ).status() );
    }

    private static SimTrace trace( final String name ) throws IOException, URISyntaxException {
        final Path p = Path.of( CollabKnownDefectsTest.class.getResource( "/collab/known-defects/session/" + name ).toURI() );
        return SimTrace.fromJson( Files.readString( p, StandardCharsets.UTF_8 ) );
    }

    private static void replaysCleanly( final String name ) throws Exception {
        final SimTrace t = SimTarget.SESSION.replay( trace( name ) );
        assertEquals( SimTrace.Status.PASS, t.outcome().status(), t.outcome().message() );
    }

    /** The tolerances absorb exactly these traces: with them on (the fuzz's setting) each replays cleanly. */
    @Test
    void theShrunkTracesPassUnderTheirTolerances() throws Exception {
        replaysCleanly( "agent_heavy-179-unchanged-save-credits-the-existing-version.json" );
        replaysCleanly( "publish_storm-103-rewritten-unfinished-publish-leaves-its-rows.json" );
        replaysCleanly( "tight-2-page-deleted-before-the-retry-leaves-its-rows.json" );
    }

    // ---- D1 ------------------------------------------------------------------------------------------------------

    /**
     * Defect D1: a publish whose save stores nothing new is finished as a publish of the page's existing version B, and
     * its contributors replace B's ledger rows. It happens whenever the draft's only differences vanish when the save
     * normalises the text ({@code TextUtil.normalizePostData}) or a save filter rewrites it: the writer answers
     * {@code PublishOutcome(B, ..., unchanged = true)}, and {@code PublishFlow.applyPublished} never reads
     * {@code unchanged}, so {@code credit(t, saved.version())} calls {@code recordContributors(canonicalId, B, ...)} (and
     * the client is answered 200 at B). The authors of B lose their credit to someone whose edit was thrown away. Fix:
     * on an unchanged outcome credit nobody (the rewrite took the edits out, R2), leaving B's rows alone.
     */
    @Disabled( "known product defect D1: an unchanged save credits the existing version B (PublishFlow.applyPublished"
        + " ignores PublishOutcome.unchanged)" )
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
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ), "version 2 keeps the rows of the publish that wrote it" );
    }

    @Disabled( "known product defect D1: an unchanged save credits the existing version B (PublishFlow.applyPublished"
        + " ignores PublishOutcome.unchanged)" )
    @Test
    void theShrunkUnchangedSaveTraceReplaysCleanly() throws Exception {
        final boolean before = CollabInvariants.tolerateUnchangedSaveCredit;
        CollabInvariants.tolerateUnchangedSaveCredit = false;
        try {
            replaysCleanly( "agent_heavy-179-unchanged-save-credits-the-existing-version.json" );
        } finally {
            CollabInvariants.tolerateUnchangedSaveCredit = before;
        }
    }

    // ---- D2 ------------------------------------------------------------------------------------------------------

    /**
     * Defect D2, the rewrite variant: a publish's save succeeds, it writes its ledger rows, and its commit fails (a store
     * fault): 503, unfinished, owed. The retry ({@code PublishFlow.settleMarker} with the in-memory unfinished ticket)
     * claims version B+1 only when its content hash is S_V's ({@code claims}), which a save filter's rewrite breaks
     * although the session saw that very save succeed. So the marker is cleared, the version is merged as an outside
     * write, the rows stay, and the contributors stay pending: the next publish credits them again. Fix: finish an
     * in-memory unfinished ticket with the outcome its save returned (the hash rule is for a ticket rebuilt after a
     * crash), and clear rows a publish wrote when its marker is cleared unclaimed.
     */
    @Disabled( "known product defect D2: an unfinished publish whose save a filter rewrote is not claimed on retry; its"
        + " early ledger rows stay and its contributors are credited again" )
    @Test
    void anUnfinishedPublishWhoseSaveWasRewrittenIsFinishedOnRetry() {
        final CollabSessionSimServer s = server( "hello \n", "strip-trailing-spaces" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertEquals( 503, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status(),
            "the save succeeded but its commit failed: owed" );
        s.settle();
        assertNull( store.session( s.view().sessionId() ).orElseThrow().pendingPublishVersion() );
        assertEquals( Map.of( 2, List.of( A ) ), s.ledger( CID ) );
        assertEquals( Set.of(), s.contributors(), "the publish of version 2 credited alice: she is not owed it again" );
    }

    /**
     * Defect D2, the delete variant: as above (rows written, commit failed), but the page is deleted before the retry and
     * then recreated by an outside save. The retry finds no version B+1 and clears the marker as a save that never ran,
     * yet the rows the attempt wrote stay (now naming a version of the deleted page) and the contributors stay pending.
     * Either the publish completed (rows, nobody pending) or it did not (no rows, still pending); never both.
     */
    @Disabled( "known product defect D2: a publish whose commit failed and whose page was deleted before the retry keeps"
        + " its early ledger rows while its contributors stay pending" )
    @Test
    void aPublishLostWithItsPageDoesNotKeepItsRowsAndItsContributors() {
        final CollabSessionSimServer s = server( "hello\n", "none" );
        final Joined a = s.join( A );
        type( s, a, A, 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertEquals( 503, s.publish( a.participantId(), A, s.view().head(), "alice's" ).status() );
        writer.delete( PAGE );
        s.deleted();
        final int recreated = writer.externalSave( PAGE, s.view().doc(), Map.of( "canonical_id", CID ), "recreator" );
        s.externalSaved( recreated );
        s.settle();
        assertNull( store.session( s.view().sessionId() ).orElseThrow().pendingPublishVersion() );
        final boolean credited = List.of( A ).equals( s.ledger( CID ).get( 2 ) );
        assertFalse( credited && s.contributors().contains( A ), "alice is credited at version 2 and still owed credit"
            + " (ledger " + s.ledger( CID ) + ", contributors " + s.contributors() + ")" );
    }

    @Disabled( "known product defect D2: an unfinished publish's early ledger rows outlive a retry that does not claim"
        + " its version" )
    @Test
    void theShrunkOrphanedRowsTracesReplayCleanly() throws Exception {
        final boolean before = CollabInvariants.tolerateOrphanedRows;
        CollabInvariants.tolerateOrphanedRows = false;
        try {
            replaysCleanly( "publish_storm-103-rewritten-unfinished-publish-leaves-its-rows.json" );
            replaysCleanly( "tight-2-page-deleted-before-the-retry-leaves-its-rows.json" );
        } finally {
            CollabInvariants.tolerateOrphanedRows = before;
        }
    }
}

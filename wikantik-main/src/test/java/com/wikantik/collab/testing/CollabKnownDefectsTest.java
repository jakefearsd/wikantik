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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Product defects the Phase 2 Task 8 fuzz found in {@code com.wikantik.collab} and that are not fixed yet (product code
 * belongs to other tasks). Each is pinned twice, as a direct scenario and as the fuzz's shrunk trace (under
 * {@code src/test/resources/collab/known-defects/session/}, outside the directories {@link CollabRegressionTracesTest}
 * replays), and the fuzz carries a narrow, documented tolerance for each so it stays green on everything else. Once a
 * defect is fixed: enable its tests, move its trace to {@code collab/regressions/session/}, and delete the tolerance
 * ({@link CollabInvariants#tolerateRecreateSnapshotBase}, {@link SimScheduler#avoidRecreateWhileFreezeOwed}).
 */
class CollabKnownDefectsTest {

    private static final String PAGE = "P";
    private static final String A = "author-a";

    private static CollabSessionSimServer server( final ScriptedPageWriter writer, final InMemoryCollabStore store ) {
        return CollabSessionSimServer.create( PAGE, store, writer, new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            64 * 1024, 4, 6 );
    }

    private static SimTrace trace( final String name ) throws IOException, URISyntaxException {
        final Path p = Path.of( CollabKnownDefectsTest.class.getResource( "/collab/known-defects/session/" + name ).toURI() );
        return SimTrace.fromJson( Files.readString( p, StandardCharsets.UTF_8 ) );
    }

    /**
     * Defect 1: a frozen draft's recreate publish labels its publish-point snapshot with the publish's expected page
     * version (0) instead of the draft's base page version, though its {@code local} is relative to that base
     * ({@code PublishFlow.snapshotForSave} writes {@code new Snapshot( d.head, d.metaSeq, b, ... )} with {@code b == 0}
     * for a recreate). Invariant 3 fails until the next base change; a reload logs "snapshot does not fit base version"
     * and recomputes {@code local} by diff. Fix: label the snapshot with {@code d.record.basePageVersion()}.
     */
    @Disabled( "known product defect: a recreate publish's snapshot is labelled base page version 0 (PublishFlow.snapshotForSave)" )
    @Test
    void aRecreatePublishSnapshotIsOnTheDraftsBase() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "hello\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        writer.delete( PAGE );
        server.deleted();
        final SimServer.PublishStart st = server.beginPublish( a.participantId(), A, a.version(), "recreate" );
        assertNotNull( st.pending(), "the recreate publish is held inside its save" );
        final InvariantView v = server.view();
        assertEquals( v.basePageVersion(), store.latestSnapshot( v.sessionId() ).orElseThrow().basePageVersion(),
            "the publish-point snapshot must be on the draft's base page version" );
    }

    @Disabled( "known product defect: a recreate publish's snapshot is labelled base page version 0 (PublishFlow.snapshotForSave)" )
    @Test
    void theShrunkRecreateSnapshotTraceReplaysCleanly() throws Exception {
        final boolean before = CollabInvariants.tolerateRecreateSnapshotBase;
        CollabInvariants.tolerateRecreateSnapshotBase = false;
        try {
            final SimTrace t = SimTarget.SESSION.replay( trace( "agent_heavy-1-recreate-snapshot-labelled-base-0.json" ) );
            assertEquals( SimTrace.Status.PASS, t.outcome().status(), t.outcome().message() );
        } finally {
            CollabInvariants.tolerateRecreateSnapshotBase = before;
        }
    }

    /**
     * Defect 2: a delete that did not freeze the draft (the freeze's store write failed, or the event was missed) followed
     * by a recreate of the page. The recreated page restarts at version 1, below the draft's base, and
     * {@code ExternalMerge.mergeTo} drops a target older than the base by version number alone
     * ({@code target.version() < b} answers NONE, against A1's "by content hash, never by version number"): the
     * recreated text is never merged, and every publish (whose A1 backstop reconcile does the same) saves with the old
     * expected version, conflicts and answers 409 {@code publish-merged-retry} for ever.
     */
    @Disabled( "known product defect: a recreated page below the draft's base version is never merged (ExternalMerge.mergeTo)" )
    @Test
    void aRecreatedPageBelowTheBaseIsMergedAndPublishable() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "agent" ) );
        assertEquals( 2, server.view().basePageVersion() );
        writer.delete( PAGE );                 // the freeze never happens (a refused store write, a missed event)
        final int recreated = writer.externalSave( PAGE, "recreated\n", Map.of(), "other" );
        server.externalSaved( recreated );
        assertEquals( recreated, server.view().basePageVersion(), "the recreated page is the draft's base" );
        final InvariantView v = server.view();
        assertEquals( 200, server.push( a.participantId(), A, v.head(), List.of( new Update( a.clientId(),
            ChangeSet.replace( v.doc().length(), 0, 0, "x" ) ) ) ).status() );
        PublishResult r = null;
        for( int attempt = 0; attempt < 3 && ( r == null || r.status() != 200 ); attempt++ ) {
            r = server.publish( a.participantId(), A, server.view().head(), "retry " + attempt );
        }
        assertEquals( 200, r.status(), "the publish never got through: " + r );
    }

    @Disabled( "known product defect: a recreated page below the draft's base version is never merged (ExternalMerge.mergeTo)" )
    @Test
    void theShrunkRecreateBelowBaseTraceReplaysCleanly() throws Exception {
        final boolean before = SimScheduler.avoidRecreateWhileFreezeOwed;
        SimScheduler.avoidRecreateWhileFreezeOwed = false;
        try {
            final SimTrace t = SimTarget.SESSION.replay( trace( "calm-249-recreated-page-below-base-never-merged.json" ) );
            assertEquals( SimTrace.Status.PASS, t.outcome().status(), t.outcome().message() );
        } finally {
            SimScheduler.avoidRecreateWhileFreezeOwed = before;
        }
    }
}

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

import com.wikantik.collab.PublishStart;
import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PublishResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deleted page coming back under a draft, two defects the Phase 2 Task 8 fuzz found (their shrunk traces replay from
 * {@code collab/regressions/session/}): a frozen draft's recreate publish labelled its publish-point snapshot with the
 * save's expected version 0 instead of the draft's base page version, and a page recreated under a draft that was never
 * frozen restarted below the draft's base version and was dropped as stale by version number, so every publish answered
 * 409 {@code publish-merged-retry} for ever. A1: whether a version was merged is decided by content hash.
 */
class CollabRecreateCasesTest {

    private static final String PAGE = "P";
    private static final String A = "author-a";
    private static final Instant EPOCH = Instant.parse( "2026-01-01T00:00:00Z" );

    private static CollabSessionSimServer server( final ScriptedPageWriter writer, final InMemoryCollabStore store ) {
        return CollabSessionSimServer.create( PAGE, store, writer, new MutableClock( EPOCH ), 64 * 1024, 4, 6 );
    }

    private static CollabSessionSimServer reload( final ScriptedPageWriter writer, final InMemoryCollabStore store ) {
        return CollabSessionSimServer.reload( PAGE, store, writer, new MutableClock( EPOCH ), 64 * 1024, 4, 6 );
    }

    private static void type( final CollabSessionSimServer server, final Joined a, final String text ) {
        final InvariantView v = server.view();
        assertEquals( 200, server.push( a.participantId(), A, v.head(), List.of( new Update( a.clientId(),
            ChangeSet.replace( v.doc().length(), v.doc().length(), v.doc().length(), text ) ) ) ).status() );
    }

    private static List< CollabEvent > events( final CollabSessionSimServer server, final Joined a, final String type ) {
        return server.pull( a.participantId(), A, new PullCursor( a.version(), Long.MAX_VALUE, Long.MAX_VALUE, 0 ), null )
            .events().stream().filter( e -> e.type().equals( type ) ).toList();
    }

    // ---- defect 1: the recreate publish's snapshot ------------------------------------------------------------------

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
        CollabInvariants.check( v, true );
        final PublishResult r = server.releasePublish( st.pending() );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 1, r.pageVersion(), "the recreate created the page" );
        assertFalse( server.frozen() );
        CollabInvariants.check( server.view(), true );
    }

    @Test
    void aRecreateStillSavesAsMustNotExist() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        writer.externalSave( PAGE, "two\n", Map.of(), "agent" );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        writer.delete( PAGE );
        server.deleted();
        final PublishStart st = server.session().startPublish( a.participantId(), A, a.version(), "recreate" );
        assertNull( st.response(), "the recreate publish got a ticket" );
        assertEquals( 0, st.ticket().baseVersion(), "a recreate saves on B = 0" );
        assertEquals( 0, st.ticket().request().expectedVersion(), "a recreate saves as 'must not exist' (A11)" );
        assertEquals( 2, store.latestSnapshot( server.view().sessionId() ).orElseThrow().basePageVersion() );
        final PublishResponse r = server.session().finishPublish( st.ticket(), saveOf( writer, st ) );
        assertEquals( 200, r.status() );
        assertEquals( 1, server.view().basePageVersion() );
        assertFalse( server.frozen() );
        CollabInvariants.check( server.view(), true );
    }

    /**
     * The save persisted and the server died before finishing: the stored publish-point snapshot is on the record's base
     * (so the reload pairs them without recomputing local), and the reload's pending-publish rule rebuilds S_V and M_V
     * from that snapshot and claims version 1 as the recreate.
     */
    @Test
    void aRecreatePublishLostInItsSaveIsRecoveredFromItsSnapshot() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "hello\n", Map.of( "status", "draft" ) );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        type( server, a, "more\n" );
        writer.delete( PAGE );
        server.deleted();
        final PublishStart st = server.session().startPublish( a.participantId(), A, server.view().head(), "recreate" );
        assertNull( st.response() );
        saveOf( writer, st );   // persisted; the post-save half never runs
        final UUID id = server.view().sessionId();
        assertEquals( store.session( id ).orElseThrow().basePageVersion(),
            store.latestSnapshot( id ).orElseThrow().basePageVersion(), "the stored snapshot is on the stored base" );
        assertNotNull( store.session( id ).orElseThrow().pendingPublishVersion(), "the marker survives the crash" );
        final CollabSessionSimServer reloaded = reload( writer, store );
        reloaded.settle();
        final InvariantView v = reloaded.view();
        assertFalse( v.publishPending() );
        assertFalse( v.frozen(), "the claimed recreate made the draft active again" );
        assertEquals( 1, v.basePageVersion() );
        assertEquals( "hello\nmore\n", v.baseBody() );
        assertTrue( v.local().empty(), "everything typed was published" );
        assertEquals( "draft", v.meta().get( "status" ) );
        CollabInvariants.check( v, true );
    }

    private static PublishOutcome saveOf( final ScriptedPageWriter writer, final PublishStart st ) {
        try {
            return writer.publish( st.ticket().request() );
        } catch( final Exception e ) {
            throw new AssertionError( "the save failed: " + e, e );
        }
    }

    // ---- defect 2: a recreated page below the base ------------------------------------------------------------------

    @Test
    void aRecreatedPageBelowTheBaseIsMergedAndPublishable() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "agent" ) );
        assertEquals( 2, server.view().basePageVersion() );
        type( server, a, "mine\n" );
        writer.delete( PAGE );                 // the freeze never happens (a refused store write, a missed event)
        final int recreated = writer.externalSave( PAGE, "recreated\n", Map.of(), "other" );
        server.externalSaved( recreated );
        final InvariantView v = server.view();
        assertEquals( recreated, v.basePageVersion(), "the recreated page is the draft's base" );
        assertEquals( "recreated\n", v.baseBody() );
        assertTrue( v.doc().contains( "recreated\n" ) && v.doc().contains( "mine\n" ),
            "the recreated text is merged and the local edits rebased on it: " + v.doc() );
        assertEquals( "mine\n", v.local().apply( v.baseBody() ).replace( "recreated\n", "" ) );
        final List< CollabEvent > changes = events( server, a, "external-change" );
        assertEquals( Boolean.TRUE, changes.get( changes.size() - 1 ).fields().get( "recreated" ) );
        CollabInvariants.check( v, true );
        final PublishResult r = server.publish( a.participantId(), A, server.view().head(), "publish" );
        assertEquals( 200, r.status(), "the publish gets through first time: " + r );
        assertEquals( recreated + 1, r.pageVersion() );
        assertEquals( v.doc(), CollabText.toLf( writer.latest( PAGE ).map( PageState::body ).orElseThrow() ) );
    }

    /** A page recreated with the base's exact text merges nothing but still becomes the base: the next save is on it. */
    @Test
    void aRecreatedPageWithTheBasesTextOnlyMovesTheBase() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "agent" ) );
        type( server, a, "mine\n" );
        final int head = server.view().head();
        writer.delete( PAGE );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "other" ) );
        final InvariantView v = server.view();
        assertEquals( 1, v.basePageVersion() );
        assertEquals( head, v.head(), "no server update: the text was already the base's" );
        assertEquals( "two\nmine\n", v.doc() );
        CollabInvariants.check( v, true );
        final PublishResult r = server.publish( a.participantId(), A, v.head(), "publish" );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 2, r.pageVersion() );
    }

    /** An older version of the base's own history is a stale event: nothing merges, the base stays. */
    @Test
    void aStaleEventOfTheSamePageIsIgnored() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "agent" ) );
        server.externalSaved( writer.externalSave( PAGE, "three\n", Map.of(), "agent" ) );
        type( server, a, "mine\n" );
        final InvariantView before = server.view();
        server.externalSaved( 1 );             // late, and out of order
        server.externalSaved( 2 );
        server.externalSaved( 3 );             // a repeat of the base
        final InvariantView after = server.view();
        assertEquals( 3, after.basePageVersion() );
        assertEquals( before.head(), after.head() );
        assertEquals( before.doc(), after.doc() );
        assertEquals( "three\nmine\n", after.doc() );
        CollabInvariants.check( after, true );
    }

    /**
     * A3: the page is deleted and recreated while a publish is inside its save; the event queues, the save conflicts
     * with the recreated version 1, the queued merge runs at the publish's end, and the retry gets through.
     */
    @Test
    void aRecreateDuringAPublishsSaveMergesAtItsEnd() {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( PAGE, "one\n", Map.of() );
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final CollabSessionSimServer server = server( writer, store );
        final Joined a = server.join( A );
        server.externalSaved( writer.externalSave( PAGE, "two\n", Map.of(), "agent" ) );
        type( server, a, "mine\n" );
        final SimServer.PublishStart st = server.beginPublish( a.participantId(), A, server.view().head(), "publish" );
        assertNotNull( st.pending() );
        writer.delete( PAGE );
        final int recreated = writer.externalSave( PAGE, "recreated\n", Map.of(), "other" );
        server.externalSaved( recreated );
        assertEquals( 2, server.view().basePageVersion(), "queued behind the publish (A3)" );
        final PublishResult held = server.releasePublish( st.pending() );
        assertEquals( 409, held.status(), held.toString() );
        final InvariantView v = server.view();
        assertEquals( recreated, v.basePageVersion(), "merged by the publish's end" );
        assertTrue( v.doc().contains( "recreated\n" ) && v.doc().contains( "mine\n" ), v.doc() );
        CollabInvariants.check( v, true );
        final PublishResult r = server.publish( a.participantId(), A, v.head(), "retry" );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( recreated + 1, r.pageVersion() );
    }
}

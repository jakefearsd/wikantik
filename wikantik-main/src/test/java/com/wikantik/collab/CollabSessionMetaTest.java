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

import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A14 session meta edits: field SETs and the set ops ADD/REMOVE on {@code tags}, {@code related} and {@code aliases},
 * resolved by the server in its order, stored with the push's text in one step, broadcast on pull with the author's
 * public name, and kept across a publish (and its crash recovery, Phase 1 review note M5).
 */
class CollabSessionMetaTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final Date JAN_15 = Date.from( Instant.parse( "2026-01-15T00:00:00Z" ) );
    private static final CollabIdentity IDENTITY = key -> switch( key ) {
        case "ada" -> "Ada";
        case "raj" -> "Raj";
        default -> null;
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSession session;

    /** Records the one completion of a parked pull. */
    private static final class Waiter implements PullWaiter {
        final List< PullResponse > got = new ArrayList<>();

        @Override
        public void complete( final PullResponse r ) {
            if( !got.isEmpty() ) {
                fail( "waiter completed twice: " + got + " then " + r );
            }
            got.add( r );
        }
    }

    private static CollabConfig config() {
        return new CollabConfig( true, 20, 65536, 25, 45, 30, 50, 500, 10, 0, 1000 );
    }

    private CollabPorts ports() {
        return new CollabPorts( store, writer, IDENTITY, CollabSessionTest.ALLOW, clock );
    }

    private CollabSession reload() {
        return CollabSession.load( session.id(), ports(), config(), 1000 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "title", "T" );
        meta.put( "tags", List.of( "a" ) );
        meta.put( "date", JAN_15 );
        writer.seed( PAGE, BODY, meta );
        clock = new MutableClock( T0 );
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( PAGE.getBytes() ), PAGE,
            writer.latest( PAGE ), "creator", clock );
        session = CollabSession.create( rec, ports(), config(), 1000 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, author, null ) );
    }

    private PushResponse edit( final JoinResponse j, final String author, final int version, final MetaEdit... edits ) {
        return session.push( j.participantId(), author, version, List.of(), List.of( edits ), null );
    }

    private PushResponse edit( final JoinResponse j, final String author, final MetaEdit... edits ) {
        return edit( j, author, session.view().head(), edits );
    }

    private Update typing( final JoinResponse j, final int at, final String text ) {
        return new Update( j.clientId(), ChangeSet.replace( session.view().doc().length(), at, at, text ) );
    }

    private PullResponse pullMeta( final JoinResponse j, final String author, final long metaSince ) {
        final Waiter w = new Waiter();
        final PullResponse r = session.pullOrPark( j.participantId(), author,
            new PullCursor( session.view().head(), metaSince, Long.MAX_VALUE, Long.MAX_VALUE ), null, w );
        assertNotNull( r, "answered at once" );
        return r;
    }

    private Object meta( final String field ) {
        return session.view().meta().get( field );
    }

    // ---- set, broadcast, server order ------------------------------------------------------------------------------

    @Test
    void aSetEditIsStoredAppliedAndBroadcast() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse raj = join( "raj" );
        final PushResponse r = edit( ada, "ada", MetaEdit.set( "summary", "New" ) );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 1L, r.metaSeq() );
        assertEquals( "New", meta( "summary" ) );
        assertEquals( List.of( new StoredMetaOp( 1, "ada", "summary", "New" ) ), store.metaOpsSince( session.id(), 0 ) );
        final PullResponse p = pullMeta( raj, "raj", 0 );
        assertEquals( List.of( new MetaOp( "summary", "New", "Ada" ) ), p.metaOps() );
        assertEquals( 1L, p.metaSeq() );
    }

    @Test
    void concurrentTagAddsBothSurvive() {   // review focus #2
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( List.of( "a" ), a.metadata().get( "tags" ) );
        assertEquals( 200, edit( a, "ada", a.version(), MetaEdit.add( "tags", "x" ) ).status() );
        // B has not pulled: it still believes tags == [a]
        assertEquals( 200, edit( b, "raj", b.version(), MetaEdit.add( "tags", "y" ) ).status() );
        assertEquals( List.of( "a", "x", "y" ), meta( "tags" ) );
        assertEquals( 2L, session.view().metaSeq() );
    }

    @Test
    void addThenRemoveOfTheSameTagIsOrderedByTheServer() {   // E34, set against remove
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "x" ) ).status() );
        assertEquals( 200, edit( b, "raj", MetaEdit.remove( "tags", "x" ) ).status() );
        assertEquals( List.of( "a" ), meta( "tags" ), "the remove came second: it wins" );
        assertEquals( 200, edit( b, "raj", MetaEdit.remove( "tags", "z" ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "z" ) ).status() );
        assertEquals( List.of( "a", "z" ), meta( "tags" ), "the add came second: it wins" );
        // every client converges on the server's resolved assignments
        final List< MetaOp > ops = pullMeta( a, "ada", 0 ).metaOps();
        assertEquals( new MetaOp( "tags", List.of( "a", "z" ), "Ada" ), ops.get( ops.size() - 1 ) );
    }

    @Test
    void e34_concurrentSetsOfOneFieldServerOrderWins() {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, edit( a, "ada", a.version(), MetaEdit.set( "status", "draft" ) ).status() );
        assertEquals( 200, edit( b, "raj", b.version(), MetaEdit.set( "status", "active" ) ).status() );
        assertEquals( "active", meta( "status" ) );
        assertEquals( List.of( new MetaOp( "status", "draft", "Ada" ), new MetaOp( "status", "active", "Raj" ) ),
            pullMeta( a, "ada", 0 ).metaOps() );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "status", null ) ).status() );
        assertFalse( session.view().meta().containsKey( "status" ), "a null SET removes the field" );
    }

    @Test
    void aNoOpEditStoresNothing() {
        final JoinResponse a = join( "ada" );
        final PushResponse r = edit( a, "ada", MetaEdit.set( "title", "T" ), MetaEdit.add( "tags", "a" ),
            MetaEdit.remove( "tags", "nope" ), MetaEdit.remove( "aliases", "none" ), MetaEdit.set( "absent", null ) );
        assertEquals( 200, r.status() );
        assertEquals( 0L, r.metaSeq() );
        assertEquals( 0L, session.view().metaSeq() );
        assertTrue( store.metaOpsSince( session.id(), 0 ).isEmpty() );
        assertFalse( session.view().meta().containsKey( "aliases" ) );
        assertFalse( CollabSession.Hooks.hasUnpublishedChanges( session ) );
    }

    @Test
    void aScalarTagsValueIsTreatedAsAOneItemList() {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "tags", "solo, two" ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "solo, two" ) ).status() );
        assertEquals( "solo, two", meta( "tags" ), "adding the one present item changes nothing" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "b" ) ).status() );
        assertEquals( List.of( "solo, two", "b" ), meta( "tags" ), "never split on commas" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "related", "Other" ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.remove( "related", "Other" ) ).status() );
        assertEquals( List.of(), meta( "related" ), "removing the last item leaves []" );
    }

    @Test
    void removeDropsEveryEqualItem() {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "aliases", List.of( "x", "y", "x" ) ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.remove( "aliases", "x" ) ).status() );
        assertEquals( List.of( "y" ), meta( "aliases" ) );
    }

    // ---- refusals --------------------------------------------------------------------------------------------------

    @Test
    void setOpsOnANonSetFieldAreRejected() {
        final JoinResponse a = join( "ada" );
        final String before = session.view().doc();
        final PushResponse r = session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ),
            List.of( MetaEdit.set( "summary", "s" ), MetaEdit.add( "status", "x" ) ), null );
        assertEquals( 400, r.status() );
        assertEquals( "rejected", r.reason() );
        assertEquals( before, session.view().doc(), "the text of the same push is not applied" );
        assertEquals( 0, session.view().head() );
        assertNull( meta( "summary" ), "nor is the valid edit before the bad one" );
        assertTrue( store.updatesSince( session.id(), 0 ).isEmpty() );
        assertTrue( store.metaOpsSince( session.id(), 0 ).isEmpty() );
    }

    @Test
    void badFieldNamesAreRejected() {
        final JoinResponse a = join( "ada" );
        assertEquals( 400, edit( a, "ada", MetaEdit.set( " ", "x" ) ).status() );
        assertEquals( 400, edit( a, "ada", MetaEdit.set( "__yaml__", "x" ) ).status() );
        assertEquals( 400, edit( a, "ada", MetaEdit.set( "f".repeat( 129 ), "x" ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "f".repeat( 128 ), "x" ) ).status() );
        assertEquals( 1L, session.view().metaSeq() );
    }

    @Test
    void aStalePushAppliesNoMetaEdits() {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ) ).status() );
        final PushResponse r = session.push( b.participantId(), "raj", 0,
            List.of( new Update( b.clientId(), ChangeSet.replace( BODY.length(), 0, 0, "Y" ) ) ),
            List.of( MetaEdit.set( "summary", "stale" ) ), null );
        assertEquals( 409, r.status() );
        assertEquals( "stale-base", r.reason() );
        assertNull( meta( "summary" ) );
        assertEquals( 0L, session.view().metaSeq() );
    }

    @Test
    void aFrozenSessionAppliesNoMetaEdits() {
        final JoinResponse a = join( "ada" );
        session.freeze();
        final PushResponse r = edit( a, "ada", MetaEdit.set( "summary", "x" ) );
        assertEquals( 409, r.status() );
        assertEquals( "frozen", r.reason() );
        assertEquals( 0L, r.metaSeq() );
        assertNull( meta( "summary" ) );
    }

    @Test
    void aMetaOnlyPushSkipsTheVersionCheck() {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ) ).status() );
        final PushResponse r = edit( b, "raj", 0, MetaEdit.set( "summary", "s" ) );
        assertEquals( 200, r.status(), "behind on text, but meta-only" );
        assertEquals( 1, r.version() );
        assertEquals( 1L, r.metaSeq() );
        assertEquals( "s", meta( "summary" ) );
    }

    // ---- waking, atomicity, unpublished ----------------------------------------------------------------------------

    @Test
    void aMetaEditWakesParkedPulls() {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "raj", new PullCursor( 0, 0, Long.MAX_VALUE, Long.MAX_VALUE ),
            null, w ), "parked at head" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "x" ) ).status() );
        assertEquals( 1, w.got.size(), "woken by the meta op" );
        assertEquals( List.of( new MetaOp( "tags", List.of( "a", "x" ), "Ada" ) ), w.got.get( 0 ).metaOps() );
        assertTrue( CollabSession.Hooks.hasUnpublishedChanges( session ), "meta != baseMeta: unpublished" );
    }

    @Test
    void textAndMetaOfOnePushAreAtomic() {
        final JoinResponse a = join( "ada" );
        store.failOn( InMemoryCollabStore.Op.APPEND_BATCH, 1 );
        final PushResponse r = session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ),
            List.of( MetaEdit.set( "summary", "s" ) ), null );
        assertEquals( 503, r.status() );
        assertEquals( "store", r.reason() );
        assertEquals( BODY, session.view().doc() );
        assertEquals( 0, session.view().head() );
        assertNull( meta( "summary" ) );
        assertEquals( 0L, session.view().metaSeq() );
        // the retry succeeds, both parts at once
        final PushResponse retry = session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ),
            List.of( MetaEdit.set( "summary", "s" ) ), null );
        assertEquals( 200, retry.status() );
        assertEquals( "X" + BODY, session.view().doc() );
        assertEquals( "s", meta( "summary" ) );
        assertEquals( 1, store.session( session.id() ).orElseThrow().headVersion() );
        assertEquals( 1L, store.session( session.id() ).orElseThrow().metaSeq() );
    }

    @Test
    void metaEditsSurviveAReload() {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "x" ), MetaEdit.set( "summary", "s" ) ).status() );
        session = reload();
        assertEquals( List.of( "a", "x" ), meta( "tags" ) );
        assertEquals( "s", meta( "summary" ) );
        assertEquals( 2L, session.view().metaSeq() );
    }

    // ---- normalisation and typed base values -----------------------------------------------------------------------

    @Test
    void aYamlDateAndItsJsonStringAreOneValue() {
        final JoinResponse a = join( "ada" );
        final PushResponse r = edit( a, "ada", MetaEdit.set( "date", "2026-01-15" ) );
        assertEquals( 200, r.status() );
        assertEquals( 0L, r.metaSeq(), "the client's JSON string is the base's YAML Date: nothing stored" );
        assertTrue( store.metaOpsSince( session.id(), 0 ).isEmpty() );
        assertFalse( CollabSession.Hooks.hasUnpublishedChanges( session ) );
    }

    @Test
    void aClientEditOnAnotherFieldKeepsTheBaseDateTyped() throws Exception {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "tags", "x" ) ).status() );
        final PublishResponse p = session.publish( a.participantId(), "ada", session.view().head(), "note" );
        assertEquals( 200, p.status(), p.toString() );
        final Map< String, Object > saved = writer.latest( PAGE ).orElseThrow().meta();
        assertSame( JAN_15, saved.get( "date" ), "the publish request carried the base's original Date" );
        assertEquals( List.of( "a", "x" ), saved.get( "tags" ) );
        assertFalse( CollabSession.Hooks.hasUnpublishedChanges( session ) );
    }

    // ---- publish interplay (Phase 1 review note M5) ----------------------------------------------------------------

    @Test
    void aMetaEditDuringThePublishSaveSurvivesIt() throws Exception {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "published" ) ).status() );
        final ScriptedPageWriter.PauseHandle h = writer.pauseNextPublish();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< PublishResponse > f = ex.submit( () -> session.publish( a.participantId(), "ada", 0, "note" ) );
            h.awaitEntered( Duration.ofSeconds( 5 ) );
            assertEquals( 200, edit( b, "raj", MetaEdit.set( "summary", "during" ), MetaEdit.add( "tags", "d" ) ).status() );
            h.release();
            assertEquals( 200, f.get( 5, TimeUnit.SECONDS ).status() );
        } finally {
            ex.shutdownNow();
        }
        assertEquals( "published", writer.latest( PAGE ).orElseThrow().meta().get( "summary" ) );
        assertEquals( "during", meta( "summary" ), "the edit made during the save is kept" );
        assertEquals( List.of( "a", "d" ), meta( "tags" ) );
        assertTrue( CollabSession.Hooks.hasUnpublishedChanges( session ) );
    }

    @Test
    void aMetaEditDuringThePublishSaveSurvivesCrashRecovery() throws Exception {   // M5: M_V is durable
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "published" ) ).status() );
        final PublishStart st = session.startPublish( a.participantId(), "ada", 0, "note" );
        assertNull( st.response() );
        assertEquals( 200, edit( b, "raj", MetaEdit.set( "summary", "during" ) ).status() );
        final PublishOutcome saved = writer.publish( st.ticket().request() );
        assertEquals( 2, saved.version() );
        // crash: the post-save half never ran; the reload claims version 2 as the marked publish
        session = reload();
        assertEquals( 2, session.view().basePageVersion() );
        assertNull( store.session( session.id() ).orElseThrow().pendingPublishVersion() );
        assertEquals( "during", meta( "summary" ), "the fold must not revert the edit made during the save" );
        assertTrue( CollabSession.Hooks.hasUnpublishedChanges( session ) );
    }

    @Test
    void aPublishWhoseMarkerSnapshotCannotBeStoredIsRefused() {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "s" ) ).status() );
        store.failOn( InMemoryCollabStore.Op.WRITE_SNAPSHOT, 1 );
        final PublishStart st = session.startPublish( a.participantId(), "ada", 0, "note" );
        assertNotNull( st.response() );
        assertEquals( 503, st.response().status() );
        assertNull( store.session( session.id() ).orElseThrow().pendingPublishVersion(), "no marker without M_V" );
        assertFalse( session.view().publishPending() );
        assertEquals( 200, session.publish( a.participantId(), "ada", 0, "note" ).status() );
        assertEquals( 2, writer.latest( PAGE ).orElseThrow().version(), "the retry publishes" );
    }

    @Test
    void aMarkerThatCannotBeStoredAfterItsSnapshotIsRefusedAndTheNextPublishWorks() {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ) ).status() );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // the marker write, after the snapshot at V
        final PublishStart st = session.startPublish( a.participantId(), "ada", 1, "note" );
        assertNotNull( st.response() );
        assertEquals( 503, st.response().status() );
        assertEquals( 1, store.snapshotAtOrBelow( session.id(), 1 ).orElseThrow().version(), "an orphan snapshot at V" );
        assertNull( store.session( session.id() ).orElseThrow().pendingPublishVersion(), "no marker" );
        assertFalse( session.view().publishPending() );
        assertEquals( 200, session.publish( a.participantId(), "ada", 1, "note" ).status() );
        assertEquals( "X" + BODY, com.wikantik.collab.text.CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() ) );
    }

    @Test
    void aLegacyMarkerWithNoSnapshotAtVRecoversWithTheLiveMetaAndWarns() throws Exception {
        final JoinResponse a = join( "ada" );
        assertEquals( 200, session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ) ).status() );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "live" ) ).status() );
        // a marker as a build before durable M_V stored it: no snapshot at V = 1 (only the creation snapshot at 0)
        final SessionRecord rec = store.session( session.id() ).orElseThrow();
        store.updateSession( rec.withPending( 1, 1, "ada" ) );
        writer.publish( new PublishRequest( PAGE, "X" + BODY, session.view().meta(), 1, "ada", "" ) );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "during" ) ).status() );   // during the save
        try( Warnings w = new Warnings( Draft.class ) ) {
            session = reload();
            assertTrue( w.lines.stream().anyMatch( l -> l.contains( "no snapshot at the published version 1" ) ),
                w.lines.toString() );
        }
        assertEquals( 2, session.view().basePageVersion(), "the marked publish is still claimed" );
        assertNull( store.session( session.id() ).orElseThrow().pendingPublishVersion() );
        // M_V fell back to the live meta (summary "during"), so the fold reverts the edit made during the save: the
        // documented limitation of a legacy marker, and exactly what the snapshot at V prevents
        assertEquals( "live", meta( "summary" ) );
    }

    // ---- fix round 1: set-field items as strings, value cap, null edits, refusal log -------------------------------

    private CollabSession numericTags() {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "tags", List.of( 2024, "x" ) );
        m.put( "count", 3 );
        writer.seed( "Num", BODY, m );
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( "Num".getBytes() ), "Num",
            writer.latest( "Num" ), "creator", clock );
        return CollabSession.create( rec, ports(), config(), 1000 );
    }

    @Test
    void setFieldItemsCompareAsStrings() {
        session = numericTags();
        final JoinResponse a = join( "ada" );
        final PushResponse add = edit( a, "ada", MetaEdit.add( "tags", "2024" ) );
        assertEquals( 200, add.status() );
        assertEquals( 0L, add.metaSeq(), "YAML 2024 is the item \"2024\": no duplicate" );
        assertEquals( List.of( 2024L, "x" ), meta( "tags" ) );
        assertEquals( 200, edit( a, "ada", MetaEdit.remove( "tags", "2024" ) ).status() );
        assertEquals( List.of( "x" ), meta( "tags" ), "REMOVE \"2024\" removes YAML 2024" );
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "aliases", List.of( 7, "y" ) ) ).status() );
        assertEquals( List.of( "7", "y" ), meta( "aliases" ), "set-field items are stored as strings" );
        assertEquals( 200, edit( a, "ada", MetaEdit.add( "aliases", "z" ) ).status() );
        assertEquals( List.of( "7", "y", "z" ), meta( "aliases" ) );
    }

    @Test
    void otherFieldsKeepNormalisedJsonComparison() {
        session = numericTags();
        final JoinResponse a = join( "ada" );
        assertEquals( 0L, edit( a, "ada", MetaEdit.set( "count", 3 ) ).metaSeq(), "3 is 3" );
        assertEquals( 1L, edit( a, "ada", MetaEdit.set( "count", "3" ) ).metaSeq(), "\"3\" is not 3 outside set fields" );
        assertEquals( "3", meta( "count" ) );
    }

    @Test
    void aMetaValueOverSixtyFourKibRefusesTheWholePush() {
        final JoinResponse a = join( "ada" );
        final PushResponse big = session.push( a.participantId(), "ada", 0, List.of( typing( a, 0, "X" ) ),
            List.of( MetaEdit.set( "title", "t2" ), MetaEdit.set( "summary", "x".repeat( 65535 ) ) ), null );
        assertEquals( 400, big.status() );
        assertEquals( "rejected", big.reason() );
        assertEquals( BODY, session.view().doc() );
        assertEquals( "T", meta( "title" ) );
        assertEquals( 0L, session.view().metaSeq() );
        // 65534 characters + 2 quotes = exactly 64 KiB of JSON: allowed
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "summary", "x".repeat( 65534 ) ) ).status() );
        final List< String > many = new ArrayList<>();
        for( int i = 0; i < 6000; i++ ) {
            many.add( "tag-" + i );
        }
        assertEquals( 200, edit( a, "ada", MetaEdit.set( "tags", many ) ).status(), "about 57.5 KiB" );
        assertEquals( 400, edit( a, "ada", MetaEdit.add( "tags", "y".repeat( 7000 ) ) ).status(),
            "an ADD that grows the list past the cap" );
    }

    @Test
    void aNullMetaEditIsRejected() {
        final JoinResponse a = join( "ada" );
        final PushResponse r = session.push( a.participantId(), "ada", 0, List.of(),
            java.util.Arrays.asList( MetaEdit.set( "summary", "s" ), null ), null );
        assertEquals( 400, r.status() );
        assertEquals( "rejected", r.reason() );
        assertNull( meta( "summary" ) );
    }

    @Test
    void theRefusalLogNamesFieldKindAndSessionButNeverTheValue() {
        final JoinResponse a = join( "ada" );
        try( Warnings w = new Warnings( SessionPush.class ) ) {
            assertEquals( 400, edit( a, "ada", MetaEdit.add( "status", "SECRET-VALUE" ) ).status() );
            assertEquals( 1, w.lines.size(), w.lines.toString() );
            final String line = w.lines.get( 0 );
            assertTrue( line.contains( "status" ) && line.contains( "ADD" ) && line.contains( session.id().toString() ),
                line );
            assertFalse( line.contains( "SECRET" ), line );
        }
    }

    /** Captures WARN and above from one class's logger, raising its level to WARN for the duration. */
    private static final class Warnings implements AutoCloseable {
        final List< String > lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final org.apache.logging.log4j.core.Logger logger;
        private final org.apache.logging.log4j.Level previous;
        private final org.apache.logging.log4j.core.appender.AbstractAppender appender;

        Warnings( final Class< ? > c ) {
            logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger( c );
            previous = logger.getLevel();
            appender = new org.apache.logging.log4j.core.appender.AbstractAppender( "warnings-" + c.getSimpleName(), null,
                    null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY ) {
                @Override
                public void append( final org.apache.logging.log4j.core.LogEvent e ) {
                    if( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                        lines.add( e.getMessage().getFormattedMessage() );
                    }
                }
            };
            appender.start();
            logger.addAppender( appender );
            org.apache.logging.log4j.core.config.Configurator.setLevel( c.getName(), org.apache.logging.log4j.Level.WARN );
        }

        @Override
        public void close() {
            org.apache.logging.log4j.core.config.Configurator.setLevel( logger.getName(), previous );
            logger.removeAppender( appender );
            appender.stop();
        }
    }
}

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

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 Task 5: {@link CollabPageEventListener} drives a real registry over the Phase 0 doubles; events are built
 * directly, as the engine would fire them (A1, A3, A5, A13, ruling Q10).
 */
class CollabPageEventListenerTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Edit by wiki session; sign-in by wiki session. */
    private static final class Permissions implements CollabPermissions {
        final Set< Object > noEdit = new HashSet<>();
        final Set< Object > signedOut = new HashSet<>();

        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !noEdit.contains( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean signedIn( final Object participantSession ) {
            return !signedOut.contains( participantSession );
        }
    }

    /** The scripted writer, throwing on {@code latest()} of one page on demand. */
    private static final class Throwing implements CollabPageWriter {
        final CollabPageWriter d;
        volatile String explodeOn;

        Throwing( final CollabPageWriter d ) {
            this.d = d;
        }

        @Override
        public Optional< PageState > latest( final String pageName ) {
            if( pageName.equals( explodeOn ) ) {
                throw new IllegalStateException( "test: the page store is down for " + pageName );
            }
            return d.latest( pageName );
        }

        @Override
        public Optional< PageState > version( final String pageName, final int version ) {
            return d.version( pageName, version );
        }

        @Override
        public PublishOutcome publish( final PublishRequest request ) throws WikiException {
            return d.publish( request );
        }
    }

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private Throwing throwing;
    private MutableClock clock;
    private Permissions permissions;
    private CollabSessionRegistry registry;
    private CollabPageEventListener listener;
    private final Object source = new Object();

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        throwing = new Throwing( writer );
        clock = new MutableClock( T0 );
        permissions = new Permissions();
        registry = new CollabSessionRegistry( store, throwing, permissions, key -> null, clock,
            new CollabConfig( true, 20, 65536, 25, 600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
        listener = new CollabPageEventListener( registry, throwing );
    }

    @AfterEach
    void tearDown() {
        WikiEventManager.removeWikiEventListener( listener );
    }

    private Joined join( final String page, final String author, final Object wikiSession ) {
        return assertInstanceOf( Joined.class, registry.join( page,
            new JoinRequest( author, null, wikiSession, author.toUpperCase(), null, null ) ) );
    }

    private Joined join( final String author ) {
        return join( PAGE, author, author );
    }

    private static PushResponse type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    private void fire( final int type, final String page ) {
        listener.actionPerformed( new WikiPageEvent( source, type, page ) );
    }

    private void fireSecurity( final int type, final Object target ) {
        listener.actionPerformed( new WikiSecurityEvent( source, type, null, target ) );
    }

    private long serverUpdates( final Joined j ) {
        return store.updatesSince( j.response().sessionId(), 0 ).stream()
            .filter( u -> StoredUpdate.SERVER.equals( u.kind() ) ).count();
    }

    /** One tick to notice the session is empty, then the idle time, then the evicting tick. */
    private void idleOut() {
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
    }

    // ---- saves (A1, A3) ---------------------------------------------------------------------------------------------

    @Test
    void aSaveEventDuringPublishIsQueuedAndMergedOnce() throws Exception {   // Review Focus #1
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< PublishResponse > first = CompletableFuture.supplyAsync(
            () -> a.session().publish( a.response().participantId(), "alice", 1, "" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        writer.externalSave( PAGE, BODY + "ext\n", Map.of( "title", "T" ), "bob" );
        fire( WikiPageEvent.POST_SAVE_BEGIN, PAGE );
        fire( WikiPageEvent.POST_SAVE_END, PAGE );
        assertEquals( "xhello\nworld\n", a.session().view().doc(), "queued while the publish is in its save" );
        pause.release();

        final PublishResponse r = first.get( 10, TimeUnit.SECONDS );
        assertEquals( 409, r.status() );
        assertEquals( "publish-merged-retry", r.reason() );
        assertEquals( "xhello\nworld\next\n", a.session().view().doc(), "the external text once, the typing once" );
        assertEquals( 1, serverUpdates( a ), "one external server update in the log" );

        final PublishResponse retry = a.session().publish( a.response().participantId(), "alice",
            a.session().view().head(), "" );
        assertEquals( 200, retry.status() );
        assertEquals( "xhello\r\nworld\r\next\r\n", writer.latest( PAGE ).orElseThrow().body() );
        assertEquals( "xhello\nworld\next\n", a.session().view().doc() );
    }

    @Test
    void bothEventsOfOneSaveMergeOnce() {
        final Joined a = join( "alice" );
        final int head = a.session().view().head();
        writer.externalSave( PAGE, BODY + "ext\n", Map.of( "title", "T" ), "bob" );
        fire( WikiPageEvent.POST_SAVE_BEGIN, PAGE );
        fire( WikiPageEvent.POST_SAVE_END, PAGE );
        assertEquals( BODY + "ext\n", a.session().view().doc() );
        assertEquals( head + 1, a.session().view().head() );
        assertEquals( 1, serverUpdates( a ) );
    }

    @Test
    void aWatcherOverwriteWithoutANewVersionIsMergedOnTheEvent() {
        final Joined a = join( "alice" );
        writer.overwriteWithoutVersion( PAGE, BODY + "disk\n" );
        fire( WikiPageEvent.POST_SAVE_BEGIN, PAGE );
        assertEquals( BODY + "disk\n", a.session().view().doc() );
    }

    @Test
    void aSaveEventForAPageWithoutALiveSessionIsANoOp() {
        assertDoesNotThrow( () -> fire( WikiPageEvent.POST_SAVE_END, "Nobody" ) );
        assertEquals( 0, registry.liveCount() );
    }

    @Test
    void aWatcherDeleteDoesNotFreezeTheDraft() {
        final Joined a = join( "alice" );
        writer.delete( PAGE );
        fire( WikiPageEvent.POST_SAVE_BEGIN, PAGE );   // the watcher's only event, also for a file delete or a git mv
        assertFalse( a.session().frozen() );
        assertEquals( 200, type( a, "alice", "x" ).status() );
    }

    // ---- renames (A1) ------------------------------------------------------------------------------------------------

    @Test
    void renameRekeysAndReconcilesReferrers() {
        writer.seed( "Referrer", "see [Page]\n", Map.of() );
        final Joined r = join( "Referrer", "bob", "bob" );
        final Joined t = join( "alice" );
        // the renamer rewrites the referrer with putPageText: no filters, no save event
        writer.externalSave( "Referrer", "see [Renamed]\n", Map.of(), "renamer" );
        writer.rename( PAGE, "Renamed" );

        listener.actionPerformed( new WikiPageRenameEvent( source, PAGE, "Renamed" ) );

        assertEquals( "Renamed", t.session().pageName() );
        assertSame( t.session(), registry.liveForPage( "Renamed" ).orElseThrow() );
        assertEquals( "see [Renamed]\n", r.session().view().doc(), "the referrer's session merged by content hash" );
    }

    @Test
    void aRenameIsNotTakenForADelete() {
        final Joined t = join( "alice" );
        writer.rename( PAGE, "Renamed" );
        listener.actionPerformed( new WikiPageRenameEvent( source, PAGE, "Renamed" ) );
        fire( WikiPageEvent.PAGE_DELETED, PAGE );   // a stray delete of the old name
        assertFalse( t.session().frozen() );
        registry.tick();
        clock.advance( Duration.ofMinutes( 5 ) );
        registry.tick();
        assertFalse( t.session().frozen(), "the renamed page exists: the sweep keeps it too" );
        assertEquals( 200, type( t, "alice", "x" ).status() );
    }

    @Test
    void rekeyOntoADraftFreezesTheOrphan() {
        // Target's page was deleted, but its draft (with changes) is still stored
        writer.seed( "Target", "t\n", Map.of() );
        final Joined old = join( "Target", "carol", "carol" );
        assertEquals( 200, type( old, "carol", "c" ).status() );
        assertEquals( 200, old.session().leave( old.response().participantId(), "carol" ).status() );
        idleOut();
        writer.delete( "Target" );
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        writer.rename( PAGE, "Target" );

        assertDoesNotThrow( () -> listener.actionPerformed( new WikiPageRenameEvent( source, PAGE, "Target" ) ) );

        assertEquals( PAGE, a.session().pageName(), "refused: the draft stays under its old name" );
        assertTrue( a.session().frozen(), "the orphan is frozen, so its client learns why" );
        assertEquals( "Target", store.session( old.response().sessionId() ).orElseThrow().pageName() );
        assertTrue( registry.hasDraft( "Target" ) );
        final PushResponse refused = type( a, "alice", "y" );
        assertEquals( 409, refused.status() );
        assertEquals( "frozen", refused.reason() );
    }

    // ---- deletes (ruling Q10) ------------------------------------------------------------------------------------------

    @Test
    void pageDeletedFreezesLiveAndStoredSessions() {
        writer.seed( "Other", "o\n", Map.of() );
        final Joined stored = join( "Other", "bob", "bob" );
        assertEquals( 200, type( stored, "bob", "b" ).status() );
        assertEquals( 200, stored.session().leave( stored.response().participantId(), "bob" ).status() );
        idleOut();
        final Joined live = join( "alice" );
        writer.delete( PAGE );
        writer.delete( "Other" );

        fire( WikiPageEvent.PAGE_DELETED, PAGE );
        fire( WikiPageEvent.PAGE_DELETED, "Other" );

        assertTrue( live.session().frozen() );
        assertEquals( SessionRecord.FROZEN, store.session( live.response().sessionId() ).orElseThrow().status() );
        assertEquals( SessionRecord.FROZEN, store.session( stored.response().sessionId() ).orElseThrow().status() );
    }

    @Test
    void pageDeletedForAnExistingPageKeepsTheDraft() {
        final Joined a = join( "alice" );
        fire( WikiPageEvent.PAGE_DELETED, PAGE );   // its delete failed, say
        assertFalse( a.session().frozen() );
        assertEquals( SessionRecord.ACTIVE, store.session( a.response().sessionId() ).orElseThrow().status() );
    }

    // ---- security events (A5, A13) -----------------------------------------------------------------------------------

    @Test
    void sessionExpiredEvictsThatSessionsParticipantsOnly() {
        final Object tab1 = new Object();
        final Object tab2 = new Object();
        final Joined a1 = join( PAGE, "alice", tab1 );
        final Joined a2 = join( PAGE, "alice", tab2 );
        final Joined b = join( "bob" );

        fireSecurity( WikiSecurityEvent.SESSION_EXPIRED, tab1 );

        final PushResponse gone = type( a1, "alice", "x" );
        assertEquals( 403, gone.status() );
        assertEquals( CollabSession.EVICTED, gone.reason() );
        assertEquals( CollabSession.SIGNED_OUT, gone.detail() );
        assertEquals( 200, type( a2, "alice", "y" ).status() );
        assertEquals( 200, type( b, "bob", "z" ).status() );
    }

    @Test
    void logoutEvictsSignedOutParticipants() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        permissions.signedOut.add( "alice" );

        fireSecurity( WikiSecurityEvent.LOGOUT, null );   // the principal, no target

        final PushResponse gone = type( a, "alice", "x" );
        assertEquals( 403, gone.status() );
        assertEquals( CollabSession.SIGNED_OUT, gone.detail() );
        assertEquals( 200, type( b, "bob", "y" ).status() );
    }

    @Test
    void groupChangeRechecksOnTheNextTick() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        permissions.noEdit.add( "bob" );

        fireSecurity( WikiSecurityEvent.GROUP_REMOVE, null );
        assertEquals( 2, a.session().view().participants(), "nothing happens on the event thread" );
        registry.tick();

        final PushResponse gone = type( b, "bob", "x" );
        assertEquals( 403, gone.status() );
        assertEquals( CollabSession.LOST_EDIT, gone.detail() );
        assertEquals( 200, type( a, "alice", "y" ).status() );

        // the request is used up: a later revocation without a group event waits for the cadence
        permissions.noEdit.add( "alice" );
        registry.tick();
        assertEquals( 200, type( a, "alice", "z" ).status() );
        fireSecurity( WikiSecurityEvent.GROUP_ADD, null );
        registry.tick();
        assertEquals( 403, type( a, "alice", "w" ).status() );
    }

    // ---- robustness --------------------------------------------------------------------------------------------------

    @Test
    void aThrowingSessionNeverEscapesTheListener() {
        final Joined a = join( "alice" );
        throwing.explodeOn = PAGE;
        assertDoesNotThrow( () -> fire( WikiPageEvent.POST_SAVE_END, PAGE ) );
        assertDoesNotThrow( () -> fire( WikiPageEvent.PAGE_DELETED, PAGE ) );
        assertFalse( a.session().frozen(), "a delete that cannot be confirmed keeps the draft" );
        throwing.explodeOn = null;
        writer.rename( PAGE, "Renamed" );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertDoesNotThrow( () -> listener.actionPerformed( new WikiPageRenameEvent( source, PAGE, "Renamed" ) ) );
        assertDoesNotThrow( () -> fire( WikiPageEvent.POST_SAVE_END, null ) );
        assertDoesNotThrow( () -> fire( WikiPageEvent.PAGE_REQUESTED, PAGE ) );   // ignored
        registry.tick();   // the failed rekey is retried
        assertEquals( "Renamed", a.session().pageName() );
    }

    /**
     * Fix round 1, item 2 (content-destroying): over the real adapter, a provider error while the save event's settle
     * reads the page must leave the draft's base alone. The engine's lenient reads answer "" for the text on that error;
     * an adapter built on them merged the "" as an outside overwrite and emptied the base.
     */
    @Test
    void aReadErrorDuringASaveEventLeavesTheDraftsBaseTextIntact() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 1 );
        when( page.getAuthor() ).thenReturn( "bob" );
        when( pm.getPage( eq( "P" ) ) ).thenReturn( page );
        when( pm.getPage( eq( "P" ), anyInt() ) )
            .thenReturn( page );
        when( pm.getPageInfo( eq( "P" ), anyInt() ) )
            .thenReturn( page );
        when( pm.getPureText( eq( "P" ), anyInt() ) )
            .thenReturn( BODY );
        when( pm.getPageText( eq( "P" ), anyInt() ) )
            .thenReturn( BODY );
        final PageSaveHelperPageWriter real = new PageSaveHelperPageWriter( pm,
            mock( PageSaveHelper.class ) );
        final CollabSessionRegistry reg = new CollabSessionRegistry( new InMemoryCollabStore(), real, permissions,
            key -> null, clock, new CollabConfig( true, 20, 65536, 25, 600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
        final CollabPageEventListener l = new CollabPageEventListener( reg, real );
        final Joined a = assertInstanceOf( Joined.class, reg.join( "P",
            new JoinRequest( "alice", null, "alice", "ALICE", null, null ) ) );
        assertEquals( BODY, a.session().view().baseBody() );

        // the provider fails: the engine's lenient text read answers "", the strict one throws
        when( pm.getPureText( eq( "P" ), anyInt() ) )
            .thenReturn( "" );
        when( pm.getPageText( eq( "P" ), anyInt() ) )
            .thenThrow( new ProviderException( "transient read failure" ) );
        l.actionPerformed( new WikiPageEvent( source, WikiPageEvent.POST_SAVE_END, "P" ) );
        reg.tick();

        assertEquals( BODY, a.session().view().baseBody(), "the base text survives the failed read" );
        assertEquals( BODY, a.session().view().doc() );
        assertTrue( a.session().view().reconcilePending(), "owed: the next settle retries the read" );
    }

    @Test
    void registerListensOnEverySource() {
        final PageManager pm = mock( PageManager.class );
        final FilterManager fm = mock( FilterManager.class );
        final PageRenamer renamer = mock( PageRenamer.class );
        final Object sessions = new Object();
        final Object auth = new Object();
        listener.register( pm, fm, renamer, sessions, auth, null );
        final Joined a = join( "alice" );

        writer.externalSave( PAGE, BODY + "ext\n", Map.of( "title", "T" ), "bob" );
        WikiEventManager.fireEvent( fm, new WikiPageEvent( fm, WikiPageEvent.POST_SAVE_END, PAGE ) );
        assertEquals( BODY + "ext\n", a.session().view().doc() );

        permissions.signedOut.add( "alice" );
        WikiEventManager.fireEvent( auth, new WikiSecurityEvent( auth, WikiSecurityEvent.LOGOUT, null, null ) );
        assertEquals( 0, a.session().view().participants() );

        final Joined b = join( "bob" );
        WikiEventManager.fireEvent( sessions, new WikiSecurityEvent( sessions, WikiSecurityEvent.SESSION_EXPIRED,
            null, "bob" ) );
        assertEquals( 0, b.session().view().participants() );

        writer.rename( PAGE, "Renamed" );
        WikiEventManager.fireEvent( renamer, new WikiPageRenameEvent( renamer, PAGE, "Renamed" ) );
        assertEquals( "Renamed", a.session().pageName() );

        writer.delete( "Renamed" );
        WikiEventManager.fireEvent( pm, new WikiPageEvent( pm, WikiPageEvent.PAGE_DELETED, "Renamed" ) );
        assertTrue( a.session().frozen() );
    }
}

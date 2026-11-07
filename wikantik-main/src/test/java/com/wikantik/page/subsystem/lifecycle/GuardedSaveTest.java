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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveGuard;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GuardedSaveTest {

    private TestEngine engine;
    private PageManager pm;
    private PageSaveHelper helper;
    /** Held strongly: WikiEventManager keeps listeners by weak reference. */
    private com.wikantik.event.WikiEventListener listener;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        pm = engine.getManager( PageManager.class );
        helper = new PageSaveHelper( engine, pm );
    }

    @AfterEach
    void tearDown() {
        for( final String n : new String[]{ "GuardRace", "GuardOk", "GuardWrote", "GuardSame", "GuardRe", "GuardStale",
                "GuardStaleSame", "GuardLocks", "GuardPut", "GuardPutA", "GuardPutB", "GuardReent", "GuardTarget",
                "GuardRenamed", "GuardReferrer", "GuardPostFail", "GuardPostRt", "GuardMoveFrom", "GuardMoveTo",
                "GuardDel" } ) {
            engine.deleteTestPage( n );
        }
        engine.stop();
    }

    private SaveOutcome save( final String name, final String text, final SaveGuard guard ) throws Exception {
        final Page page = Wiki.contents().page( engine, name );
        final Context ctx = Wiki.context().create( engine, page );
        return pm.saveText( ctx, text, guard );
    }

    private void holdFilter( final String pageName, final CountDownLatch entered, final CountDownLatch release )
            throws Exception {
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                if( pageName.equals( context.getPage().getName() ) && content.contains( "HOLD" ) ) {
                    entered.countDown();
                    try {
                        release.await( 20, TimeUnit.SECONDS );
                    } catch( final InterruptedException e ) {
                        Thread.currentThread().interrupt();
                    }
                }
                return content;
            }
        }, 100 );
    }

    @Test
    void aStrictSaveOvertakenWhileInItsFiltersIsRefused() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        holdFilter( "GuardRace", entered, release );
        save( "GuardRace", "seed", SaveGuard.NONE );

        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< SaveOutcome > a = ex.submit( () ->
                    helper.saveTracked( "GuardRace", "a HOLD", SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            assertEquals( 2, save( "GuardRace", "b", SaveGuard.NONE ).version() );
            release.countDown();

            final var thrown = assertThrows( java.util.concurrent.ExecutionException.class, () -> a.get( 20, TimeUnit.SECONDS ) );
            assertInstanceOf( VersionConflictException.class, thrown.getCause() );
            assertEquals( "b", pm.getPureText( "GuardRace", -1 ).trim() );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void anUncontendedStrictSaveSucceeds() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        release.countDown();
        holdFilter( "GuardOk", entered, release );
        save( "GuardOk", "seed", SaveGuard.NONE );

        final SaveOutcome out = helper.saveTracked( "GuardOk", "a HOLD",
                SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() );
        assertEquals( 2, out.version() );
        assertFalse( out.unchanged() );
        assertEquals( "a HOLD", pm.getPureText( "GuardOk", -1 ).trim() );
    }

    @Test
    void theOutcomeIsTheVersionThisCallWrote() throws Exception {
        save( "GuardWrote", "one", SaveGuard.NONE );
        final AtomicBoolean fired = new AtomicBoolean();
        listener = event -> {
            if( event instanceof WikiPageEvent e && e.getType() == WikiPageEvent.POST_SAVE_END
                    && "GuardWrote".equals( e.getPageName() ) && fired.compareAndSet( false, true ) ) {
                try {
                    save( "GuardWrote", "three", SaveGuard.NONE );
                } catch( final Exception ex ) {
                    throw new IllegalStateException( ex );
                }
            }
        };
        WikiEventManager.addWikiEventListener( engine.getManager( FilterManager.class ), listener );
        final SaveOutcome out = save( "GuardWrote", "two", SaveGuard.NONE );
        assertTrue( fired.get() );
        assertEquals( 2, out.version() );
        assertEquals( 2, out.page().getVersion() );
        assertEquals( 3, pm.getPage( "GuardWrote" ).getVersion() );
    }

    @Test
    void anUnchangedTextReportsUnchangedAndWritesNothing() throws Exception {
        save( "GuardSame", "same", SaveGuard.NONE );
        final int before = pm.getPage( "GuardSame" ).getVersion();
        final SaveOutcome out = save( "GuardSame", "same", SaveGuard.NONE );
        assertTrue( out.unchanged() );
        assertEquals( before, out.version() );
        assertEquals( before, pm.getPage( "GuardSame" ).getVersion() );
    }

    private DefaultPageLifecycle lifecycle() {
        return (DefaultPageLifecycle) ( (com.wikantik.pages.DefaultPageManager) pm ).getLifecycle();
    }

    /**
     * Post-save filters and events run after the page lock is released: a POST_SAVE_END listener sees no page lock
     * registered, and a save of the same page from ANOTHER thread (which a held lock would block, reentrant or not)
     * completes.
     */
    @Test
    void postSaveEventsRunOutsideThePageLock() throws Exception {
        final AtomicBoolean fired = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger locksSeen = new java.util.concurrent.atomic.AtomicInteger( -1 );
        final java.util.concurrent.atomic.AtomicReference< Throwable > innerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        final ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            listener = event -> {
                if( event instanceof WikiPageEvent e && e.getType() == WikiPageEvent.POST_SAVE_END
                        && "GuardRe".equals( e.getPageName() ) && fired.compareAndSet( false, true ) ) {
                    locksSeen.set( lifecycle().lockCount() );
                    try {
                        other.submit( () -> save( "GuardRe", "inner", SaveGuard.NONE ) ).get( 5, TimeUnit.SECONDS );
                    } catch( final Exception ex ) {
                        innerFailure.set( ex );
                    }
                }
            };
            WikiEventManager.addWikiEventListener( engine.getManager( FilterManager.class ), listener );
            assertTimeoutPreemptively( Duration.ofSeconds( 20 ), () -> save( "GuardRe", "outer", SaveGuard.NONE ) );
        } finally {
            other.shutdownNow();
        }
        assertTrue( fired.get() );
        assertEquals( 0, locksSeen.get(), "no page lock may be held while post-save events run" );
        assertNull( innerFailure.get(), () -> "a save from another thread during POST_SAVE_END must not block: "
                + innerFailure.get() );
        assertEquals( "inner", pm.getPureText( "GuardRe", -1 ).trim() );
    }

    @Test
    void aStaleStrictSaveIsRefusedBeforeThePreSaveFiltersRun() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger preSaves = new java.util.concurrent.atomic.AtomicInteger();
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                if( "GuardStale".equals( context.getPage().getName() ) ) {
                    preSaves.incrementAndGet();
                }
                return content;
            }
        }, 100 );
        save( "GuardStale", "one", SaveGuard.NONE );
        save( "GuardStale", "two", SaveGuard.NONE );
        final int before = preSaves.get();
        assertThrows( VersionConflictException.class, () -> helper.saveTracked( "GuardStale", "three",
                SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() ) );
        assertEquals( before, preSaves.get(), "a stale strict save must fail fast, before any pre-save filter" );
        assertEquals( "two", pm.getPureText( "GuardStale", -1 ).trim() );
    }

    @Test
    void aStaleStrictSaveOfIdenticalTextStillConflicts() throws Exception {
        save( "GuardStaleSame", "one", SaveGuard.NONE );
        save( "GuardStaleSame", "two", SaveGuard.NONE );
        final int latest = pm.getPage( "GuardStaleSame" ).getVersion();
        assertThrows( VersionConflictException.class, () -> helper.saveTracked( "GuardStaleSame", "two",
                SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() ) );
        assertEquals( latest, pm.getPage( "GuardStaleSame" ).getVersion() );
    }

    @Test
    void theLockRegistryIsEmptyAfterSaves() throws Exception {
        save( "GuardLocks", "one", SaveGuard.NONE );
        save( "GuardLocks", "one", SaveGuard.NONE );
        assertThrows( VersionConflictException.class, () -> helper.saveTracked( "GuardLocks", "x",
                SaveOptions.builder().author( "A" ).expectedVersion( 7 ).build() ) );
        save( "GuardLocks", "two", SaveGuard.NONE );
        assertEquals( 0, lifecycle().lockCount() );
    }

    // --- Unevented writers (putPageText, rename referrer rewrites) take the same page lock ---

    /**
     * A guard that blocks on its second evaluation: the authoritative one, made under the page lock immediately before
     * the write (the first is the fail-fast check before the pre-save filters).
     */
    private static SaveGuard blockingAtTheWrite( final CountDownLatch entered, final CountDownLatch release ) {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        return ( current, text ) -> {
            if( calls.incrementAndGet() == 2 ) {
                entered.countDown();
                try {
                    release.await( 20, TimeUnit.SECONDS );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /** Waits until a thread queues on the lock of {@code name}, or {@code task} finishes (it did not wait). */
    private void awaitQueuedOrDone( final String name, final Future< ? > task ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 10 );
        while( lifecycle().waitingOn( name ) == 0 && !task.isDone() && System.nanoTime() < deadline ) {
            Thread.sleep( 5 );
        }
    }

    @Test
    void aPutPageTextWaitsForAGuardedSaveOfThatPage() throws Exception {
        save( "GuardPut", "seed", SaveGuard.NONE );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< SaveOutcome > a = ex.submit( () -> save( "GuardPut", "guarded", blockingAtTheWrite( entered, release ) ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            final Future< ? > b = ex.submit( () -> {
                pm.putPageText( pm.getPage( "GuardPut" ), "put" );
                return null;
            } );
            awaitQueuedOrDone( "GuardPut", b );
            assertFalse( b.isDone(), "putPageText must wait while a guarded save of the page holds its lock" );
            release.countDown();
            a.get( 20, TimeUnit.SECONDS );
            b.get( 20, TimeUnit.SECONDS );
            assertEquals( "put", pm.getPureText( "GuardPut", -1 ).trim() );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void putPageTextOfAnotherPageDoesNotWait() throws Exception {
        save( "GuardPutA", "seed", SaveGuard.NONE );
        save( "GuardPutB", "seed", SaveGuard.NONE );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< SaveOutcome > a = ex.submit( () -> save( "GuardPutA", "guarded", blockingAtTheWrite( entered, release ) ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            ex.submit( () -> {
                pm.putPageText( pm.getPage( "GuardPutB" ), "put" );
                return null;
            } ).get( 10, TimeUnit.SECONDS );
            assertFalse( a.isDone() );
            assertEquals( "put", pm.getPureText( "GuardPutB", -1 ).trim() );
            release.countDown();
            a.get( 20, TimeUnit.SECONDS );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void aReentrantPutInsideTheGuardedSectionDoesNotDeadlock() throws Exception {
        save( "GuardReent", "seed", SaveGuard.NONE );
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final SaveGuard putting = ( current, text ) -> {
            if( calls.incrementAndGet() == 2 ) {
                try {
                    pm.putPageText( pm.getPage( "GuardReent" ), "inner" );
                } catch( final com.wikantik.api.exceptions.ProviderException e ) {
                    throw new IllegalStateException( e );
                }
            }
        };
        final SaveOutcome out = assertTimeoutPreemptively( Duration.ofSeconds( 10 ), () -> save( "GuardReent", "outer", putting ) );
        assertEquals( 3, out.version() );
        assertEquals( "inner", pm.getPureText( "GuardReent", 2 ).trim() );
        assertEquals( "outer", pm.getPureText( "GuardReent", -1 ).trim() );
        assertEquals( 0, lifecycle().lockCount() );
    }

    @Test
    void aReferrerRewriteDoesNotLoseAConcurrentSave() throws Exception {
        save( "GuardTarget", "the target", SaveGuard.NONE );
        save( "GuardReferrer", "see [GuardTarget]() here", SaveGuard.NONE );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< SaveOutcome > a = ex.submit( () -> save( "GuardReferrer", "see [GuardTarget]() here, and the saved edit",
                    blockingAtTheWrite( entered, release ) ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            final Future< String > rename = ex.submit( () -> {
                final Context ctx = Wiki.context().create( engine, pm.getPage( "GuardTarget" ) );
                return engine.getManager( com.wikantik.content.PageRenamer.class )
                        .renamePage( ctx, "GuardTarget", "GuardRenamed", true );
            } );
            awaitQueuedOrDone( "GuardReferrer", rename );
            assertTrue( lifecycle().waitingOn( "GuardReferrer" ) > 0, "the referrer rewrite must queue on the referrer's lock" );
            release.countDown();
            a.get( 20, TimeUnit.SECONDS );
            assertEquals( "GuardRenamed", rename.get( 20, TimeUnit.SECONDS ) );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        final String referrer = pm.getPureText( "GuardReferrer", -1 );
        assertTrue( referrer.contains( "and the saved edit" ), "the concurrent save survives: " + referrer );
        assertTrue( referrer.contains( "[GuardRenamed]()" ), "the link is rewritten: " + referrer );
        assertFalse( referrer.contains( "[GuardTarget]()" ), "no stale link remains: " + referrer );
    }

    /**
     * The rename reads the old page's text and re-writes it under the new name: a save of the old page that is mid-write
     * when the rename starts must land before the rename's read (its text moves to the new name), never be reverted by
     * the rename writing the text it read before the save.
     */
    @Test
    void aSaveOfTheOldPageDuringARenameIsNeverReverted() throws Exception {
        save( "GuardMoveFrom", "the original", SaveGuard.NONE );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< SaveOutcome > a = ex.submit( () -> save( "GuardMoveFrom", "the saved edit",
                    blockingAtTheWrite( entered, release ) ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            final Future< String > rename = ex.submit( () -> {
                final Context ctx = Wiki.context().create( engine, pm.getPage( "GuardMoveFrom" ) );
                return engine.getManager( com.wikantik.content.PageRenamer.class )
                        .renamePage( ctx, "GuardMoveFrom", "GuardMoveTo", true );
            } );
            awaitQueuedOrDone( "GuardMoveFrom", rename );
            assertTrue( lifecycle().waitingOn( "GuardMoveFrom" ) > 0, "the rename must queue on the old name's lock" );
            release.countDown();
            a.get( 20, TimeUnit.SECONDS );
            assertEquals( "GuardMoveTo", rename.get( 20, TimeUnit.SECONDS ) );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        assertEquals( "the saved edit", pm.getPureText( "GuardMoveTo", -1 ).trim() );
        assertNull( pm.getPage( "GuardMoveFrom" ) );
        assertEquals( 0, lifecycle().lockCount() );
    }

    /** Two pages' locks are taken in name order whatever order the caller names them in. */
    @Test
    void twoPageLocksAreTakenInNameOrder() throws Exception {
        final CountDownLatch holding = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< ? > first = ex.submit( () -> lifecycle().underPageLocks( "LockZ", "LockA", () -> {
                holding.countDown();
                try {
                    release.await( 20, TimeUnit.SECONDS );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
                return null;
            } ) );
            assertTrue( holding.await( 20, TimeUnit.SECONDS ) );
            final Future< ? > second = ex.submit( () -> lifecycle().underPageLocks( "LockA", "LockZ", () -> null ) );
            awaitQueuedOrDone( "LockA", second );
            assertEquals( 1, lifecycle().waitingOn( "LockA" ), "the second caller waits on the lower name first" );
            assertEquals( 0, lifecycle().waitingOn( "LockZ" ) );
            release.countDown();
            first.get( 20, TimeUnit.SECONDS );
            second.get( 20, TimeUnit.SECONDS );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        assertEquals( 0, lifecycle().lockCount() );
    }

    /**
     * A delete waits for a guarded save that already passed its "the page exists" check (the collab publish's guard), so
     * the save lands before the delete and the delete removes it: the save never recreates the deleted page.
     */
    @Test
    void aGuardedSaveRacingADeleteNeverRecreatesThePage() throws Exception {
        save( "GuardDel", "one", SaveGuard.NONE );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final SaveGuard blocking = blockingAtTheWrite( entered, release );
        final SaveGuard mustExist = ( current, text ) -> {
            if( current == null ) {
                throw VersionConflictException.pageGone( 1 );
            }
            blocking.check( current, text );
        };
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< SaveOutcome > a = ex.submit( () -> save( "GuardDel", "two", mustExist ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            final Future< ? > delete = ex.submit( () -> {
                pm.deletePage( "GuardDel" );
                return null;
            } );
            awaitQueuedOrDone( "GuardDel", delete );
            assertTrue( lifecycle().waitingOn( "GuardDel" ) > 0, "the delete must queue on the page's lock" );
            release.countDown();
            assertEquals( 2, a.get( 20, TimeUnit.SECONDS ).version() );
            delete.get( 20, TimeUnit.SECONDS );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        assertNull( pm.getPage( "GuardDel" ), "the delete ran last: the page stays deleted" );
        assertThrows( VersionConflictException.class, () -> save( "GuardDel", "three", mustExist ) );
        assertNull( pm.getPage( "GuardDel" ) );
    }

    // --- Ruling I2: a save that persisted and then failed in its post-save handling reports what it wrote ---

    private void failingPostSave( final String pageName, final Exception failure ) {
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public void postSave( final Context context, final String content ) throws com.wikantik.api.exceptions.FilterException {
                if( pageName.equals( context.getPage().getName() ) && content.contains( "FAIL" ) ) {
                    if( failure instanceof RuntimeException re ) {
                        throw re;
                    }
                    throw (com.wikantik.api.exceptions.FilterException) failure;
                }
            }
        }, 100 );
    }

    @Test
    void aPostSaveFilterFailureStillReportsTheWrittenVersion() throws Exception {
        final var failure = new com.wikantik.api.exceptions.FilterException( "post-save filter failed" );
        failingPostSave( "GuardPostFail", failure );
        save( "GuardPostFail", "seed", SaveGuard.NONE );
        final var thrown = assertThrows( com.wikantik.api.pages.SavedButPostSaveFailedException.class,
                () -> save( "GuardPostFail", "FAIL this time", SaveGuard.NONE ) );
        assertSame( failure, thrown.getCause() );
        assertEquals( 2, thrown.outcome().version() );
        assertEquals( 2, thrown.outcome().page().getVersion() );
        assertFalse( thrown.outcome().unchanged() );
        assertEquals( "FAIL this time", pm.getPureText( "GuardPostFail", 2 ).trim() );
        assertEquals( 0, lifecycle().lockCount() );
    }

    @Test
    void aRuntimeFailureAfterTheWriteStillReportsTheWrittenVersion() throws Exception {
        final var failure = new IllegalStateException( "listener blew up" );
        failingPostSave( "GuardPostRt", failure );
        final var thrown = assertThrows( com.wikantik.api.pages.SavedButPostSaveFailedException.class,
                () -> helper.saveTracked( "GuardPostRt", "FAIL at once", SaveOptions.builder().author( "A" ).build() ) );
        assertSame( failure, thrown.getCause() );
        assertEquals( 1, thrown.outcome().version() );
        assertInstanceOf( com.wikantik.api.exceptions.WikiException.class, thrown, "existing callers still see a WikiException" );
    }
}

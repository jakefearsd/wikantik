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

import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.api.exceptions.WikiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedPageWriterTest {

    private static final Map< String, Object > META = Map.of();

    private static PublishRequest req( final String body, final int expected ) {
        return new PublishRequest( "P", body, META, expected, "alice", "note" );
    }

    private static ScriptedPageWriter seeded() {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( "P", "one", META );
        return w;
    }

    @Test
    void seedCreatesVersionOneStoredAsCrlf() {
        final ScriptedPageWriter w = seeded();
        final PageState s = w.latest( "P" ).orElseThrow();
        assertEquals( 1, s.version() );
        assertEquals( "one\r\n", s.body() );
        assertEquals( com.wikantik.collab.text.CollabText.contentHash( "one\n" ), s.contentHash(),
            "content hash basis: SHA-256 hex of the LF body" );
        assertTrue( w.latest( "Missing" ).isEmpty() );
        assertTrue( w.version( "P", 2 ).isEmpty() );
        assertEquals( "one\r\n", w.version( "P", 1 ).orElseThrow().body() );
    }

    @Test
    void publishStoresCrlfAndBumpsVersion() throws Exception {
        final ScriptedPageWriter w = seeded();
        final PublishOutcome o = w.publish( req( "a\nb", 1 ) );
        assertEquals( 2, o.version() );
        assertEquals( "a\r\nb\r\n", o.savedBody() );
        assertFalse( o.unchanged() );
        assertEquals( List.of( "one\r\n", "a\r\nb\r\n" ), w.history( "P" ) );
    }

    @Test
    void staleExpectedVersionConflicts() throws Exception {
        final ScriptedPageWriter w = seeded();
        w.publish( req( "two", 1 ) );
        final VersionConflictException e = assertThrows( VersionConflictException.class, () -> w.publish( req( "x", 1 ) ) );
        assertEquals( 2, e.getCurrentVersion() );
        assertEquals( 3, w.publish( req( "x", 2 ) ).version() );
    }

    /** A11: expectedVersion 0 means "the page must not exist". */
    @Test
    void expectedVersionZeroOnAnExistingPageConflicts() throws Exception {
        final ScriptedPageWriter w = seeded();
        final VersionConflictException e = assertThrows( VersionConflictException.class, () -> w.publish( req( "x", 0 ) ) );
        assertEquals( 1, e.getCurrentVersion() );
        assertEquals( 1, w.history( "P" ).size() );
        final ScriptedPageWriter fresh = new ScriptedPageWriter();
        assertEquals( 1, fresh.publish( req( "new", 0 ) ).version() );
    }

    /**
     * A positive expectedVersion on a missing page (renamed or deleted away) is refused with the adapter's page-gone
     * conflict, never a recreate (Phase 2 Task 4's rule); nothing is stored, not even an empty page entry.
     */
    @Test
    void positiveExpectedVersionOnAMissingPageIsPageGone() {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        final VersionConflictException e = assertThrows( VersionConflictException.class,
            () -> w.publish( req( "created", 3 ) ) );
        assertEquals( ScriptedPageWriter.PAGE_GONE, e.getMessage() );
        assertEquals( 3, e.getExpectedVersion() );
        assertTrue( w.latest( "P" ).isEmpty() );
        assertEquals( List.of(), w.history( "P" ) );
    }

    @Test
    void pageStateCarriesTheAuthorKeyThatWroteTheVersion() throws Exception {
        final ScriptedPageWriter w = seeded();
        assertNull( w.latest( "P" ).orElseThrow().author(), "a seeded version has no known author" );
        w.publish( req( "two", 1 ) );
        assertEquals( "alice", w.latest( "P" ).orElseThrow().author() );
        w.externalSave( "P", "three", META, "bob" );
        assertEquals( "bob", w.latest( "P" ).orElseThrow().author() );
        assertEquals( "alice", w.version( "P", 2 ).orElseThrow().author() );
    }

    @Test
    void metaWithNullValuesIsTolerated() throws Exception {
        final Map< String, Object > meta = new java.util.HashMap<>();
        meta.put( "summary", null );
        meta.put( "title", "T" );
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( "P", "one", meta );
        assertTrue( w.latest( "P" ).orElseThrow().meta().containsKey( "summary" ) );
        w.externalSave( "P", "two", meta, "bob" );
        final PublishOutcome o = w.publish( new PublishRequest( "P", "three", meta, 2, "alice", "" ) );
        assertEquals( 3, o.version() );
        assertTrue( w.latest( "P" ).orElseThrow().meta().containsKey( "summary" ) );
        assertNull( w.latest( "P" ).orElseThrow().meta().get( "summary" ) );
    }

    @Test
    void aPausedPublishThatIsNeverReleasedTimesOut() {
        final ScriptedPageWriter w = seeded();
        w.releaseTimeout( Duration.ofMillis( 50 ) );
        w.pauseNextPublish();
        final IllegalStateException e = assertThrows( IllegalStateException.class, () -> w.publish( req( "x", 1 ) ) );
        assertTrue( e.getMessage().contains( "released" ), e.getMessage() );
        assertEquals( 1, w.history( "P" ).size() );
    }

    @Test
    void releaseTimeoutDefaultsToThirtySeconds() {
        assertEquals( Duration.ofSeconds( 30 ), ScriptedPageWriter.DEFAULT_RELEASE_TIMEOUT );
    }

    @Test
    void identicalTextIsUnchangedWithNoNewVersion() throws Exception {
        final ScriptedPageWriter w = seeded();
        final PublishOutcome o = w.publish( req( "one", 1 ) );
        assertTrue( o.unchanged() );
        assertEquals( 1, o.version() );
        assertEquals( 1, w.history( "P" ).size() );
    }

    @Test
    void rewriteOnSaveTransformsTheText() throws Exception {
        final ScriptedPageWriter w = seeded();
        w.rewriteOnSave( s -> s.toUpperCase() );
        final PublishOutcome o = w.publish( req( "abc", 1 ) );
        assertEquals( "ABC\r\n", o.savedBody() );
        assertEquals( "ABC\r\n", w.latest( "P" ).orElseThrow().body() );
    }

    @Test
    void failNextPublishFailsOnceAndStoresNothing() throws Exception {
        final ScriptedPageWriter w = seeded();
        w.failNextPublish( new WikiException( "boom" ) );
        assertThrows( WikiException.class, () -> w.publish( req( "x", 1 ) ) );
        assertEquals( 1, w.latest( "P" ).orElseThrow().version() );
        assertEquals( 2, w.publish( req( "x", 1 ) ).version() );
    }

    @Test
    void persistThenFailStoresTheVersion() {
        final ScriptedPageWriter w = seeded();
        w.persistThenFail( new WikiException( "late" ) );
        assertThrows( WikiException.class, () -> w.publish( req( "x", 1 ) ) );
        assertEquals( 2, w.latest( "P" ).orElseThrow().version() );
        assertEquals( "x\r\n", w.latest( "P" ).orElseThrow().body() );
    }

    @Test
    void externalSaveBumpsVersionAndNotifiesListeners() {
        final ScriptedPageWriter w = seeded();
        final List< ScriptedPageWriter.ExternalChange > seen = new ArrayList<>();
        w.onExternalChange( seen::add );
        assertEquals( 2, w.externalSave( "P", "ext", META, "bob" ) );
        assertEquals( List.of( new ScriptedPageWriter.ExternalChange( "P", 2, "bob" ) ), seen );
        assertEquals( "ext\r\n", w.latest( "P" ).orElseThrow().body() );
    }

    @Test
    void overwriteWithoutVersionChangesBodySilently() {
        final ScriptedPageWriter w = seeded();
        final List< ScriptedPageWriter.ExternalChange > seen = new ArrayList<>();
        w.onExternalChange( seen::add );
        final String before = w.latest( "P" ).orElseThrow().contentHash();
        w.overwriteWithoutVersion( "P", "sneaky" );
        final PageState s = w.latest( "P" ).orElseThrow();
        assertEquals( 1, s.version() );
        assertEquals( "sneaky\r\n", s.body() );
        assertNotEquals( before, s.contentHash() );
        assertTrue( seen.isEmpty() );
        assertEquals( List.of( "sneaky\r\n" ), w.history( "P" ) );
    }

    @Test
    void renameMovesHistoryAndDeleteRemovesThePage() throws Exception {
        final ScriptedPageWriter w = seeded();
        w.publish( req( "two", 1 ) );
        w.rename( "P", "Q" );
        assertTrue( w.latest( "P" ).isEmpty() );
        assertEquals( 2, w.latest( "Q" ).orElseThrow().version() );
        w.delete( "Q" );
        assertTrue( w.latest( "Q" ).isEmpty() );
        assertTrue( w.history( "Q" ).isEmpty() );
    }

    @Test
    void aPausedPublishBlocksUntilReleased() throws Exception {
        final ScriptedPageWriter w = seeded();
        final ScriptedPageWriter.PauseHandle h = w.pauseNextPublish();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< PublishOutcome > f = ex.submit( () -> w.publish( req( "x", 1 ) ) );
            h.awaitEntered( Duration.ofSeconds( 5 ) );
            assertTrue( h.entered() );
            assertFalse( f.isDone() );
            assertEquals( 1, w.latest( "P" ).orElseThrow().version() );
            h.release();
            assertEquals( 2, f.get( 5, TimeUnit.SECONDS ).version() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void anExternalSaveDuringAPauseMakesTheResumedPublishConflict() throws Exception {
        final ScriptedPageWriter w = seeded();
        final ScriptedPageWriter.PauseHandle h = w.pauseNextPublish();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< PublishOutcome > f = ex.submit( () -> w.publish( req( "x", 1 ) ) );
            h.awaitEntered( Duration.ofSeconds( 5 ) );
            w.externalSave( "P", "ext", META, "bob" );
            h.release();
            final var err = assertThrows( java.util.concurrent.ExecutionException.class, () -> f.get( 5, TimeUnit.SECONDS ) );
            assertInstanceOf( VersionConflictException.class, err.getCause() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void releaseWithFailsThePausedPublish() throws Exception {
        final ScriptedPageWriter w = seeded();
        final ScriptedPageWriter.PauseHandle h = w.pauseNextPublish();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< PublishOutcome > f = ex.submit( () -> w.publish( req( "x", 1 ) ) );
            h.awaitEntered( Duration.ofSeconds( 5 ) );
            h.releaseWith( new WikiException( "nope" ) );
            final var err = assertThrows( java.util.concurrent.ExecutionException.class, () -> f.get( 5, TimeUnit.SECONDS ) );
            assertEquals( "nope", err.getCause().getMessage() );
            assertEquals( 1, w.latest( "P" ).orElseThrow().version() );
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void awaitEnteredTimesOutWhenNothingPublishes() {
        final ScriptedPageWriter.PauseHandle h = seeded().pauseNextPublish();
        assertThrows( IllegalStateException.class, () -> h.awaitEntered( Duration.ofMillis( 50 ) ) );
    }

    @Test
    void publishAsyncSharesThePausePointForTheSingleThreadedSimulator() throws Exception {
        final ScriptedPageWriter w = seeded();
        final ScriptedPageWriter.PauseHandle h = w.pauseNextPublish();
        final ScriptedPageWriter.PendingPublish p = w.publishAsync( req( "x", 1 ) );
        assertTrue( h.entered() );
        assertFalse( p.isDone() );
        assertEquals( 1, w.latest( "P" ).orElseThrow().version() );
        assertEquals( 2, p.complete().version() );
        assertTrue( p.isDone() );
    }

    @Test
    void publishAsyncWithoutAPauseCompletesImmediatelyAndSurfacesFailureOnComplete() {
        final ScriptedPageWriter w = seeded();
        final ScriptedPageWriter.PendingPublish ok = w.publishAsync( req( "x", 1 ) );
        assertTrue( ok.isDone() );
        final ScriptedPageWriter.PendingPublish stale = w.publishAsync( req( "y", 1 ) );
        assertThrows( VersionConflictException.class, stale::complete );
    }

    @Test
    void pendingPublishCompleteWithFails() {
        final ScriptedPageWriter w = seeded();
        w.pauseNextPublish();
        final ScriptedPageWriter.PendingPublish p = w.publishAsync( req( "x", 1 ) );
        assertThrows( WikiException.class, () -> p.completeWith( new WikiException( "x" ) ) );
        assertEquals( 1, w.latest( "P" ).orElseThrow().version() );
    }

    @Test
    void concurrentPublishesWithTheSameExpectedVersionHaveOneWinner() throws Exception {
        final ScriptedPageWriter w = seeded();
        final int n = 8;
        final CountDownLatch go = new CountDownLatch( 1 );
        final ExecutorService ex = Executors.newFixedThreadPool( n );
        try {
            final List< Future< Boolean > > fs = new ArrayList<>();
            for( int i = 0; i < n; i++ ) {
                final String body = "body" + i;
                fs.add( ex.submit( () -> {
                    go.await();
                    try {
                        w.publish( req( body, 1 ) );
                        return true;
                    } catch( final VersionConflictException e ) {
                        return false;
                    }
                } ) );
            }
            go.countDown();
            int winners = 0;
            for( final Future< Boolean > f : fs ) {
                if( f.get( 5, TimeUnit.SECONDS ) ) {
                    winners++;
                }
            }
            assertEquals( 1, winners );
        } finally {
            ex.shutdownNow();
        }
    }
}

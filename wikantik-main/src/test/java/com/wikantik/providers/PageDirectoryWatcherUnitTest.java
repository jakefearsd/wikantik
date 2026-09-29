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
package com.wikantik.providers;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.search.SearchManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-mock unit tests for {@link PageDirectoryWatcher}, complementing the heavier
 * {@link PageDirectoryWatcherTest} (which drives a full {@code TestEngine}). Those
 * end-to-end tests assert observable {@code PageManager} behavior that, on the real
 * {@code CachingProvider}, turns out to be independently satisfied by the provider's own
 * staleness checks — so several watcher-internal code paths (external deletion handling,
 * the classify-event filters, the "no watcher yet" and "directory never appeared" guards,
 * and the periodic guard-map cleanup) are exercised here instead, against a lightweight
 * mock {@link Engine}/{@link AbstractFileProvider}/{@link CachingManager} triad and a real
 * {@link java.nio.file.WatchService} over a JUnit {@code @TempDir}.
 */
@Execution( ExecutionMode.SAME_THREAD )
class PageDirectoryWatcherUnitTest {

    @TempDir
    Path tempDir;

    private AbstractFileProvider fileProvider;
    private CachingManager cachingManager;
    private ReferenceManager referenceManager;
    private SearchManager searchManager;
    private Engine engine;
    private PageDirectoryWatcher watcher;

    @BeforeEach
    void setUp() {
        fileProvider = mock( AbstractFileProvider.class );
        when( fileProvider.getPageDirectory() ).thenReturn( tempDir.toString() );
        when( fileProvider.unmangleName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        cachingManager = mock( CachingManager.class );
        referenceManager = mock( ReferenceManager.class );
        searchManager = mock( SearchManager.class );

        engine = MockEngineBuilder.engine()
                .with( ReferenceManager.class, referenceManager )
                .with( SearchManager.class, searchManager )
                .build();

        watcher = spy( buildWatcher() );
    }

    private PageDirectoryWatcher buildWatcher() {
        return new PageDirectoryWatcher( engine, 1, fileProvider, cachingManager );
    }

    @AfterEach
    void tearDown() throws Exception {
        watcher.shutdownTask();
    }

    /** Repeatedly invokes {@code backgroundTask()} until {@code condition} is satisfied. */
    private void pollBackgroundTaskUntil( final java.util.concurrent.Callable< Boolean > condition ) {
        Awaitility.await()
                .atMost( 10, TimeUnit.SECONDS )
                .pollInterval( 50, TimeUnit.MILLISECONDS )
                .until( () -> {
                    watcher.backgroundTask();
                    return condition.call();
                } );
    }

    // ---- backgroundTask() no-op before startupTask() ----

    @Test
    void testBackgroundTaskIsNoOpWhenWatchServiceNotYetStarted() throws Exception {
        // startupTask() was never called, so the watchService field is still null.
        assertDoesNotThrow( () -> watcher.backgroundTask() );
        verify( cachingManager, never() ).remove( anyString(), anyString() );
    }

    // ---- startupTask() gives up when the page directory never appears ----

    @Test
    void testStartupTaskLogsErrorAndReturnsWhenDirectoryNeverAppears() throws Exception {
        final AbstractFileProvider missingDirProvider = mock( AbstractFileProvider.class );
        when( missingDirProvider.getPageDirectory() )
                .thenReturn( tempDir.resolve( "never-created" ).toString() );
        final PageDirectoryWatcher missingDirWatcher =
                new PageDirectoryWatcher( engine, 1, missingDirProvider, cachingManager );

        // The wait loop is 50 * 100ms = 5s; this test's runtime reflects that real wait.
        assertDoesNotThrow( missingDirWatcher::startupTask );

        // No watchService was ever created, so backgroundTask() must still be a no-op.
        assertDoesNotThrow( missingDirWatcher::backgroundTask );
        missingDirWatcher.shutdownTask();
    }

    // ---- classifyEvent() filters ----

    @Test
    void testExternalCreationSkippedWhenUnmangleNameReturnsNull() throws Exception {
        when( fileProvider.unmangleName( anyString() ) ).thenReturn( null );
        watcher.startupTask();

        Files.writeString( tempDir.resolve( "Orphan.md" ), "content" );

        pollBackgroundTaskUntil( () -> mockingDetails( fileProvider ).getInvocations().stream()
                .anyMatch( inv -> inv.getMethod().getName().equals( "unmangleName" ) ) );

        // unmangleName() returning null means filenameToPageName() returns null too,
        // so classifyEvent() must bail out before ever touching the cache.
        verify( cachingManager, never() ).remove( anyString(), anyString() );
    }

    @Test
    void testNonPageFileIsIgnoredByClassifyEvent() throws Exception {
        watcher.startupTask();

        Files.writeString( tempDir.resolve( "notes.properties" ), "irrelevant" );
        // Also write a real page file so we have a positive signal that polling worked at all.
        Files.writeString( tempDir.resolve( "RealPage.md" ), "content" );

        pollBackgroundTaskUntil( () -> mockingDetails( cachingManager ).getInvocations().stream()
                .anyMatch( inv -> "remove".equals( inv.getMethod().getName() ) ) );

        verify( cachingManager, never() ).remove( anyString(), eq( "notes" ) );
        verify( cachingManager, never() ).remove( anyString(), eq( "notes.properties" ) );
    }

    @Test
    void testInternalSaveGuardSuppressesExternalCreationEvent() throws Exception {
        // Default 5000ms guard (from PROP_INTERNAL_SAVE_GUARD_MILLIS's default) easily
        // outlasts this test's short polling window.
        watcher.notifyInternalSave( "Guarded" );
        assertTrue( watcher.isRecentInternalSave( "Guarded" ), "guard should be active immediately after notifyInternalSave" );

        watcher.startupTask();
        Files.writeString( tempDir.resolve( "Guarded.md" ), "content" );

        pollBackgroundTaskUntil( () -> mockingDetails( watcher ).getInvocations().stream()
                .anyMatch( inv -> inv.getMethod().getName().equals( "isRecentInternalSave" )
                        && "Guarded".equals( inv.getArgument( 0 ) ) ) );

        // The guard was active throughout, so the event must never have reached processing.
        verify( cachingManager, never() ).remove( anyString(), eq( "Guarded" ) );
        assertTrue( watcher.isRecentInternalSave( "Guarded" ), "guard should still be active — test ran well within the 5s window" );
    }

    // ---- backgroundTask() external creation/modification -> processCreatedOrModified() ----

    @Test
    void testExternalCreationInvalidatesCachesAndNotifiesCollaborators() throws Exception {
        watcher.startupTask();
        Files.writeString( tempDir.resolve( "NewPage.md" ), "content" );

        pollBackgroundTaskUntil( () -> mockingDetails( cachingManager ).getInvocations().stream()
                .anyMatch( inv -> "remove".equals( inv.getMethod().getName() ) ) );

        verify( cachingManager ).remove( CachingManager.CACHE_PAGES, "NewPage" );
        verify( cachingManager ).remove( CachingManager.CACHE_PAGES_TEXT, "NewPage" );
        verify( cachingManager ).remove( CachingManager.CACHE_PAGES_HISTORY, "NewPage" );
        verify( fileProvider ).invalidateFileExtensionCache( "NewPage" );
        verify( referenceManager ).updateReferences( any( Page.class ) );
        verify( searchManager ).reindexPage( any( Page.class ) );
    }

    @Test
    void testExceptionFromReferenceManagerDuringCreationIsCaughtAndLogged() throws Exception {
        doThrow( new RuntimeException( "boom" ) ).when( referenceManager ).updateReferences( any( Page.class ) );
        watcher.startupTask();
        Files.writeString( tempDir.resolve( "Boom.md" ), "content" );

        // Must not propagate out of backgroundTask() despite the collaborator failure.
        pollBackgroundTaskUntil( () -> mockingDetails( referenceManager ).getInvocations().stream()
                .anyMatch( inv -> inv.getMethod().getName().equals( "updateReferences" ) ) );

        verify( cachingManager ).remove( CachingManager.CACHE_PAGES, "Boom" );
    }

    // ---- backgroundTask() external deletion -> processDeleted() ----

    @Test
    void testExternalDeletionInvalidatesCachesAndNotifiesCollaborators() throws Exception {
        final File f = tempDir.resolve( "GoneSoon.md" ).toFile();
        Files.writeString( f.toPath(), "content" );

        watcher.startupTask();
        assertTrue( f.delete(), "setup: file must be deletable" );

        pollBackgroundTaskUntil( () -> mockingDetails( cachingManager ).getInvocations().stream()
                .anyMatch( inv -> "remove".equals( inv.getMethod().getName() ) ) );

        verify( cachingManager ).remove( CachingManager.CACHE_PAGES, "GoneSoon" );
        verify( cachingManager ).remove( CachingManager.CACHE_PAGES_TEXT, "GoneSoon" );
        verify( cachingManager ).remove( CachingManager.CACHE_PAGES_HISTORY, "GoneSoon" );
        verify( fileProvider ).invalidateFileExtensionCache( "GoneSoon" );
        verify( referenceManager ).pageRemoved( any( Page.class ) );
        verify( searchManager ).pageRemoved( any( Page.class ) );
    }

    @Test
    void testExceptionFromReferenceManagerDuringDeletionIsCaughtAndLogged() throws Exception {
        doThrow( new RuntimeException( "boom" ) ).when( referenceManager ).pageRemoved( any( Page.class ) );

        final File f = tempDir.resolve( "BoomOnDelete.md" ).toFile();
        Files.writeString( f.toPath(), "content" );
        watcher.startupTask();
        assertTrue( f.delete(), "setup: file must be deletable" );

        // Must not propagate out of backgroundTask() despite the collaborator failure.
        pollBackgroundTaskUntil( () -> mockingDetails( referenceManager ).getInvocations().stream()
                .anyMatch( inv -> inv.getMethod().getName().equals( "pageRemoved" ) ) );

        verify( cachingManager ).remove( CachingManager.CACHE_PAGES, "BoomOnDelete" );
    }

    // ---- cleanupGuardEntries() ----

    @Test
    void testCleanupGuardEntriesRemovesStaleEntriesAfterConfiguredInterval() throws Exception {
        final Properties props = new Properties();
        props.setProperty( PageDirectoryWatcher.PROP_INTERNAL_SAVE_GUARD_MILLIS, "50" );
        props.setProperty( PageDirectoryWatcher.PROP_GUARD_CLEANUP_INTERVAL_MILLIS, "100" );
        final Engine fastCleanupEngine = MockEngineBuilder.engine()
                .with( ReferenceManager.class, referenceManager )
                .with( SearchManager.class, searchManager )
                .properties( props )
                .build();
        final PageDirectoryWatcher fastCleanupWatcher =
                new PageDirectoryWatcher( fastCleanupEngine, 1, fileProvider, cachingManager );

        fastCleanupWatcher.notifyInternalSave( "StaleEntry" );
        org.junit.jupiter.api.Assertions.assertEquals( 1, fastCleanupWatcher.guardMapSize(),
                "guard map should hold the freshly-added entry" );

        fastCleanupWatcher.startupTask();

        // Wait past both the 50ms guard TTL and the 100ms cleanup interval (lastGuardCleanup
        // was stamped at construction time), then let a single backgroundTask() call run the
        // cleanup sweep and physically remove the now-stale map entry.
        Thread.sleep( 150 );
        fastCleanupWatcher.backgroundTask();

        assertFalse( fastCleanupWatcher.isRecentInternalSave( "StaleEntry" ) );
        org.junit.jupiter.api.Assertions.assertEquals( 0, fastCleanupWatcher.guardMapSize(),
                "cleanupGuardEntries() should have physically removed the stale entry" );

        fastCleanupWatcher.shutdownTask();
    }

    // ---- shutdownTask() idempotence ----

    @Test
    void testShutdownTaskIsSafeWhenWatchServiceNeverStarted() {
        assertDoesNotThrow( () -> watcher.shutdownTask() );
    }

    @Test
    void testShutdownTaskClosesWatchServiceAfterStartup() throws Exception {
        watcher.startupTask();
        assertDoesNotThrow( () -> watcher.shutdownTask() );
    }
}

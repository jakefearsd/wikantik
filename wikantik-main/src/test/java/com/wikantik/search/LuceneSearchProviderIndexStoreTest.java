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
package com.wikantik.search;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the Lucene page index is written and read: one commit per drained batch, atomic page
 * replacement, retry of a page whose indexing failed, and one shared reader reused across searches
 * until a commit changes the index.
 *
 * <p>Measured in the 2026-10-22 profiling run (I4): every search opened a fresh directory reader,
 * and every queued page update opened two writers and committed twice — "rebuild indexes" over
 * 1,183 pages made 1,213 commits and ~48k fsyncs.</p>
 */
class LuceneSearchProviderIndexStoreTest {

    @TempDir
    File tempDir;

    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private AuthorizationManager authorizationManager;
    private AclManager aclManager;
    private Context wikiContext;
    private LuceneSearchProvider provider;
    private File luceneDir;

    /** Flipped by a test to make indexing a page throw, as an unreachable identity service does. */
    private final AtomicBoolean identityDown = new AtomicBoolean();
    /** Runs while a page document is being built, i.e. in the middle of a drain. */
    private Runnable whileIndexing = () -> { };

    @BeforeEach
    void setUp() throws Exception {
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );
        authorizationManager = mock( AuthorizationManager.class );
        aclManager = mock( AclManager.class );
        wikiContext = mock( Context.class );

        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.displayName( anyString() ) ).thenAnswer( inv -> {
            whileIndexing.run();
            if ( identityDown.get() ) {
                throw new IllegalStateException( "identity service unreachable" );
            }
            return inv.getArgument( 0 );
        } );
        provider = new LuceneSearchProvider( pageManager, attachmentManager ) {
            @Override
            PublicIdentity publicIdentity() {
                return identity;
            }
        };

        lenient().when( pageManager.getPageWithoutMetadata( anyString(), anyInt() ) )
            .thenAnswer( inv -> pageManager.getPage( inv.getArgument( 0 ), inv.getArgument( 1 ) ) );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        when( authorizationManager.checkStaticPermission( any( Session.class ), any( Permission.class ) ) ).thenReturn( true );
        when( aclManager.getPermissions( any( Page.class ) ) ).thenReturn( null );

        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( AuthorizationManager.class, authorizationManager )
                .with( AclManager.class, aclManager )
                .build();
        when( engine.getApplicationName() ).thenReturn( "test" );
        when( wikiContext.getWikiSession() ).thenReturn( mock( Session.class ) );
        when( wikiContext.getEngine() ).thenReturn( engine );

        luceneDir = new File( tempDir, "lucene" );
        assertTrue( luceneDir.mkdirs() );
        setField( "luceneDirectory", luceneDir.getAbsolutePath() );
        setField( "analyzer", new ClassicAnalyzer() );
        setField( "searchExecutor", Executors.newCachedThreadPool( r -> {
            final Thread t = new Thread( r );
            t.setDaemon( true );
            return t;
        } ) );
        setField( "engine", engine );
    }

    @AfterEach
    void closeIndex() {
        provider.close();
    }

    // ---- writes: one commit per drained batch ----

    @Test
    void drainingManyQueuedPagesCommitsOnce() throws Exception {
        provider.updateLuceneIndex( page( "SeedPage" ), "seed body" ); // a commit to measure from
        final int pages = 25;
        for ( int i = 0; i < pages; i++ ) {
            enqueue( page( "Page" + i ), "body text number " + i );
        }

        final long before = commitGeneration();
        final LuceneSearchProvider.DrainStats stats = provider.drainUpdateQueue();
        final long commits = commitGeneration() - before;

        assertEquals( pages, stats.indexed() );
        assertEquals( 1, commits, "one drain of " + pages + " pages must commit once, not per page" );
        assertEquals( pages + 1, provider.documentCount() );
    }

    @Test
    void indexingMissingPagesAndAttachmentsCommitsOnce() throws Exception {
        final Page seed = page( "SeedPage" );
        provider.updateLuceneIndex( seed, "seed body" );
        final Page missingA = page( "MissingA" );
        final Page missingB = page( "MissingB" );
        when( pageManager.getAllPages() ).thenReturn( List.of( seed, missingA, missingB ) );
        when( pageManager.getPageText( anyString(), anyInt() ) ).thenReturn( "missing page body" );
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "MissingA/notes.txt" );
        when( att.getFileName() ).thenReturn( "notes.txt" );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of( att ) );
        when( attachmentManager.getAttachmentInfo( "MissingA/notes.txt", -1 ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( att ) )
            .thenReturn( new ByteArrayInputStream( "attachment words".getBytes( StandardCharsets.UTF_8 ) ) );

        final long before = commitGeneration();
        assertEquals( 2, provider.indexMissingPages() );

        assertEquals( 1, commitGeneration() - before, "the sweep's pages and attachments land in one commit" );
        assertEquals( 4, provider.documentCount() );
    }

    // ---- writes: an update replaces the page atomically ----

    @Test
    void updatingAPageLeavesExactlyOneSearchableDocument() throws Exception {
        final Page alpha = searchablePage( "AlphaPage" );
        enqueue( alpha, "oldversionterm body" );
        provider.drainUpdateQueue();
        enqueue( alpha, "newversionterm body" );
        provider.drainUpdateQueue();

        assertEquals( 1, provider.documentCount(), "the update must replace the page's document, not add a second" );
        assertEquals( 1, provider.findPages( "newversionterm", wikiContext ).size() );
        assertTrue( provider.findPages( "oldversionterm", wikiContext ).isEmpty() );
    }

    @Test
    void aPageWhoseNewVersionCannotBeIndexedKeepsItsPreviousVersion() throws Exception {
        final Page alpha = searchablePage( "AlphaPage" );
        enqueue( alpha, "oldversionterm body" );
        provider.drainUpdateQueue();

        identityDown.set( true );
        enqueue( alpha, "newversionterm body" );
        final LuceneSearchProvider.DrainStats stats = provider.drainUpdateQueue();

        assertEquals( 1, stats.failed() );
        assertEquals( 1, provider.findPages( "oldversionterm", wikiContext ).size(),
            "a failed re-index must not delete the page's previous document" );
    }

    // ---- writes: a failed page is retried, boundedly ----

    @Test
    void aPageThatFailsToIndexIsRetriedOnTheNextDrain() throws Exception {
        final Page alpha = searchablePage( "AlphaPage" );
        identityDown.set( true );
        enqueue( alpha, "retriedterm body" );
        provider.drainUpdateQueue();
        assertEquals( 1, provider.getReindexQueueDepth(), "a page that failed to index must stay queued" );

        identityDown.set( false );
        final LuceneSearchProvider.DrainStats stats = provider.drainUpdateQueue();

        assertEquals( 1, stats.indexed() );
        assertEquals( 0, provider.getReindexQueueDepth() );
        assertEquals( 1, provider.findPages( "retriedterm", wikiContext ).size() );
    }

    @Test
    void aPageThatKeepsFailingIsEventuallyDropped() throws Exception {
        identityDown.set( true );
        enqueue( searchablePage( "AlphaPage" ), "body" );

        int drains = 0;
        while ( provider.getReindexQueueDepth() > 0 && drains < 10 ) {
            provider.drainUpdateQueue();
            drains++;
        }

        assertEquals( 0, provider.getReindexQueueDepth(), "a page that never indexes must not be retried forever" );
        assertTrue( drains > 1, "the page must be retried before it is dropped (drains=" + drains + ")" );
    }

    @Test
    void queueDepthCountsPagesUntilTheirBatchIsCommitted() throws Exception {
        final List< Integer > depthsSeenWhileIndexing = new ArrayList<>();
        whileIndexing = () -> depthsSeenWhileIndexing.add( provider.getReindexQueueDepth() );
        for ( int i = 0; i < 3; i++ ) {
            enqueue( searchablePage( "Page" + i ), "body " + i );
        }

        provider.drainUpdateQueue();

        assertEquals( List.of( 3, 3, 3 ), depthsSeenWhileIndexing,
            "the rebuild treats depth 0 as done, so a page must count until it is committed" );
        assertEquals( 0, provider.getReindexQueueDepth() );
    }

    // ---- reads: one shared reader ----

    @Test
    void aSearchWithNoIndexChangeReusesTheOpenReader() throws Exception {
        provider.updateLuceneIndex( searchablePage( "AlphaPage" ), "sharedterm body" );
        assertEquals( 1, provider.findPages( "sharedterm", wikiContext ).size() );

        // An open reader keeps its segment files readable after they are unlinked (POSIX); a search
        // that re-opens the index from the directory finds nothing there.
        for ( final File f : listFiles( luceneDir ) ) {
            assertTrue( f.delete(), "could not delete " + f );
        }

        assertEquals( 1, provider.findPages( "sharedterm", wikiContext ).size(),
            "the second search re-read the index directory instead of reusing the open reader" );
    }

    @Test
    void aSearchAfterACommittedUpdateSeesTheNewContent() throws Exception {
        final Page alpha = searchablePage( "AlphaPage" );
        provider.updateLuceneIndex( alpha, "firstterm body" );
        assertEquals( 1, provider.findPages( "firstterm", wikiContext ).size() );

        provider.updateLuceneIndex( alpha, "secondterm body" );
        assertEquals( 1, provider.findPages( "secondterm", wikiContext ).size() );
        assertTrue( provider.findPages( "firstterm", wikiContext ).isEmpty() );

        enqueue( searchablePage( "BetaPage" ), "drainedterm body" );
        provider.drainUpdateQueue();
        assertEquals( 1, provider.findPages( "drainedterm", wikiContext ).size() );

        provider.pageRemoved( alpha );
        assertTrue( provider.findPages( "secondterm", wikiContext ).isEmpty() );
    }

    // ---- lifecycle ----

    @Test
    void closeReleasesTheWriteLockAndEndsTheIndex() throws Exception {
        provider.updateLuceneIndex( searchablePage( "AlphaPage" ), "body" );

        provider.close();

        try ( Directory dir = new NIOFSDirectory( luceneDir.toPath() ) ) {
            assertDoesNotThrow( () -> new IndexWriter( dir, new IndexWriterConfig( new ClassicAnalyzer() ) ).close(),
                "close() must release write.lock so the next engine (or JVM) can open the index" );
        }
        assertThrows( ProviderException.class, () -> provider.findPages( "body", wikiContext ),
            "a closed index must refuse searches rather than reopen a writer nobody will close" );
    }

    // ---- helpers ----

    private Page page( final String name ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getAuthor() ).thenReturn( "someone" );
        return page;
    }

    private Page searchablePage( final String name ) throws Exception {
        final Page page = page( name );
        when( pageManager.getPage( name, PageProvider.LATEST_VERSION ) ).thenReturn( page );
        when( pageManager.wikiPageExists( name, PageProvider.LATEST_VERSION ) ).thenReturn( true );
        return page;
    }

    private void enqueue( final Page page, final String text ) {
        provider.updates.add( new Object[]{ page, text } );
    }

    private long commitGeneration() throws IOException {
        try ( Directory dir = new NIOFSDirectory( luceneDir.toPath() ) ) {
            return SegmentInfos.readLatestCommit( dir ).getGeneration();
        }
    }

    private static List< File > listFiles( final File dir ) {
        final File[] files = dir.listFiles();
        return files == null ? List.of() : List.of( files );
    }

    private void setField( final String fieldName, final Object value ) {
        try {
            final java.lang.reflect.Field field = LuceneSearchProvider.class.getDeclaredField( fieldName );
            field.setAccessible( true );
            field.set( provider, value );
        } catch ( final ReflectiveOperationException e ) {
            throw new IllegalStateException( "Failed to set field " + fieldName, e );
        }
    }
}

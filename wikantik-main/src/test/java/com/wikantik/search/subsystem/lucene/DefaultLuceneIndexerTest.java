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
package com.wikantik.search.subsystem.lucene;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.providers.WikiProvider;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fault-path coverage for {@link DefaultLuceneIndexer}: directory-not-ready states, per-item
 * failures that must not abort a batch, and the outer catch blocks around each Lucene I/O
 * operation. The happy paths are already exercised elsewhere (search provider integration
 * tests); this class targets the branches those never take.
 */
class DefaultLuceneIndexerTest {

    @TempDir
    Path tmp;

    private LuceneIndexLifecycle lifecycle;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private SystemPageRegistry systemPageRegistry;
    private List<Object[]> updates;

    @BeforeEach
    void setUp() throws Exception {
        lifecycle = mock( LuceneIndexLifecycle.class );
        final Analyzer analyzer = new StandardAnalyzer();
        when( lifecycle.getAnalyzer() ).thenReturn( analyzer );
        // Default: a real, working IndexWriter over whatever Directory is handed in.
        // Individual tests override this to inject a specific failure — re-stubbing a
        // mock method with any() as the matcher makes Mockito invoke the CURRENT answer
        // once more (with a null placeholder argument) while recording the new `when()`,
        // so this answer must tolerate a null Directory rather than NPE and abort the
        // re-stub before it attaches.
        when( lifecycle.getIndexWriter( any() ) ).thenAnswer( inv -> {
            final Directory d = inv.getArgument( 0 );
            return d == null ? null : new IndexWriter( d, new IndexWriterConfig( analyzer ) );
        } );

        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );
        // Every luceneIndexPage() call reads this; an unstubbed List-returning mock method
        // returns null, which would NPE before any test-specific behaviour got exercised.
        when( attachmentManager.listAttachments( any() ) ).thenReturn( List.of() );
        systemPageRegistry = mock( SystemPageRegistry.class );
        updates = new ArrayList<>();
    }

    private DefaultLuceneIndexer newIndexer() {
        return new DefaultLuceneIndexer(
            tmp::toString, lifecycle, pageManager, attachmentManager, systemPageRegistry, updates );
    }

    private DefaultLuceneIndexer newIndexer( final java.util.function.Supplier<String> dirSupplier ) {
        return new DefaultLuceneIndexer(
            dirSupplier, lifecycle, pageManager, attachmentManager, systemPageRegistry, updates );
    }

    private static Page mockPage( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    // ---- pageRemoved ----

    @Test
    void pageRemoved_logsAndSwallowsExceptionOnWriterOpenFailure() throws Exception {
        when( lifecycle.getIndexWriter( any() ) ).thenThrow( new IOException( "writer boom" ) );
        final DefaultLuceneIndexer indexer = newIndexer();
        assertDoesNotThrow( () -> indexer.pageRemoved( mockPage( "AnyPage" ) ),
            "a writer-open failure while removing a page must be logged, not thrown" );
    }

    // ---- clearIndex ----

    @Test
    void clearIndex_warnsAndReturnsWhenDirectoryNotYetInitialized() {
        final DefaultLuceneIndexer indexer = newIndexer( () -> null );
        assertDoesNotThrow( indexer::clearIndex,
            "clearIndex before the directory is known must be a no-op, not an exception" );
    }

    @Test
    void clearIndex_returnsSilentlyWhenDirectoryDoesNotExist() {
        final DefaultLuceneIndexer indexer = newIndexer( () -> tmp.resolve( "does-not-exist" ).toString() );
        assertDoesNotThrow( indexer::clearIndex );
    }

    @Test
    void clearIndex_throwsIllegalStateExceptionWhenWriterFails() throws Exception {
        when( lifecycle.getIndexWriter( any() ) ).thenThrow( new IOException( "commit boom" ) );
        final DefaultLuceneIndexer indexer = newIndexer(); // tmp already exists
        assertThrows( IllegalStateException.class, indexer::clearIndex );
    }

    // ---- documentCount ----

    @Test
    void documentCount_returnsZeroWhenIndexUnreadable() throws Exception {
        // A non-empty directory that is NOT a valid Lucene index (no segments file).
        Files.writeString( tmp.resolve( "junk.txt" ), "not a lucene index" );
        final DefaultLuceneIndexer indexer = newIndexer();
        assertEquals( 0, indexer.documentCount() );
    }

    // ---- doFullLuceneReindex ----

    @Test
    void doFullLuceneReindex_throwsIOExceptionWhenDirectoryCannotBeListed() throws Exception {
        final Path blockerFile = Files.createFile( tmp.resolve( "blocker" ) );
        final String unreachableDir = blockerFile.resolve( "sub" ).toString(); // parent is a FILE
        final DefaultLuceneIndexer indexer = newIndexer( () -> unreachableDir );
        assertThrows( IOException.class, indexer::doFullLuceneReindex );
    }

    @Test
    void doFullLuceneReindex_logsButDoesNotThrowWhenWriterOpenFails() throws Exception {
        when( lifecycle.getIndexWriter( any() ) ).thenThrow( new IOException( "writer boom" ) );
        when( pageManager.getAllPages() ).thenReturn( List.of() );
        final DefaultLuceneIndexer indexer = newIndexer(); // fresh, empty tmp dir
        assertDoesNotThrow( indexer::doFullLuceneReindex,
            "a writer-open failure during the full reindex must be logged, not thrown" );
    }

    @Test
    void doFullLuceneReindex_wrapsProviderExceptionFromPageEnumeration() throws Exception {
        when( pageManager.getAllPages() ).thenThrow( new ProviderException( "provider down" ) );
        final DefaultLuceneIndexer indexer = newIndexer();
        assertThrows( IllegalArgumentException.class, indexer::doFullLuceneReindex );
    }

    @Test
    void doFullLuceneReindex_logsButDoesNotThrowOnUnexpectedException() throws Exception {
        when( pageManager.getAllPages() ).thenReturn( List.of() );
        when( attachmentManager.getAllAttachments() ).thenThrow( new RuntimeException( "totally unexpected" ) );
        final DefaultLuceneIndexer indexer = newIndexer();
        assertDoesNotThrow( indexer::doFullLuceneReindex,
            "an unexpected RuntimeException must be caught by the generic handler, not propagate" );
    }

    @Test
    void doFullLuceneReindex_skipsSystemPagesAndContinuesPastPerItemWriterFailures() throws Exception {
        final Page normalPage = mockPage( "NormalPage" );
        final Page systemPage = mockPage( "SystemPage" );
        when( pageManager.getAllPages() ).thenReturn( List.of( normalPage, systemPage ) );
        when( systemPageRegistry.isSystemPage( "SystemPage" ) ).thenReturn( true );
        when( systemPageRegistry.isSystemPage( "NormalPage" ) ).thenReturn( false );
        when( pageManager.getPageText( eq( "NormalPage" ), anyInt() ) ).thenReturn( "normal page body" );

        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Attach1" );
        when( att.getFileName() ).thenReturn( "data.bin" ); // not a searchable suffix
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of( att ) );
        when( attachmentManager.getAttachmentInfo( "Attach1", WikiProvider.LATEST_VERSION ) ).thenReturn( att );

        // Every addDocument (page AND attachment) fails — must not abort the batch.
        final IndexWriter faultyWriter = mock( IndexWriter.class );
        when( faultyWriter.addDocument( any() ) ).thenThrow( new IOException( "add failed" ) );
        when( lifecycle.getIndexWriter( any() ) ).thenReturn( faultyWriter );

        final DefaultLuceneIndexer indexer = newIndexer();
        assertDoesNotThrow( indexer::doFullLuceneReindex,
            "per-item write failures for both a page and an attachment must be logged, not thrown" );
    }

    // ---- indexMissingPages / indexMissingPagesToDisk / indexMissingAttachmentsToDisk ----

    @Test
    void indexMissingPages_logsIOExceptionFromWriterOpenFailure() throws Exception {
        Files.writeString( tmp.resolve( "junk.txt" ), "not a lucene index" ); // non-empty, not readable
        final Page missing1 = mockPage( "Missing1" );
        when( pageManager.getAllPages() ).thenReturn( List.of( missing1 ) );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of() );
        when( lifecycle.getIndexWriter( any() ) ).thenThrow( new IOException( "writer boom" ) );

        final DefaultLuceneIndexer indexer = newIndexer();
        assertEquals( 0, indexer.indexMissingPages(),
            "a writer-open failure must be logged and short-circuit to zero indexed" );
    }

    @Test
    void indexMissingPagesToDisk_continuesPastPerPageWriterFailures() throws Exception {
        Files.writeString( tmp.resolve( "junk.txt" ), "not a lucene index" );
        final Page p1 = mockPage( "Missing1" );
        final Page p2 = mockPage( "Missing2" );
        when( pageManager.getAllPages() ).thenReturn( List.of( p1, p2 ) );
        when( pageManager.getPageText( any(), anyInt() ) ).thenReturn( "body" );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of() );

        final IndexWriter faultyWriter = mock( IndexWriter.class );
        when( faultyWriter.addDocument( any() ) ).thenThrow( new IOException( "add failed" ) );
        when( lifecycle.getIndexWriter( any() ) ).thenReturn( faultyWriter );

        final DefaultLuceneIndexer indexer = newIndexer();
        assertEquals( 0, indexer.indexMissingPages(),
            "both pages fail to write, so nothing was actually indexed" );
    }

    @Test
    void indexMissingAttachmentsToDisk_continuesPastPerAttachmentWriterFailure() throws Exception {
        Files.writeString( tmp.resolve( "junk.txt" ), "not a lucene index" );
        when( pageManager.getAllPages() ).thenReturn( List.of() ); // no missing pages

        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Attach1" );
        when( att.getFileName() ).thenReturn( "data.bin" );
        when( attachmentManager.getAllAttachments() ).thenReturn( List.of( att ) );
        when( attachmentManager.getAttachmentInfo( "Attach1", WikiProvider.LATEST_VERSION ) ).thenReturn( att );

        final IndexWriter faultyWriter = mock( IndexWriter.class );
        when( faultyWriter.addDocument( any() ) ).thenThrow( new IOException( "add failed" ) );
        when( lifecycle.getIndexWriter( any() ) ).thenReturn( faultyWriter );

        final DefaultLuceneIndexer indexer = newIndexer();
        assertDoesNotThrow( indexer::indexMissingPages,
            "a per-attachment write failure must be logged, not thrown" );
    }

    // ---- updateLuceneIndex ----

    @Test
    void updateLuceneIndex_returnsFalseWhenWriterOpenFails() throws Exception {
        when( lifecycle.getIndexWriter( any() ) ).thenThrow( new IOException( "writer boom" ) );
        final DefaultLuceneIndexer indexer = newIndexer();
        assertFalse( indexer.updateLuceneIndex( mockPage( "AnyPage" ), "text" ) );
    }

    // ---- luceneIndexPage ----

    @Test
    void luceneIndexPage_logsDebugWhenFrontmatterHandlingThrows() throws Exception {
        // "tags: [foo, ~]" parses to a list containing a null element; Object::toString
        // on that null throws NPE from inside the tags-field-building code, exercising
        // the surrounding catch(Exception) rather than a YAML syntax error (which
        // FrontmatterParser.parse() already tolerates internally and never throws).
        final String text = "---\ntags: [foo, ~]\n---\nBody content.";
        final Page page = mockPage( "PageWithBadTags" );

        try ( Directory dir = LuceneDirectoryFactory.open( tmp, false );
              IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( new StandardAnalyzer() ) ) ) {
            final DefaultLuceneIndexer indexer = newIndexer();
            final Document doc = assertDoesNotThrow( () -> indexer.luceneIndexPage( page, text, writer ) );
            assertEquals( null, doc.get( DefaultLuceneIndexer.LUCENE_PAGE_CLUSTER ),
                "frontmatter fields must not have been populated once parsing the tags list failed" );
        }
    }

    // ---- drainUpdateQueue ----

    @Test
    void drainUpdateQueue_warnsAtThresholdAndLogsProgressPeriodically() {
        // All entries are system pages, so the drain loop is trivially fast (no real
        // Lucene I/O per item) while still exercising the threshold-warning and the
        // every-100 progress log across a realistic queue depth.
        when( systemPageRegistry.isSystemPage( "BulkPage" ) ).thenReturn( true );
        final Page bulkPage = mockPage( "BulkPage" );
        for ( int i = 0; i < DefaultLuceneIndexer.QUEUE_DEPTH_WARN_THRESHOLD; i++ ) {
            updates.add( new Object[]{ bulkPage, "text" } );
        }

        final DefaultLuceneIndexer indexer = newIndexer();
        final LuceneIndexer.DrainStats stats = indexer.drainUpdateQueue();

        assertEquals( DefaultLuceneIndexer.QUEUE_DEPTH_WARN_THRESHOLD, stats.totalQueued() );
        assertEquals( DefaultLuceneIndexer.QUEUE_DEPTH_WARN_THRESHOLD, stats.skipped() );
        assertEquals( 0, stats.indexed() );
        assertEquals( 0, stats.failed() );
    }
}

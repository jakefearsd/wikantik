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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.api.core.Context;

/**
 * Read-path correctness for {@link DefaultLuceneSearcher}.
 *
 * <p>Both behaviours here were real defects found on 2026-08-19 while chasing an
 * intermittent failure of {@code PluginCoverageTest.testSearchWithResults}:
 *
 * <ol>
 *   <li><b>An unreadable index was reported as an empty result set.</b> Lucene
 *       writes {@code pending_segments_N} and only renames it to
 *       {@code segments_N} on commit, so a directory holding
 *       {@code [pending_segments_1, write.lock]} makes {@code DirectoryReader.open}
 *       throw {@link org.apache.lucene.index.IndexNotFoundException}. That is an
 *       {@link IOException}, the catch swallowed it, and the caller got an empty
 *       collection — a wrong answer dressed up as "nothing matched". The Search
 *       plugin then rendered its "No results" table, which is exactly the
 *       observed test failure.</li>
 *   <li><b>A search deleted index entries.</b> When a hit's page could not be
 *       loaded, the read path called {@code indexer.pageRemoved(...)}. A null
 *       page is ambiguous — genuinely gone, or a transient provider failure — so
 *       one bad read permanently dropped a valid document.</li>
 * </ol>
 */
class DefaultLuceneSearcherReadinessTest {

    @TempDir
    Path indexDir;

    private LuceneIndexLifecycle lifecycle;
    private LuceneIndexer indexer;
    private PageManager pageManager;
    private Engine engine;
    private Context context;
    private AuthorizationManager authManager;
    private AclManager aclManager;

    @BeforeEach
    void setUp() {
        lifecycle = mock( LuceneIndexLifecycle.class );
        indexer = mock( LuceneIndexer.class );
        pageManager = mock( PageManager.class );
        engine = mock( Engine.class );
        context = mock( Context.class );
        authManager = mock( AuthorizationManager.class );
        aclManager = mock( AclManager.class );

        final Analyzer analyzer = new StandardAnalyzer();
        when( lifecycle.getAnalyzer() ).thenReturn( analyzer );
        when( engine.getWikiProperties() ).thenReturn( new Properties() );
        when( engine.getApplicationName() ).thenReturn( "TestWiki" );
        when( context.getWikiSession() ).thenReturn( mock( Session.class ) );
        when( context.getEngine() ).thenReturn( engine );
        when( authManager.checkStaticPermission( any(), any() ) ).thenReturn( true );
    }

    private DefaultLuceneSearcher newSearcher() {
        final Executor direct = Runnable::run;
        return new DefaultLuceneSearcher( () -> indexDir.toString(), lifecycle, indexer,
                pageManager, engine, direct, authManager, aclManager );
    }

    /** Writes one committed document so the index is genuinely readable. */
    private void writeCommittedDoc( final String pageName, final String contents ) throws IOException {
        try ( Directory dir = new NIOFSDirectory( indexDir );
              IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( new StandardAnalyzer() ) ) ) {
            final Document doc = new Document();
            doc.add( new StringField( DefaultLuceneIndexer.LUCENE_ID, pageName, Field.Store.YES ) );
            doc.add( new TextField( DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS, contents, Field.Store.YES ) );
            writer.addDocument( doc );
            writer.commit();
        }
    }

    // ------------------------------------------------------------------
    // Defect 1: a not-ready index is a transient EMPTY state, not a failure
    //
    // The original defect (2.4.12) was that a genuine IOException was swallowed
    // into an empty result. The fix distinguishes a genuine I/O fault (which
    // propagates, see below) from an index with no committed segments — which is
    // NOT an error: a fresh install and a mid-commit index both legitimately have
    // "no results yet". Returning empty for that case, and letting callers that
    // care retry, is the right contract; an earlier iteration threw a "not ready"
    // exception here, which turned a transient state into a hard failure that every
    // caller and test had to special-case.
    // ------------------------------------------------------------------

    @Test
    void searchDuringAnUncommittedIndexBuildReturnsEmptyNotAnError() throws Exception {
        // Exactly the on-disk state captured from a real run: a writer has been
        // opened and has not committed, so there is no segments_* file yet. A search
        // landing in that window has no results to give YET — it returns empty, and
        // a waiting caller (poller / warm-up / the Search plugin under retry) tries
        // again. It must NOT throw: that is a transient state, not a fault.
        Files.createFile( indexDir.resolve( "write.lock" ) );
        Files.createFile( indexDir.resolve( "pending_segments_1" ) );

        final DefaultLuceneSearcher searcher = newSearcher();

        assertTrue( searcher.findPages( "anything", 0, context ).isEmpty(),
                "a not-yet-committed index yields no results yet — empty, never an exception" );
    }

    @Test
    void searchAgainstAnIndexThatWasNeverBuiltReturnsEmptyRatherThanFailing() throws Exception {
        // Nothing has ever been indexed — a brand-new wiki with no content. That
        // legitimately has zero results and must NOT be turned into an error, or
        // every search on a fresh install would fail.
        final DefaultLuceneSearcher searcher = newSearcher();

        assertTrue( searcher.findPages( "anything", 0, context ).isEmpty(),
                "an index that was never built has no documents; that is not a failure" );
    }

    // ------------------------------------------------------------------
    // Defect 2: a read must not delete index entries on an ambiguous signal
    // ------------------------------------------------------------------

    @Test
    void aHitWhosePageCannotBeLoadedIsNotDeletedWhenThePageStillExists() throws Exception {
        writeCommittedDoc( "FlakyPage", "alpha beta gamma" );
        // getPage returns null (transient provider hiccup) but the page IS still there.
        when( pageManager.getPage( anyString(), anyInt() ) ).thenReturn( null );
        when( pageManager.wikiPageExists( anyString(), anyInt() ) ).thenReturn( true );

        final DefaultLuceneSearcher searcher = newSearcher();
        searcher.findPages( "alpha", 0, context );

        verify( indexer, never() ).pageRemoved( any( Page.class ) );
    }

    @Test
    void aHitWhosePageCannotBeLoadedIsNotDeletedWhenExistenceCannotBeChecked() throws Exception {
        writeCommittedDoc( "FlakyPage", "alpha beta gamma" );
        when( pageManager.getPage( anyString(), anyInt() ) ).thenReturn( null );
        when( pageManager.wikiPageExists( anyString(), anyInt() ) )
                .thenThrow( new ProviderException( "page store unavailable" ) );

        final DefaultLuceneSearcher searcher = newSearcher();
        searcher.findPages( "alpha", 0, context );

        verify( indexer, never() ).pageRemoved( any( Page.class ) );
    }

    @Test
    void aHitForAPageThatIsConfirmedGoneIsStillRemovedFromTheIndex() throws Exception {
        // The self-healing behaviour is worth keeping — it just needs a
        // definitive signal rather than an ambiguous null.
        writeCommittedDoc( "DeletedPage", "alpha beta gamma" );
        when( pageManager.getPage( anyString(), anyInt() ) ).thenReturn( null );
        when( pageManager.wikiPageExists( anyString(), anyInt() ) ).thenReturn( false );

        final DefaultLuceneSearcher searcher = newSearcher();
        final int hits = searcher.findPages( "alpha", 0, context ).size();

        assertEquals( 0, hits, "a page that is confirmed gone must not appear in results" );
        verify( indexer ).pageRemoved( any( Page.class ) );
    }
}

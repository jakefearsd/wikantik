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

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Unit tests for the {@link LuceneSearchProvider#documentCount()},
 * {@link LuceneSearchProvider#lastUpdateInstant()}, and
 * {@link LuceneSearchProvider#clearIndex()} methods added for the admin
 * rebuild orchestrator (Task 11). These tests use the package-private test
 * constructor plus a {@link TempDir} so we can drive real Lucene operations
 * against a throwaway index directory without standing up a full TestEngine.
 */
class LuceneSearchProviderIndexOpsTest {

    /**
     * Reflectively sets the private {@code luceneDirectory} field so the
     * test can exercise the real Lucene code paths that depend on it.
     */
    private static void wireDirectory( final LuceneSearchProvider provider, final Path dir ) throws Exception {
        final java.lang.reflect.Field f = LuceneSearchProvider.class.getDeclaredField( "luceneDirectory" );
        f.setAccessible( true );
        f.set( provider, dir.toString() );
    }

    private static LuceneSearchProvider newProvider( final Path dir ) throws Exception {
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                Mockito.mock( PageManager.class ),
                Mockito.mock( AttachmentManager.class ),
                null, null );
        wireDirectory( provider, dir );
        return provider;
    }

    /** Writes a single trivial document directly via a fresh IndexWriter. */
    private static void writeOneDoc( final Path dir, final String id ) throws IOException {
        try ( final Directory luceneDir = new NIOFSDirectory( dir );
              final IndexWriter w = new IndexWriter( luceneDir,
                      new IndexWriterConfig( new ClassicAnalyzer() ) ) ) {
            final Document doc = new Document();
            doc.add( new Field( "id", id, StringField.TYPE_STORED ) );
            doc.add( new Field( "contents", "hello world " + id, TextField.TYPE_STORED ) );
            w.addDocument( doc );
            w.commit();
        }
    }

    @Test
    void documentCountIsZeroForEmptyDirectory( @TempDir final Path tmp ) throws Exception {
        final LuceneSearchProvider provider = newProvider( tmp );
        Assertions.assertEquals( 0, provider.documentCount(),
                "Empty directory must report zero documents, not throw" );
    }

    @Test
    void documentCountReflectsIndexedDocs( @TempDir final Path tmp ) throws Exception {
        writeOneDoc( tmp, "Page1" );
        writeOneDoc( tmp, "Page2" );
        final LuceneSearchProvider provider = newProvider( tmp );
        Assertions.assertEquals( 2, provider.documentCount() );
    }

    @Test
    void clearIndexDropsAllDocuments( @TempDir final Path tmp ) throws Exception {
        writeOneDoc( tmp, "Page1" );
        writeOneDoc( tmp, "Page2" );
        writeOneDoc( tmp, "Page3" );
        final LuceneSearchProvider provider = newProvider( tmp );
        Assertions.assertEquals( 3, provider.documentCount(),
                "Precondition: index should contain 3 documents before clearIndex" );

        provider.clearIndex();

        Assertions.assertEquals( 0, provider.documentCount(),
                "After clearIndex, documentCount must be 0" );
        Assertions.assertTrue( tmp.toFile().exists(),
                "clearIndex must not delete the Lucene directory itself" );
    }

    @Test
    void clearIndexAdvancesLastUpdateInstant( @TempDir final Path tmp ) throws Exception {
        final LuceneSearchProvider provider = newProvider( tmp );
        final Instant before = provider.lastUpdateInstant();
        Assertions.assertEquals( Instant.EPOCH, before,
                "lastUpdateInstant must start at EPOCH before any update" );

        provider.clearIndex();

        Assertions.assertTrue( provider.lastUpdateInstant().isAfter( before ),
                "clearIndex must advance lastUpdateInstant past its EPOCH default" );
    }

    @Test
    void lastUpdateInstantAdvancesAfterReindexDrain( @TempDir final Path tmp ) throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                pm, Mockito.mock( AttachmentManager.class ), null, null );
        wireDirectory( provider, tmp );
        // Install the ClassicAnalyzer directly — normally initialize() does this,
        // but we're skipping the full lifecycle for the unit test.
        final java.lang.reflect.Field analyzerField =
                LuceneSearchProvider.class.getDeclaredField( "analyzer" );
        analyzerField.setAccessible( true );
        analyzerField.set( provider, new ClassicAnalyzer() );

        final Page page = Mockito.mock( Page.class );
        Mockito.when( page.getName() ).thenReturn( "Sample" );
        Mockito.when( pm.getPureText( page ) ).thenReturn( "some body text" );

        final Instant before = provider.lastUpdateInstant();
        provider.reindexPage( page );
        final LuceneSearchProvider.DrainStats stats = provider.drainUpdateQueue();

        Assertions.assertEquals( 1, stats.indexed(),
                "Precondition: a single page reindex should end up indexed" );
        Assertions.assertTrue( provider.lastUpdateInstant().isAfter( before ),
                "Successful drain must advance lastUpdateInstant" );
    }
}

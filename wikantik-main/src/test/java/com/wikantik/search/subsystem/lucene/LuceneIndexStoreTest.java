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

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared writer/reader holder behind the page index: reuse, refresh, recovery and shutdown. */
class LuceneIndexStoreTest {

    @TempDir
    Path tmp;

    private final AtomicReference< String > path = new AtomicReference<>();
    private final LuceneIndexLifecycle lifecycle = new DefaultLuceneIndexLifecycle( new StandardAnalyzer() );
    private LuceneIndexStore store;

    @AfterEach
    void closeStore() {
        if ( store != null ) {
            store.close();
        }
    }

    private LuceneIndexStore storeAt( final Path dir ) {
        path.set( dir.toString() );
        store = new LuceneIndexStore( path::get, lifecycle, false );
        return store;
    }

    private static void addDoc( final IndexWriter writer, final String id ) throws IOException {
        final Document doc = new Document();
        doc.add( new Field( DefaultLuceneIndexer.LUCENE_ID, id, StringField.TYPE_STORED ) );
        writer.addDocument( doc );
    }

    private int liveDocs() throws IOException {
        try ( LuceneIndexStore.ReaderLease lease = store.acquire() ) {
            return lease.reader().numDocs();
        }
    }

    @Test
    void readingBeforeTheFirstCommitReportsNoIndexYet() {
        storeAt( tmp );
        assertThrows( IndexNotFoundException.class, store::acquire );
    }

    @Test
    void theReaderIsReusedUntilACommitAndThenReplaced() throws Exception {
        storeAt( tmp );
        addDoc( store.writer(), "One" );
        store.commit();

        final DirectoryReader first;
        try ( LuceneIndexStore.ReaderLease a = store.acquire(); LuceneIndexStore.ReaderLease b = store.acquire() ) {
            first = a.reader();
            assertSame( first, b.reader(), "no commit in between, so both leases share one reader" );
        }

        addDoc( store.writer(), "Two" );
        assertEquals( 1, liveDocs(), "an uncommitted write is not visible" );
        store.commit();

        try ( LuceneIndexStore.ReaderLease c = store.acquire() ) {
            assertNotSame( first, c.reader() );
            assertEquals( 2, c.reader().numDocs() );
        }
    }

    @Test
    void theWriterIsLongLivedAndReopenedAfterLuceneClosesIt() throws Exception {
        storeAt( tmp );
        final IndexWriter writer = store.writer();
        assertSame( writer, store.writer() );

        writer.close(); // as Lucene does itself after a tragic failure
        final IndexWriter reopened = store.writer();

        assertNotSame( writer, reopened );
        assertTrue( reopened.isOpen() );
    }

    @Test
    void aChangedDirectoryPathClosesTheOldIndexAndOpensTheNewOne() throws Exception {
        final Path first = tmp.resolve( "first" );
        final Path second = tmp.resolve( "second" );
        storeAt( first );
        addDoc( store.writer(), "InFirst" );
        store.commit();
        assertEquals( 1, liveDocs() );

        path.set( second.toString() );
        addDoc( store.writer(), "InSecond" );
        addDoc( store.writer(), "AlsoInSecond" );
        store.commit();

        assertEquals( 2, liveDocs() );
    }

    @Test
    void aStoreWithoutALifecycleCanReadButNotWrite() throws Exception {
        storeAt( tmp );
        addDoc( store.writer(), "One" );
        store.commit();
        store.close();

        store = new LuceneIndexStore( path::get, null, false );
        assertEquals( 1, liveDocs() );
        assertThrows( IOException.class, store::writer );
    }

    @Test
    void commitWithoutAWriterIsANoOpAndCloseIsIdempotent() throws Exception {
        storeAt( tmp );
        store.commit();
        store.close();
        store.close();
        assertThrows( IOException.class, store::writer, "a closed store must not reopen a writer" );
        assertThrows( IOException.class, store::acquire );
    }

    @Test
    void anUnconfiguredDirectoryIsReportedNotOpened() {
        store = new LuceneIndexStore( () -> null, lifecycle, false );
        assertThrows( IOException.class, store::writer );
        assertNull( store.path() );
    }
}

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.ReaderManager;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The page index's one {@link IndexWriter} and its shared, reference-counted reader.
 *
 * <p>Opening a {@link DirectoryReader} per search re-read {@code segments_N} and re-opened every
 * segment file on each query, and a writer per page update committed once or twice per page. Here the
 * writer stays open until {@link #close()} (one per JVM: it holds {@code write.lock}), callers commit
 * once per batch through {@link #commit()}, and each commit refreshes the {@link ReaderManager} so the
 * next {@link #acquire()} sees it. Readers are only refreshed by commits made through this store, so
 * nothing else may write to the directory while the store is open.</p>
 *
 * <p>The directory path is read from a supplier on every call (test fixtures re-point it by
 * reflection); a changed path closes everything opened on the old one.</p>
 */
public final class LuceneIndexStore implements Closeable {

    private static final Logger LOG = LogManager.getLogger( LuceneIndexStore.class );

    private final Supplier< String > directorySupplier;
    private final LuceneIndexLifecycle lifecycle;
    private final boolean useMMap;

    // Guarded by this; volatile where the lock-free acquire() path reads them.
    private volatile String openPath;
    private Directory directory;
    private IndexWriter writer;
    private volatile ReaderManager readers;
    private boolean closed;

    /** Set when a refresh after a commit failed, so the next {@link #acquire()} retries it. */
    private volatile boolean refreshPending;

    /**
     * @param directorySupplier returns the index directory path; may return {@code null} before initialisation
     * @param lifecycle         opens the writer; may be {@code null} for a store that is only ever read
     * @param useMMap           {@code true} for {@link org.apache.lucene.store.MMapDirectory}, else NIO
     */
    public LuceneIndexStore( final Supplier< String > directorySupplier,
                             final LuceneIndexLifecycle lifecycle,
                             final boolean useMMap ) {
        this.directorySupplier = directorySupplier;
        this.lifecycle = lifecycle;
        this.useMMap = useMMap;
    }

    /** @return the configured index directory path, or {@code null} before initialisation */
    public String path() {
        return directorySupplier.get();
    }

    /**
     * Returns the shared writer, opening it on first use and again if Lucene closed it after a
     * tragic failure. Changes made through it become durable and searchable at {@link #commit()}.
     *
     * @return the open writer
     * @throws IOException if the directory or writer cannot be opened (e.g. another process holds the lock)
     */
    public synchronized IndexWriter writer() throws IOException {
        openDirectory();
        if ( writer == null || !writer.isOpen() ) {
            if ( writer != null ) {
                LOG.warn( "Lucene index writer at {} was closed ({}); opening a new one",
                          openPath, writer.getTragicException() );
            }
            if ( lifecycle == null ) {
                throw new IOException( "Lucene index store at " + openPath + " has no lifecycle to open a writer with" );
            }
            writer = lifecycle.getIndexWriter( directory );
        }
        return writer;
    }

    /**
     * Commits everything written since the last commit, then refreshes the shared reader. A no-op when
     * no writer has been opened.
     *
     * @throws IOException if the commit fails
     */
    public synchronized void commit() throws IOException {
        if ( writer == null ) {
            return;
        }
        writer.commit();
        refreshReaders();
    }

    /**
     * Borrows the shared reader; close the lease to return it. The reader is opened on first use and
     * replaced only after a commit, so searches with no index change in between reuse it.
     *
     * @return a lease on the current reader
     * @throws org.apache.lucene.index.IndexNotFoundException if nothing has been committed yet
     * @throws IOException if the index cannot be read, or the store is closed
     */
    public ReaderLease acquire() throws IOException {
        ReaderManager manager = readers;
        if ( manager == null || !Objects.equals( directorySupplier.get(), openPath ) ) {
            manager = openReaders();
        }
        if ( refreshPending ) {
            retryRefresh( manager );
        }
        try {
            return new ReaderLease( manager, manager.acquire() );
        } catch ( final AlreadyClosedException e ) {
            // Closed by a concurrent close() or a re-pointed directory; take whatever is current now.
            LOG.debug( "Lucene reader manager closed under an acquire; reopening: {}", e.getMessage() );
            manager = openReaders();
            return new ReaderLease( manager, manager.acquire() );
        }
    }

    /** Closes the reader, the writer (committing anything uncommitted) and the directory; the store stays closed. */
    @Override
    public synchronized void close() {
        closed = true;
        closeResources();
    }

    private synchronized ReaderManager openReaders() throws IOException {
        openDirectory();
        if ( readers == null ) {
            readers = new ReaderManager( directory ); // IndexNotFoundException until the first commit
        }
        return readers;
    }

    /** Opens the directory for the configured path, closing what was open on a previous path. Caller holds this. */
    private void openDirectory() throws IOException {
        if ( closed ) {
            throw new IOException( "Lucene index store at " + openPath + " is closed" );
        }
        final String path = directorySupplier.get();
        if ( path == null ) {
            throw new IOException( "Lucene index directory is not configured yet" );
        }
        if ( !path.equals( openPath ) ) {
            closeResources();
            directory = LuceneDirectoryFactory.open( new File( path ).toPath(), useMMap );
            openPath = path;
        }
    }

    private void refreshReaders() {
        final ReaderManager manager = readers;
        if ( manager == null ) {
            return; // nothing open yet; the first acquire() opens the latest commit
        }
        refreshPending = true;
        try {
            manager.maybeRefreshBlocking();
            refreshPending = false;
        } catch ( final IOException e ) {
            LOG.warn( "Could not refresh the Lucene reader at {} after a commit; the next search retries: {}",
                      openPath, e.getMessage(), e );
        }
    }

    private void retryRefresh( final ReaderManager manager ) {
        try {
            if ( manager.maybeRefresh() ) {
                refreshPending = false;
            }
        } catch ( final IOException e ) {
            LOG.warn( "Lucene reader at {} is still stale; refresh failed again: {}", openPath, e.getMessage(), e );
        }
    }

    /** Caller holds this. */
    private void closeResources() {
        final ReaderManager manager = readers;
        readers = null;
        closeQuietly( manager, "reader" );
        closeQuietly( writer, "writer" );
        writer = null;
        closeQuietly( directory, "directory" );
        directory = null;
        openPath = null;
    }

    private void closeQuietly( final Closeable resource, final String what ) {
        if ( resource == null ) {
            return;
        }
        try {
            resource.close();
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "Failed to close the Lucene index {} at {}: {}", what, openPath, e.getMessage(), e );
        }
    }

    /** A reader borrowed from the store; {@link #close()} gives it back. */
    public static final class ReaderLease implements AutoCloseable {

        private final ReaderManager manager;
        private final DirectoryReader reader;

        ReaderLease( final ReaderManager manager, final DirectoryReader reader ) {
            this.manager = manager;
            this.reader = reader;
        }

        /** @return the borrowed reader; valid until this lease is closed */
        public DirectoryReader reader() {
            return reader;
        }

        @Override
        public void close() throws IOException {
            manager.release( reader );
        }
    }
}

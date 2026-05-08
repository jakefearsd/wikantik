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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link LuceneIndexLifecycle}.
 *
 * <p>Holds the cached {@link Analyzer} instance and all mutable shared state:
 * search-counter metrics and the {@code lastUpdateInstant} timestamp. Provides
 * an {@link IndexWriter} factory. The Lucene index directory path is NOT stored
 * here — it is managed by the enclosing {@code LuceneSearchProvider} facade and
 * threaded through the indexer and searcher via {@code Supplier<String>} lambdas.</p>
 */
public class DefaultLuceneIndexLifecycle implements LuceneIndexLifecycle {


    private final Analyzer analyzer;

    /** Total number of non-blank queries processed since startup. */
    private final AtomicLong totalSearchCount = new AtomicLong();
    /** Subset of {@link #totalSearchCount} whose result set was empty. */
    private final AtomicLong zeroResultSearchCount = new AtomicLong();
    /** Wall-clock duration of the most recent query, in milliseconds. */
    private final AtomicLong lastQueryElapsedMillis = new AtomicLong();

    /**
     * Timestamp of the most recent successful Lucene index update.
     * Starts at {@link Instant#EPOCH} until the first update lands.
     */
    private volatile Instant lastUpdateInstant = Instant.EPOCH;

    /**
     * Constructs a lifecycle instance with the given analyzer.
     *
     * @param analyzer the cached analyzer instance
     */
    public DefaultLuceneIndexLifecycle( final Analyzer analyzer ) {
        this.analyzer = analyzer;
    }

    @Override
    public Analyzer getAnalyzer() {
        return analyzer;
    }

    @Override
    public IndexWriter getIndexWriter( final Directory luceneDir ) throws IOException {
        final IndexWriterConfig writerConfig = new IndexWriterConfig( analyzer );
        writerConfig.setOpenMode( OpenMode.CREATE_OR_APPEND );
        return new IndexWriter( luceneDir, writerConfig );
    }

    @Override
    public Instant lastUpdateInstant() {
        return lastUpdateInstant;
    }

    @Override
    public void touchLastUpdateInstant() {
        lastUpdateInstant = Instant.now();
    }

    @Override
    public long getTotalSearchCount() {
        return totalSearchCount.get();
    }

    @Override
    public long getZeroResultSearchCount() {
        return zeroResultSearchCount.get();
    }

    @Override
    public long getLastQueryElapsedMillis() {
        return lastQueryElapsedMillis.get();
    }

    @Override
    public void recordSearchMetrics( final long elapsedMs, final boolean zeroResults ) {
        lastQueryElapsedMillis.set( elapsedMs );
        totalSearchCount.incrementAndGet();
        if ( zeroResults ) {
            zeroResultSearchCount.incrementAndGet();
        }
    }
}

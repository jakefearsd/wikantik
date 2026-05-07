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
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.time.Instant;

/**
 * Cross-cutting lifecycle seam for the Lucene search decomposition.
 *
 * <p>Owns the cached {@link Analyzer} instance and all shared mutable state:
 * search-counter metrics and the {@code lastUpdateInstant} timestamp.
 * Provides an {@link IndexWriter} factory used by the indexer.
 * The Lucene index directory path is managed by the enclosing
 * {@code LuceneSearchProvider} facade and threaded through via
 * {@code Supplier<String>} lambdas on the indexer and searcher.</p>
 */
public interface LuceneIndexLifecycle {

    /** @return the cached Analyzer instance for this index */
    Analyzer getAnalyzer();

    /**
     * Creates (or opens) an {@link IndexWriter} on the given directory.
     * Caller is responsible for closing the writer.
     *
     * @param luceneDir the Lucene {@link Directory} to open
     * @return a new {@link IndexWriter}
     * @throws IOException if the writer cannot be opened
     */
    IndexWriter getIndexWriter( Directory luceneDir ) throws IOException;

    /**
     * @return the timestamp of the most recent successful index update;
     *         {@link Instant#EPOCH} if no update has been recorded yet
     */
    Instant lastUpdateInstant();

    /**
     * Advances the {@link #lastUpdateInstant()} to {@link Instant#now()}.
     * Called by the indexer after every successful write.
     */
    void touchLastUpdateInstant();

    /** @return total number of non-blank queries processed since startup */
    long getTotalSearchCount();

    /** @return number of queries that produced zero results since startup */
    long getZeroResultSearchCount();

    /** @return elapsed wall-clock millis of the most recent non-blank query, or 0 if none yet */
    long getLastQueryElapsedMillis();

    /**
     * Records search metrics after a completed query.
     *
     * @param elapsedMs   wall-clock duration of the query in milliseconds
     * @param zeroResults {@code true} if the result set was empty
     */
    void recordSearchMetrics( long elapsedMs, boolean zeroResults );
}

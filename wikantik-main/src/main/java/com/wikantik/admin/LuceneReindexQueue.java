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
package com.wikantik.admin;

import com.wikantik.api.core.Page;

import java.time.Instant;

/**
 * Thin abstraction over the async Lucene reindex pipeline used by
 * {@link ContentIndexRebuildService} so rebuild orchestration can enqueue
 * pages, observe queue depth, and wipe the index without depending on the
 * concrete search-provider implementation.
 *
 * <p>Task 11 wires this interface onto {@code LuceneSearchProvider}; until
 * then only in-memory test fakes implement it.</p>
 */
public interface LuceneReindexQueue {

    /** Enqueues a page for asynchronous Lucene reindex. */
    void reindexPage( Page page );

    /** Returns the number of pages currently awaiting reindex. */
    int queueDepth();

    /** Returns the number of documents currently held by the Lucene index. */
    int documentCount();

    /**
     * Returns the timestamp of the most recent successful index update,
     * or {@code null} if no update has ever been recorded.
     */
    Instant lastUpdateInstant();

    /** Removes all documents from the Lucene index. */
    void clearIndex();
}

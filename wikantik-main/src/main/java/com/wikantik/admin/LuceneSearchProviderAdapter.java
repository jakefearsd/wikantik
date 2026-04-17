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
import com.wikantik.search.LuceneSearchProvider;

import java.time.Instant;

/**
 * Production adapter that exposes {@link LuceneSearchProvider}'s
 * asynchronous reindex pipeline to {@link ContentIndexRebuildService}
 * through the {@link LuceneReindexQueue} abstraction.
 *
 * <p>The adapter is a thin pass-through — all real work lives on the
 * underlying provider. Keeping it in the {@code admin} package lets the
 * rebuild service stay decoupled from the concrete search-provider class,
 * which keeps unit tests fake-friendly and makes it easy to swap in a
 * non-Lucene backend later.
 */
public final class LuceneSearchProviderAdapter implements LuceneReindexQueue {

    private final LuceneSearchProvider provider;

    public LuceneSearchProviderAdapter( final LuceneSearchProvider provider ) {
        this.provider = provider;
    }

    @Override
    public void reindexPage( final Page page ) {
        provider.reindexPage( page );
    }

    @Override
    public int queueDepth() {
        return provider.getReindexQueueDepth();
    }

    @Override
    public int documentCount() {
        return provider.documentCount();
    }

    @Override
    public Instant lastUpdateInstant() {
        return provider.lastUpdateInstant();
    }

    @Override
    public void clearIndex() {
        provider.clearIndex();
    }
}

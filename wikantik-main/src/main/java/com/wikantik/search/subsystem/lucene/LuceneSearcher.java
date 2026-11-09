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

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.search.SearchResult;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Read-side seam for {@code LuceneSearchProvider}.
 *
 * <p>Owns {@code findPages} overloads, query parsing,
 * scored-hits assembly, highlighting, and snippet extraction.</p>
 */
public interface LuceneSearcher {

    /** Create contexts also. Generating contexts can be expensive, so they're not on by default. */
    int FLAG_CONTEXTS = 0x01;

    /**
     * Searches the index using the default flag set ({@link #FLAG_CONTEXTS}).
     *
     * @param query       the Lucene query string
     * @param wikiContext the current wiki context (session + page)
     * @return matching search results, ordered by score
     * @throws ProviderException if the backend cannot be queried
     */
    Collection<SearchResult> findPages( String query, Context wikiContext ) throws ProviderException;

    /**
     * Searches the index with explicit flags.
     *
     * @param query       the Lucene query string
     * @param flags       bitfield of {@link #FLAG_CONTEXTS} etc.
     * @param wikiContext the current wiki context (session + page)
     * @return matching search results, ordered by score
     * @throws ProviderException if the backend cannot be queried
     */
    Collection<SearchResult> findPages( String query, int flags, Context wikiContext ) throws ProviderException;
}

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
package com.wikantik.api.knowledge;

/**
 * Input for {@link ContextRetrievalService#retrieve(ContextQuery)}. {@code query}
 * is the natural-language query; {@code maxPages} caps the returned page list
 * at 20; {@code chunksPerPage} caps per-page contributing chunks at 5. The
 * optional {@code filter} pre-filters the candidate page set before ranking.
 */
public record ContextQuery(
    String query,
    int maxPages,
    int chunksPerPage,
    PageListFilter filter
) {
    public static final int MAX_PAGES_CAP = 20;
    public static final int MAX_CHUNKS_PER_PAGE_CAP = 5;

    public ContextQuery {
        if ( query == null || query.isBlank() ) {
            throw new IllegalArgumentException( "query must not be blank" );
        }
        if ( maxPages <= 0 || maxPages > MAX_PAGES_CAP ) {
            throw new IllegalArgumentException(
                "maxPages must be in (0, " + MAX_PAGES_CAP + "], got " + maxPages );
        }
        if ( chunksPerPage <= 0 || chunksPerPage > MAX_CHUNKS_PER_PAGE_CAP ) {
            throw new IllegalArgumentException(
                "chunksPerPage must be in (0, " + MAX_CHUNKS_PER_PAGE_CAP + "], got " + chunksPerPage );
        }
        filter = filter == null ? PageListFilter.unfiltered() : filter;
    }
}

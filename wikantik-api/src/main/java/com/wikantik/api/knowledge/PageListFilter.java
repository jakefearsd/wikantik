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

import java.time.Instant;
import java.util.List;

/**
 * Optional filters for {@link ContextRetrievalService#listPages(PageListFilter)}
 * and as an embedded filter in {@link ContextQuery}. All string/list fields are
 * null-tolerant; the two bounded numerics are validated.
 *
 * <p>Limit is bounded at 200 to avoid runaway JSON responses. Callers that
 * genuinely need more should paginate via {@code offset}.</p>
 */
public record PageListFilter(
    String cluster,
    List< String > tags,
    String type,
    String author,
    Instant modifiedAfter,
    Instant modifiedBefore,
    int limit,
    int offset
) {
    public static final int MAX_LIMIT = 200;

    public PageListFilter {
        if ( limit < 0 || limit > MAX_LIMIT ) {
            throw new IllegalArgumentException(
                "limit must be in [0, " + MAX_LIMIT + "], got " + limit );
        }
        if ( offset < 0 ) {
            throw new IllegalArgumentException( "offset must be >= 0, got " + offset );
        }
        tags = tags == null ? List.of() : List.copyOf( tags );
    }

    /** A filter with default pagination (50/0) and no field constraints. */
    public static PageListFilter unfiltered() {
        return new PageListFilter( null, null, null, null, null, null, 50, 0 );
    }
}

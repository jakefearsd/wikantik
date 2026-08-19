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

import com.wikantik.api.exceptions.ProviderException;

/**
 * Raised when a search runs against an index that exists but has no committed
 * segments yet — a writer is mid-build, or a previous build left a half-written
 * index behind.
 *
 * <p>This is deliberately distinct from a general {@link ProviderException}, and
 * far more deliberately distinct from an empty result set. Lucene writes
 * {@code pending_segments_N} and only renames it to {@code segments_N} on commit,
 * so a search landing in that window raises
 * {@link org.apache.lucene.index.IndexNotFoundException}. That used to be caught
 * as a plain {@code IOException} and answered with an empty collection, which is
 * a <em>wrong answer indistinguishable from a correct one</em>: the caller cannot
 * tell "nothing matched" from "the index could not be read".
 *
 * <p>Callers whose job is to wait for the index — pollers, warm-up probes, tests —
 * should treat this as "not yet" and retry. Callers serving a user request should
 * surface it as a temporary condition rather than reporting no results.
 */
public class SearchIndexNotReadyException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public SearchIndexNotReadyException( final String msg ) {
        super( msg );
    }
}

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
package com.wikantik.search.hybrid;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only neighbor-lookup abstraction over the KG edge set for the Phase 3
 * graph rerank. Implementations return all nodes adjacent to a given node,
 * treating edges as undirected (a path from A to B exists regardless of the
 * original edge direction) since proximity reranking cares about "are these
 * two entities related at all" rather than relationship directionality.
 *
 * <p>Kept as an interface so the scorer can be unit-tested against an in-process
 * fake while the production path uses {@link InMemoryGraphNeighborIndex}.</p>
 */
public interface GraphNeighborIndex {

    /**
     * Neighbors of {@code nodeId}, treating edges as undirected. Returns an
     * empty set when the node has no edges or is unknown; implementations must
     * never return {@code null}.
     */
    Set< UUID > neighbors( UUID nodeId );

    /** True iff the index is populated (at least one edge loaded). */
    boolean isReady();

    /** Approximate number of distinct nodes covered. Intended for metrics/logging. */
    int nodeCount();
}

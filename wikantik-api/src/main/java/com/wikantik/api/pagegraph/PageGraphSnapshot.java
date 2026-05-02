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
package com.wikantik.api.pagegraph;

import java.util.List;

/**
 * Immutable, view-friendly projection of the Page Graph (pages + wikilinks)
 * for the {@code /page-graph} React route. Counterpart to
 * {@code com.wikantik.api.knowledge.GraphSnapshot} which projects the
 * Knowledge Graph (LLM-extracted entities + typed predicates) — see
 * {@code docs/wikantik-pages/PageGraphVsKnowledgeGraph.md} for the
 * distinction.
 *
 * <p>{@code generatedAt} is an ISO-8601 instant string. {@code hubDegreeThreshold}
 * is the in+out-degree the legend uses to colour hub nodes; the service
 * computes it from the corpus rather than hard-coding it so a small wiki
 * still surfaces hubs.</p>
 */
public record PageGraphSnapshot(
    String generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List< PageGraphNode > nodes,
    List< PageGraphEdge > edges
) {
    public PageGraphSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf( nodes );
        edges = edges == null ? List.of() : List.copyOf( edges );
    }
}

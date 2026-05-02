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
 * One vertex in the Page Graph snapshot — a single wiki page with cached
 * structural metadata and link-degree counts. JSON shape mirrors
 * {@code com.wikantik.api.knowledge.SnapshotNode} so the existing
 * cytoscape-based viewer can render it without changes; only the content of
 * the snapshot differs (pages instead of LLM-extracted entities).
 *
 * <p>{@code id} is the page's canonical_id when one is set, falling back to
 * the slug for pages that have not yet been claimed. {@code role} is one of
 * {@code hub}, {@code normal}, {@code orphan}, {@code stub}, or
 * {@code restricted}; restricted nodes have {@code name}, {@code type},
 * {@code cluster}, {@code tags}, and {@code sourcePage} redacted to
 * {@code null}/empty.</p>
 */
public record PageGraphNode(
    String id,
    String name,
    String type,
    String role,
    String sourcePage,
    int degreeIn,
    int degreeOut,
    boolean restricted,
    String cluster,
    List< String > tags
) {
    public PageGraphNode {
        tags = tags == null ? List.of() : List.copyOf( tags );
    }
}

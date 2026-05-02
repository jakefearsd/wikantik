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

/**
 * One directed edge in the Page Graph snapshot — a wikilink from {@code source}
 * to {@code target}, both keyed by {@link PageGraphNode#id()}.
 * {@code relationshipType} is always {@code "page-link"} (typed relations were
 * removed 2026-05-02 — see PageGraphVsKnowledgeGraph) and {@code provenance}
 * is always {@code "HUMAN_AUTHORED"} since wikilinks are extracted from
 * page bodies. Both fields are kept on the wire for compatibility with the
 * existing cytoscape-based viewer.
 */
public record PageGraphEdge(
    String id,
    String source,
    String target,
    String relationshipType,
    String provenance
) {}

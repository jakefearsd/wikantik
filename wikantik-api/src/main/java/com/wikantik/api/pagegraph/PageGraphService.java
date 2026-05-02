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

import com.wikantik.api.core.Session;

/**
 * Builds the snapshot that powers the {@code /page-graph} React route. The
 * Page Graph is the wikilink graph between pages — each node is a page, each
 * edge is a wikilink parsed from the source page's body. Source data is the
 * {@link StructuralIndexService} (page metadata, canonical_id, cluster, tags)
 * plus {@link com.wikantik.api.managers.ReferenceManager} (refers-to /
 * referred-by maps).
 *
 * <p>Distinct from {@link com.wikantik.api.knowledge.KnowledgeGraphService},
 * which builds a snapshot of LLM-extracted entities and typed predicates.</p>
 */
public interface PageGraphService {

    /**
     * Builds a Page Graph snapshot for the given viewer. ACL-restricted pages
     * are present in the response but with their identifying fields nulled
     * out (and {@code restricted=true}) so the viewer can see graph topology
     * without reading page names they aren't authorized to see.
     *
     * @param viewer the requesting session — may be {@code null} for
     *               anonymous access (the same redaction rules apply)
     */
    PageGraphSnapshot snapshot( Session viewer );
}

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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.derived.DerivedPage;

/**
 * Content-type-aware chunking strategy selection seam.
 *
 * <p><strong>v1 is flat-and-tuned</strong> — all document types (hand-authored articles,
 * runbooks, design docs, and machine-produced derived pages) use the same default
 * {@link ContentChunker.Config} read from {@code wikantik.chunker.*} properties.
 * Per-type tuning is explicitly deferred: it requires a harness-gated measurement
 * showing that derived-page recall benefits from a different window size before any
 * divergence is introduced.</p>
 *
 * <p>This is the <em>single place to branch by document type later</em>. When the
 * harness provides evidence, add a branch here (e.g.
 * {@link DerivedPage#isDerived(java.util.Map) DerivedPage.isDerived(page.metadata())}
 * → a different Config) and adjust the {@code ChunkingStrategySelectorTest} assertions
 * to document the new invariant. Nothing else needs to change.</p>
 */
public class ChunkingStrategySelector {

    /**
     * Returns the {@link ContentChunker.Config} to use for the given page.
     *
     * <p>In v1 this always returns {@code defaultConfig} unchanged, regardless of
     * whether the page is derived (has {@code derived_from} frontmatter) or hand-authored.
     * The method signature and its call site document the hook for future per-type divergence.</p>
     *
     * @param page          the parsed page (metadata + body); metadata is inspected for the
     *                      {@code derived_from} key but not acted on in v1
     * @param defaultConfig the config built from {@code wikantik.chunker.*} properties; returned
     *                      unchanged in v1
     * @return the config to pass to {@link ContentChunker#chunk}; never {@code null}
     */
    public ContentChunker.Config configFor( final ParsedPage page,
                                            final ContentChunker.Config defaultConfig ) {
        // v1: flat-and-tuned — all types use the same config.
        // Future: if ( DerivedPage.isDerived( page.metadata() ) ) { return derivedConfig; }
        return defaultConfig;
    }
}

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
package com.wikantik.knowledge.bundle;

import java.util.List;

/**
 * Supplies the candidate sections for a context bundle, before rerank → dedup →
 * cite → top-N. Two implementations: {@link RetrievalSectionSource} (the original
 * page-gated hybrid path) and {@link DenseChunkSectionSource} (global dense-chunk
 * retrieval — the 2026-06-14 measurement showed it beats the page-gated path,
 * recall@12 0.735 vs 0.685, because the page pre-select discards retrievable
 * sections).
 */
@FunctionalInterface
public interface SectionCandidateSource {

    /** Candidate sections for the query, ordered best-first (highest dense score). */
    List< CandidateSection > candidates( String query );
}

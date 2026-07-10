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

/** Candidate sections for a query plus the true top dense cosine (for the coverage signal).
 *  topSimilarity is -1.0 when no dense similarity is available (e.g. embedder down,
 *  or the page-gated path which has no per-section cosine).
 *
 *  <p>{@code denseCosineScale} is true iff every {@link CandidateSection#denseScore()} in
 *  {@code sections} is on the same cosine scale as {@code topSimilarity} — i.e. a real dense
 *  cosine, not a rank proxy. This matters because {@link KneeCutoff} compares denseScore
 *  against {@code topSimilarity * retainRatio}; that comparison is only meaningful when both
 *  sides are cosines. The pure {@link DenseChunkSectionSource} path sets this true. The default
 *  {@link HybridChunkSectionSource} path (denseScore = {@code 1/(1+pos)}, a display-only rank
 *  proxy) and {@link RetrievalSectionSource} leave it false via the 2-arg {@link #of} — the
 *  safe default so the knee no-ops rather than truncating on a scale it can't compare.</p> */
record SectionCandidates( List< CandidateSection > sections, double topSimilarity, boolean denseCosineScale ) {
    SectionCandidates {
        sections = sections == null ? List.of() : List.copyOf( sections );
    }

    /** 2-arg overload — safe default: {@code denseCosineScale = false}, so the knee no-ops
     *  unless a source explicitly opts in via the 3-arg {@link #of}. */
    static SectionCandidates of( final List< CandidateSection > sections, final double topSimilarity ) {
        return new SectionCandidates( sections, topSimilarity, false );
    }

    static SectionCandidates of( final List< CandidateSection > sections, final double topSimilarity,
                                 final boolean denseCosineScale ) {
        return new SectionCandidates( sections, topSimilarity, denseCosineScale );
    }
}

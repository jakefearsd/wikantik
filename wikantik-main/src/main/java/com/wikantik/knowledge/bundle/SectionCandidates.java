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
 *  or the page-gated path which has no per-section cosine). */
record SectionCandidates( List< CandidateSection > sections, double topSimilarity ) {
    SectionCandidates {
        sections = sections == null ? List.of() : List.copyOf( sections );
    }
    static SectionCandidates of( final List< CandidateSection > sections, final double topSimilarity ) {
        return new SectionCandidates( sections, topSimilarity );
    }
}

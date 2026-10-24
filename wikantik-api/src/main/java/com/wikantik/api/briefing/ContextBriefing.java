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
package com.wikantik.api.briefing;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;

import java.util.List;

/** The unit a session-start briefing returns: retrieval-driven sections (with coverage), plus
 *  the explicitly pinned/cluster items, warnings, and the token budget actually used.
 *
 *  <p>Two coverage values, deliberately distinct: {@code coverage} is what the briefing
 *  <em>delivered</em> as sections (recounted over the kept sections), while {@code retrievalCoverage}
 *  is the retrieval bundle's own coverage <em>before</em> the cluster-scope partition, i.e. what
 *  retrieval actually found. The query log records the latter: a prompt whose strong hits all lie
 *  outside the requested cluster is not a content gap just because zero sections were delivered.
 *  {@code retrievalCoverage} is {@code null}-tolerant and defaults to the same object as
 *  {@code coverage}.</p>
 *
 *  <p><b>The raw value is server-side only.</b> The retrieval backend does not ACL-filter, so the
 *  assembler's {@code retrievalCoverage} counts pages the caller may not view. The surfaces read it from
 *  the briefing <em>before</em> the ACL gate for the query log; the gate then recounts it over the
 *  viewable sections, exactly as it does {@code coverage}, before anything is returned to a caller.</p> */
public record ContextBriefing( String prompt, List< BundleSection > sections, BundleCoverage coverage,
                               List< BriefingItem > items, List< String > warnings,
                               int budgetTokens, int usedTokens, BundleCoverage retrievalCoverage ) {
    public ContextBriefing {
        sections = sections == null ? List.of() : List.copyOf( sections );
        items = items == null ? List.of() : List.copyOf( items );
        warnings = warnings == null ? List.of() : List.copyOf( warnings );
        coverage = coverage == null ? BundleCoverage.empty() : coverage;
        retrievalCoverage = retrievalCoverage == null ? coverage : retrievalCoverage;
    }

    /** A briefing whose retrieval coverage is the delivered {@code coverage} (no scope partition took place). */
    public ContextBriefing( final String prompt, final List< BundleSection > sections, final BundleCoverage coverage,
                            final List< BriefingItem > items, final List< String > warnings,
                            final int budgetTokens, final int usedTokens ) {
        this( prompt, sections, coverage, items, warnings, budgetTokens, usedTokens, null );
    }
}

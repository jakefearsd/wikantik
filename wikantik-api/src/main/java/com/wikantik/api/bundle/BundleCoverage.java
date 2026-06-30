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
package com.wikantik.api.bundle;

import java.util.List;
import java.util.Objects;

/** Coverage signal for a context bundle: how much, how corroborated, how confident.
 *  Pure data — confidence/threshold logic lives in wikantik-main (BundleCoverageCalculator). */
public record BundleCoverage(
    int sectionCount, int distinctPageCount, double topSimilarity, String confidence
) {
    public static final String STRONG = "strong";
    public static final String PARTIAL = "partial";
    public static final String WEAK = "weak";
    public static final String UNKNOWN = "unknown";

    public static BundleCoverage empty() {
        return new BundleCoverage( 0, 0, -1.0, UNKNOWN );
    }

    /** Distinct non-null canonical_ids across the sections — thin vs corroborated coverage. */
    public static int distinctPages( final List< BundleSection > sections ) {
        return (int) sections.stream()
            .map( BundleSection::canonicalId )
            .filter( Objects::nonNull )
            .distinct().count();
    }

    /** Recompute counts over a (post-ACL-gate) section subset, preserving the retrieval-derived
     *  topSimilarity + confidence (cosine is unaffected by view filtering). See design §5. */
    public static BundleCoverage recount( final BundleCoverage original,
                                          final List< BundleSection > gatedSections ) {
        return new BundleCoverage( gatedSections.size(), distinctPages( gatedSections ),
            original.topSimilarity(), original.confidence() );
    }
}

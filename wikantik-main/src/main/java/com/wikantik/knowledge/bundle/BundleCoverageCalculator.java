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

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;

import java.util.List;

/** Derives the bundle confidence label from the top dense cosine + section/page counts.
 *  Thresholds are provisional/tunable (design §4); lives in wikantik-main so wikantik-api
 *  stays logic-free. */
final class BundleCoverageCalculator {

    static final double DEFAULT_STRONG = 0.55;
    static final double DEFAULT_PARTIAL = 0.40;

    private final double strongThreshold;
    private final double partialThreshold;

    BundleCoverageCalculator( final double strongThreshold, final double partialThreshold ) {
        this.strongThreshold = strongThreshold;
        this.partialThreshold = partialThreshold;
    }

    static BundleCoverageCalculator defaults() {
        return new BundleCoverageCalculator( DEFAULT_STRONG, DEFAULT_PARTIAL );
    }

    BundleCoverage compute( final double topSimilarity, final List< BundleSection > sections ) {
        final int n = sections.size();
        final int pages = BundleCoverage.distinctPages( sections );
        final String confidence;
        if ( topSimilarity < 0 ) {
            confidence = BundleCoverage.UNKNOWN;
        } else if ( n == 0 ) {
            confidence = BundleCoverage.WEAK;
        } else if ( topSimilarity >= strongThreshold && n >= 3 ) {
            confidence = BundleCoverage.STRONG;
        } else if ( topSimilarity >= partialThreshold ) {
            confidence = BundleCoverage.PARTIAL;
        } else {
            confidence = BundleCoverage.WEAK;
        }
        return new BundleCoverage( n, pages, topSimilarity, confidence );
    }
}

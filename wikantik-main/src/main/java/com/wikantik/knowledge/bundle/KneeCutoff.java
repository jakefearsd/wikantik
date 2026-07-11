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

/** Dynamic-N selector: count of candidates whose denseScore ≥ topSimilarity·retainRatio
 *  (order-independent), clamped [1, maxSections]. Disabled, or a non-dense path
 *  ({@code topSimilarity < 0}), or an empty list all return {@code maxSections} (the fixed cut —
 *  byte-identical to pre-knee behaviour). */
record KneeCutoff( boolean enabled, double retainRatio ) {

    static KneeCutoff disabled() {
        return new KneeCutoff( false, 0.5 );
    }

    static KneeCutoff of( final boolean enabled, final double retainRatio ) {
        final double r = ( retainRatio > 0.0 && retainRatio <= 1.0 ) ? retainRatio : 0.5;
        return new KneeCutoff( enabled, r );
    }

    int effectiveN( final List< CandidateSection > denseSorted, final double topSimilarity, final int maxSections ) {
        if ( !enabled || topSimilarity < 0.0 || denseSorted.isEmpty() ) {
            return maxSections;
        }
        final double retainLine = topSimilarity * retainRatio;
        int kept = 0;
        for ( final CandidateSection c : denseSorted ) {
            if ( c.denseScore() >= retainLine ) kept++;
        }
        return Math.max( 1, Math.min( kept, maxSections ) );
    }
}

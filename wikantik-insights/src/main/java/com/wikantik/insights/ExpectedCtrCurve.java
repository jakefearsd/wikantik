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
package com.wikantik.insights;

/**
 * Estimated click-through rate at a given average search-result position. {@link OpportunityEngine}
 * rule 2 ({@code ENGINE_DIVERGENCE}) uses this to convert a position gap into an expected-clicks
 * uplift: {@code weak_impressions x (ctrAt(strong_pos) - ctrAt(weak_pos))} (content-intelligence
 * design §7.3, rule 2).
 */
@FunctionalInterface
public interface ExpectedCtrCurve {

    /**
     * @param position an average search-result position (1 = top result)
     * @return the estimated click-through rate at that position, in {@code [0, 1]}
     */
    double ctrAt( double position );

    /**
     * A documented placeholder step table standing in for jakemon's own {@code EXPECTED_CTR}
     * curve -- the single definition of the CTR model (design §7.3, "Imported from jakemon,
     * unchanged": "jakemon's {@code EXPECTED_CTR} curve stays the single place the CTR model is
     * defined"). Work item J3 (design §12.1) replaces this table by shipping jakemon's real,
     * measured curve in the ingest payload; until then, this is Wikantik's best local estimate,
     * not a ground truth, and every consumer of it should treat it accordingly.
     *
     * <p>The table (position -&gt; CTR): 1&#8594;0.28, 2&#8594;0.15, 3&#8594;0.11, 4&#8594;0.08,
     * 5&#8594;0.06, 6&#8594;0.05, 7&#8594;0.04, 8&#8594;0.035, 9&#8594;0.03, 10&#8594;0.025,
     * 11-20&#8594;0.012, 21-50&#8594;0.005, &gt;50&#8594;0.002.</p>
     *
     * <p><strong>This is a step function, not an interpolated curve.</strong> A fractional
     * position is floored to its integer bucket before lookup -- e.g. position 2.9 uses the
     * position-2 value (0.15), not a value interpolated between the position-2 and position-3
     * rows. Positions below 1 (but positive) clamp to the position-1 value, since there is no
     * result above the first. {@link Double#isNaN(double)} or a non-positive position (zero or
     * negative -- not a real rank) returns {@code 0.0} rather than clamping, since there is no
     * result to estimate a CTR for at all.</p>
     *
     * @return the default stepped CTR-by-position table
     */
    static ExpectedCtrCurve defaultCurve() {
        return DefaultExpectedCtrCurve.INSTANCE;
    }

    /** The step-table implementation backing {@link #defaultCurve()}. */
    final class DefaultExpectedCtrCurve implements ExpectedCtrCurve {

        private static final DefaultExpectedCtrCurve INSTANCE = new DefaultExpectedCtrCurve();

        /** Positions 1-10, index 0 == position 1. */
        private static final double[] TOP_TEN = {
                0.28, 0.15, 0.11, 0.08, 0.06, 0.05, 0.04, 0.035, 0.03, 0.025 };

        private static final double POSITIONS_11_TO_20 = 0.012;
        private static final double POSITIONS_21_TO_50 = 0.005;
        private static final double POSITIONS_ABOVE_50 = 0.002;

        private DefaultExpectedCtrCurve() {
        }

        @Override
        public double ctrAt( final double position ) {
            if ( Double.isNaN( position ) || position <= 0 ) {
                return 0.0;
            }

            final double clamped = position < 1 ? 1 : position;
            final int bucket = ( int ) Math.floor( clamped );

            if ( bucket <= TOP_TEN.length ) {
                return TOP_TEN[ bucket - 1 ];
            }
            if ( bucket <= 20 ) {
                return POSITIONS_11_TO_20;
            }
            if ( bucket <= 50 ) {
                return POSITIONS_21_TO_50;
            }
            return POSITIONS_ABOVE_50;
        }
    }
}

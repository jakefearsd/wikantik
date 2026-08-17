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

import java.util.Map;

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
     * Builds a curve from jakemon's real, measured {@code EXPECTED_CTR} table (work item J3,
     * design §12.1) -- the top-10 points shipped in the ingest payload's {@code expected_ctr} key
     * (V057) -- plus the two tail-decay numbers jakemon does <strong>not</strong> ship (see
     * V057's migration comment for why: {@code ship_visibility.py} sends only the table, never
     * jakemon's {@code _DEEP_CTR} floor or its decay formula).
     *
     * <p>Mirrors jakemon's {@code opportunities.expected_ctr()} exactly:</p>
     * <pre>
     * position &lt;= 0            -&gt; 0.0
     * p = max(1, round(position))          # 0.4 -&gt; 1, NOT 0
     * p in table               -&gt; table[p]
     * p &lt;= deepMaxPosition     -&gt; deepCtr
     * otherwise                -&gt; round(deepCtr * (deepMaxPosition / p), 4)
     * </pre>
     *
     * <p>{@code round(position)} here is Java's round-half-up ({@link Math#round(double)}), not
     * Python's round-half-to-even -- the two differ only on an exact {@code x.5} tie, which an
     * average search-result position (a continuous, measured value) essentially never lands on
     * exactly; this is not expected to diverge from jakemon's own output in practice.</p>
     *
     * @param table          position -&gt; CTR, keyed by the exact integer positions jakemon shipped
     *                       (today: 1-10); copied defensively, so later mutation of the caller's
     *                       map does not change the returned curve
     * @param deepCtr        the flat floor CTR for positions beyond the table but at or below
     *                       {@code deepMaxPosition} (jakemon's {@code _DEEP_CTR}, mirrored in
     *                       config since jakemon does not ship it -- see V057)
     * @param deepMaxPosition the last position the flat {@code deepCtr} floor applies to; positions
     *                       deeper than this decay as {@code deepCtr * (deepMaxPosition / p)}
     *                       (jakemon's hardcoded {@code 20}, mirrored in config for the same reason)
     * @return a curve backed by {@code table}, falling through to the {@code deepCtr}/
     *         {@code deepMaxPosition} tail rule for every position the table does not cover
     */
    static ExpectedCtrCurve fromTable( final Map<Integer, Double> table, final double deepCtr,
                                       final int deepMaxPosition ) {
        final Map<Integer, Double> copy = Map.copyOf( table );
        return position -> {
            if ( Double.isNaN( position ) || position <= 0 ) {
                return 0.0;
            }
            final int p = ( int ) Math.max( 1, Math.round( position ) );
            final Double tableValue = copy.get( p );
            if ( tableValue != null ) {
                return tableValue;
            }
            if ( p <= deepMaxPosition ) {
                return deepCtr;
            }
            final double decayed = deepCtr * ( deepMaxPosition / ( double ) p );
            return Math.round( decayed * 10000.0 ) / 10000.0;
        };
    }

    /**
     * A documented placeholder step table standing in for jakemon's own {@code EXPECTED_CTR}
     * curve -- the single definition of the CTR model (design §7.3, "Imported from jakemon,
     * unchanged": "jakemon's {@code EXPECTED_CTR} curve stays the single place the CTR model is
     * defined"). {@link #fromTable} is the real thing, built from what jakemon actually ships
     * (work item J3, design §12.1); this table is used only as the fallback when no imported
     * curve is available at all (subsystem disabled, nothing shipped yet, or the most recent
     * shipment has gone stale) -- see {@code ContentOpportunityService}.
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

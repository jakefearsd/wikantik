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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ExpectedCtrCurve#defaultCurve()} -- the step table named in the
 * content-intelligence design (§7.3 rule 2), including its boundary/clamp rules.
 */
class ExpectedCtrCurveTest {

    private final ExpectedCtrCurve curve = ExpectedCtrCurve.defaultCurve();

    // --- the named table values -------------------------------------------------------------------

    @Test
    void position1IsPoint28() {
        assertEquals( 0.28, curve.ctrAt( 1 ), 0.0001 );
    }

    @Test
    void position2IsPoint15() {
        assertEquals( 0.15, curve.ctrAt( 2 ), 0.0001 );
    }

    @Test
    void position3IsPoint11() {
        assertEquals( 0.11, curve.ctrAt( 3 ), 0.0001 );
    }

    @Test
    void position4IsPoint08() {
        assertEquals( 0.08, curve.ctrAt( 4 ), 0.0001 );
    }

    @Test
    void position5IsPoint06() {
        assertEquals( 0.06, curve.ctrAt( 5 ), 0.0001 );
    }

    @Test
    void position6IsPoint05() {
        assertEquals( 0.05, curve.ctrAt( 6 ), 0.0001 );
    }

    @Test
    void position7IsPoint04() {
        assertEquals( 0.04, curve.ctrAt( 7 ), 0.0001 );
    }

    @Test
    void position8IsPoint035() {
        assertEquals( 0.035, curve.ctrAt( 8 ), 0.0001 );
    }

    @Test
    void position9IsPoint03() {
        assertEquals( 0.03, curve.ctrAt( 9 ), 0.0001 );
    }

    @Test
    void position10IsPoint025() {
        assertEquals( 0.025, curve.ctrAt( 10 ), 0.0001 );
    }

    @Test
    void position11To20BucketIsPoint012() {
        assertEquals( 0.012, curve.ctrAt( 11 ), 0.0001 );
        assertEquals( 0.012, curve.ctrAt( 15 ), 0.0001 );
        assertEquals( 0.012, curve.ctrAt( 20 ), 0.0001 );
    }

    @Test
    void position21To50BucketIsPoint005() {
        assertEquals( 0.005, curve.ctrAt( 21 ), 0.0001 );
        assertEquals( 0.005, curve.ctrAt( 35 ), 0.0001 );
        assertEquals( 0.005, curve.ctrAt( 50 ), 0.0001 );
    }

    @Test
    void positionAbove50IsPoint002() {
        assertEquals( 0.002, curve.ctrAt( 51 ), 0.0001 );
    }

    // --- required boundary behaviour (position 0, NaN, 999) ----------------------------------------

    @Test
    void positionZeroReturnsZero() {
        assertEquals( 0.0, curve.ctrAt( 0 ), 0.0001, "a non-positive position is not a real rank" );
    }

    @Test
    void nanPositionReturnsZero() {
        assertEquals( 0.0, curve.ctrAt( Double.NaN ), 0.0001 );
    }

    @Test
    void position999UsesTheAbove50Bucket() {
        assertEquals( 0.002, curve.ctrAt( 999 ), 0.0001 );
    }

    // --- other clamp cases ----------------------------------------------------------------------------

    @Test
    void negativePositionReturnsZero() {
        assertEquals( 0.0, curve.ctrAt( -5 ), 0.0001 );
    }

    @Test
    void fractionalPositionBelowOneClampsToPositionOne() {
        assertEquals( 0.28, curve.ctrAt( 0.5 ), 0.0001 );
    }

    @Test
    void fractionalPositionIsFlooredToItsBucket() {
        // 2.9 uses the position-2 value, not an interpolation toward position 3 -- this is a step
        // function, not a curve fit.
        assertEquals( 0.15, curve.ctrAt( 2.9 ), 0.0001 );
    }

    // --- fromTable() -- parity with jakemon's opportunities.expected_ctr() -----------------------
    //
    // These mirror jakemon's expected_ctr() (visibility/opportunities.py) field for field: the
    // EXPECTED_CTR table (positions 1-10), the _DEEP_CTR floor (0.008, positions 11-20), and the
    // round(_DEEP_CTR * (20.0 / p), 4) decay beyond that. If jakemon's curve or its tail rule ever
    // changes, these assertions must be updated in lockstep -- that is the whole point of testing
    // exact numbers rather than ranges.

    /** jakemon's real, shipped EXPECTED_CTR table (visibility/opportunities.py). */
    private static final Map<Integer, Double> JAKEMON_TABLE = Map.of(
            1, 0.28, 2, 0.15, 3, 0.11, 4, 0.08, 5, 0.06,
            6, 0.05, 7, 0.04, 8, 0.032, 9, 0.028, 10, 0.025 );

    /** jakemon's _DEEP_CTR / page-2 floor, mirrored per the V057 migration's javadoc. */
    private static final double DEEP_CTR = 0.008;
    private static final int DEEP_MAX_POSITION = 20;

    private final ExpectedCtrCurve imported = ExpectedCtrCurve.fromTable(
            JAKEMON_TABLE, DEEP_CTR, DEEP_MAX_POSITION );

    @Test
    void fromTablePosition1IsPoint28() {
        assertEquals( 0.28, imported.ctrAt( 1 ), 0.0001 );
    }

    @Test
    void fromTablePosition3IsPoint11() {
        assertEquals( 0.11, imported.ctrAt( 3 ), 0.0001 );
    }

    @Test
    void fromTablePosition10IsPointO25() {
        assertEquals( 0.025, imported.ctrAt( 10 ), 0.0001 );
    }

    @Test
    void fromTableFractionalBestRankClampsToPositionOneNotTheDeepFloor() {
        // jakemon's own comment: "through to the deep-page floor below (int(round(0.4)) == 0)" --
        // 0.4 must map to position 1's CTR, never fall through to the >=11 deep floor.
        assertEquals( 0.28, imported.ctrAt( 0.4 ), 0.0001 );
    }

    @Test
    void fromTablePosition15UsesTheDeepFloor() {
        assertEquals( 0.008, imported.ctrAt( 15 ), 0.0001 );
    }

    @Test
    void fromTablePosition40DecaysBelowTheDeepFloor() {
        // round(0.008 * (20.0 / 40), 4) == round(0.004, 4) == 0.004
        assertEquals( 0.004, imported.ctrAt( 40 ), 0.0001 );
    }

    @Test
    void fromTablePositionZeroReturnsZero() {
        assertEquals( 0.0, imported.ctrAt( 0 ), 0.0001 );
    }

    @Test
    void fromTableNegativePositionReturnsZero() {
        assertEquals( 0.0, imported.ctrAt( -5 ), 0.0001 );
    }

    @Test
    void fromTablePosition2Point6RoundsToPosition3() {
        assertEquals( 0.11, imported.ctrAt( 2.6 ), 0.0001 );
    }
}

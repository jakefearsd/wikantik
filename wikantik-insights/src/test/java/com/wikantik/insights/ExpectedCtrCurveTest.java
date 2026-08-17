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
}

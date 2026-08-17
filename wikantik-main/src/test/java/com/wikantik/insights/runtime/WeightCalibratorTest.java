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
package com.wikantik.insights.runtime;

import com.wikantik.insights.CalibrationSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WeightCalibrator} (content-intelligence design §7.4.4).
 */
class WeightCalibratorTest {

    private final WeightCalibrator calibrator = new WeightCalibrator();

    private static List<CalibrationSample> samplesOf( final String type, final int count,
                                                       final double predictedEach, final double realizedEach ) {
        final List<CalibrationSample> samples = new ArrayList<>();
        for ( int i = 0; i < count; i++ ) {
            samples.add( new CalibrationSample( type, predictedEach, realizedEach ) );
        }
        return samples;
    }

    @Test
    void belowThresholdTypeStaysDefaultAndUncalibrated() {
        // 19 samples, threshold is 20 -- one short.
        final List<CalibrationSample> samples = samplesOf( "agent_gap", 19, 10.0, 10.0 );

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "agent_gap", 2.0 ) );

        assertFalse( result.calibratedTypes().contains( "agent_gap" ) );
        assertTrue( result.calibratedWeights().isEmpty() );
    }

    @Test
    void atThresholdTypeIsCalibrated() {
        final List<CalibrationSample> samples = samplesOf( "agent_gap", 20, 10.0, 10.0 );

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "agent_gap", 2.0 ) );

        assertTrue( result.calibratedTypes().contains( "agent_gap" ) );
        assertTrue( result.calibratedWeights().containsKey( "agent_gap" ) );
    }

    @Test
    void zeroPredictedSumIsSkippedEntirely() {
        // predictedPriority sums to zero across the group -- ratio is undefined.
        final List<CalibrationSample> samples = new ArrayList<>();
        for ( int i = 0; i < 25; i++ ) {
            samples.add( new CalibrationSample( "engine_divergence", 0.0, 5.0 ) );
        }

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "engine_divergence", 1.0 ) );

        assertFalse( result.calibratedTypes().contains( "engine_divergence" ) );
        assertFalse( result.calibratedWeights().containsKey( "engine_divergence" ) );
    }

    @Test
    void negativePredictedSumIsAlsoSkipped() {
        final List<CalibrationSample> samples = new ArrayList<>();
        for ( int i = 0; i < 25; i++ ) {
            samples.add( new CalibrationSample( "engine_divergence", -1.0, 5.0 ) );
        }

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "engine_divergence", 1.0 ) );

        assertFalse( result.calibratedTypes().contains( "engine_divergence" ) );
    }

    @Test
    void negativeRealizedDeltaDrivesWeightDownButNeverBelowTheFloor() {
        // 25 samples, predicted priority sums to 25 * 10 = 250; realized delta sums deeply
        // negative, so the raw ratio would drive newWeight far below the 0.25x floor.
        final List<CalibrationSample> samples = samplesOf( "stale_high_traffic", 25, 10.0, -100.0 );
        final double defaultWeight = 0.02;

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 1.0, Map.of( "stale_high_traffic", defaultWeight ) );

        assertTrue( result.calibratedTypes().contains( "stale_high_traffic" ) );
        final double floor = defaultWeight * 0.25;
        assertEquals( floor, result.calibratedWeights().get( "stale_high_traffic" ), 1e-9 );
    }

    @Test
    void positiveOverperformanceIsClampedAtTheCeiling() {
        // realized wildly exceeds predicted -- ratio >> 4, so newWeight must clamp at 4x default.
        final List<CalibrationSample> samples = samplesOf( "vocabulary_gap", 25, 1.0, 1000.0 );
        final double defaultWeight = 0.05;

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 1.0, Map.of( "vocabulary_gap", defaultWeight ) );

        final double ceiling = defaultWeight * 4.0;
        assertEquals( ceiling, result.calibratedWeights().get( "vocabulary_gap" ), 1e-9 );
    }

    @Test
    void dampingOfHalfMovesExactlyHalfwayBetweenDefaultAndObservedRatio() {
        // 20 samples, predicted sums to 200, realized sums to 400 -- ratio = 2.0.
        final List<CalibrationSample> samples = samplesOf( "agent_gap", 20, 10.0, 20.0 );
        final double defaultWeight = 2.0;

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "agent_gap", defaultWeight ) );

        // fully-observed value would be defaultWeight * ratio = 4.0; damping=0.5 lands exactly
        // halfway between the default (2.0) and that fully-observed value (4.0) = 3.0.
        assertEquals( 3.0, result.calibratedWeights().get( "agent_gap" ), 1e-9 );
    }

    @Test
    void zeroDampingLeavesWeightAtDefault() {
        final List<CalibrationSample> samples = samplesOf( "agent_gap", 20, 10.0, 100.0 );
        final double defaultWeight = 2.0;

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.0, Map.of( "agent_gap", defaultWeight ) );

        assertEquals( defaultWeight, result.calibratedWeights().get( "agent_gap" ), 1e-9 );
    }

    @Test
    void fullDampingMovesAllTheWayToTheObservedRatio() {
        // predicted sums to 200, realized sums to 300 -- ratio = 1.5.
        final List<CalibrationSample> samples = samplesOf( "agent_gap", 20, 10.0, 15.0 );
        final double defaultWeight = 2.0;

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 1.0, Map.of( "agent_gap", defaultWeight ) );

        assertEquals( defaultWeight * 1.5, result.calibratedWeights().get( "agent_gap" ), 1e-9 );
    }

    @Test
    void typeWithNoConfiguredDefaultWeightCannotBeCalibrated() {
        final List<CalibrationSample> samples = samplesOf( "unknown_type", 25, 10.0, 10.0 );

        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( samples, 20, 0.5, Map.of( "agent_gap", 2.0 ) );

        assertFalse( result.calibratedTypes().contains( "unknown_type" ) );
    }

    @Test
    void multipleTypesAreCalibratedIndependently() {
        final List<CalibrationSample> samples = new ArrayList<>();
        samples.addAll( samplesOf( "agent_gap", 20, 10.0, 20.0 ) );      // ratio 2.0, calibrated
        samples.addAll( samplesOf( "vocabulary_gap", 5, 10.0, 20.0 ) );  // below threshold

        final WeightCalibrator.CalibrationResult result = calibrator.calibrate( samples, 20, 0.5,
                Map.of( "agent_gap", 2.0, "vocabulary_gap", 0.05 ) );

        assertTrue( result.calibratedTypes().contains( "agent_gap" ) );
        assertFalse( result.calibratedTypes().contains( "vocabulary_gap" ) );
        assertEquals( 1, result.calibratedTypes().size() );
    }

    @Test
    void emptySamplesProducesEmptyResult() {
        final WeightCalibrator.CalibrationResult result =
                calibrator.calibrate( List.of(), 20, 0.5, Map.of( "agent_gap", 2.0 ) );

        assertTrue( result.calibratedTypes().isEmpty() );
        assertTrue( result.calibratedWeights().isEmpty() );
    }
}

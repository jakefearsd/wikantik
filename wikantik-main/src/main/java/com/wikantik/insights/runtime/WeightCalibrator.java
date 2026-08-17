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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-calibration arithmetic (content-intelligence design §7.4.4): once an opportunity type has
 * enough evaluated changes, compares predicted priority against realized click delta and nudges
 * that type's priority weight toward the observed ratio.
 *
 * <p><strong>Pure, no I/O.</strong> Like {@link com.wikantik.insights.OpportunityEngine}, this
 * class takes its inputs as parameters and returns a new result -- no clock reads, no database
 * access. {@link ContentOpportunityService} is the one caller that supplies the samples (from
 * {@code InsightsStore#calibrationSamples()}) and the configured defaults (from
 * {@link InsightsSettings}).</p>
 *
 * <p>Until a type clears {@code minVerdicts} evaluated samples, or its sample group's predicted
 * priorities sum to zero or less (a ratio would be meaningless), that type is left out of both
 * {@link CalibrationResult#calibratedTypes()} and {@link CalibrationResult#calibratedWeights()} --
 * callers must fall back to the configured default weight and report the type as
 * <em>uncalibrated</em>.</p>
 */
public final class WeightCalibrator {

    /** The weight can move at most 4x up or down from the configured default, in either direction. */
    private static final double CLAMP_FLOOR_FACTOR = 0.25;
    private static final double CLAMP_CEILING_FACTOR = 4.0;

    /**
     * @param calibratedTypes   opportunity types with at least {@code minVerdicts} evaluated
     *                          samples and a positive predicted-priority sum -- i.e. types whose
     *                          weight has actually been earned from evidence
     * @param calibratedWeights the recalibrated weight for each {@code calibratedTypes} entry;
     *                          carries no entry for an uncalibrated type
     */
    public record CalibrationResult( Set<String> calibratedTypes, Map<String, Double> calibratedWeights ) {
    }

    /**
     * @param samples          evaluated changes with both a predicted priority and a realized
     *                         click delta (design §7.4.2/§7.4.4)
     * @param minVerdicts      minimum sample count a type needs before its weight is recalibrated
     *                         ({@code wikantik.insights.calibration.min_verdicts}, design default
     *                         20)
     * @param damping          fraction of the way a weight moves from the configured default
     *                         toward the fully-observed ratio ({@code 0.0} = no movement,
     *                         {@code 1.0} = move all the way to the observed ratio;
     *                         {@code wikantik.insights.calibration.damping}, design default 0.5)
     * @param defaultWeightsByType each native opportunity type's configured default priority
     *                         weight -- the calibration midpoint and the base the clamp bounds are
     *                         computed from; a type absent from this map cannot be calibrated (there
     *                         is no default to damp around) and is skipped
     * @return which types were calibrated, and their new weight
     */
    public CalibrationResult calibrate( final List<CalibrationSample> samples, final int minVerdicts,
                                        final double damping, final Map<String, Double> defaultWeightsByType ) {
        final Map<String, List<CalibrationSample>> byType = new LinkedHashMap<>();
        for ( final CalibrationSample sample : samples ) {
            byType.computeIfAbsent( sample.opportunityType(), key -> new ArrayList<>() ).add( sample );
        }

        final Set<String> calibratedTypes = new LinkedHashSet<>();
        final Map<String, Double> calibratedWeights = new LinkedHashMap<>();

        for ( final Map.Entry<String, List<CalibrationSample>> entry : byType.entrySet() ) {
            final String type = entry.getKey();
            final List<CalibrationSample> group = entry.getValue();

            if ( group.size() < minVerdicts ) {
                continue; // below threshold: stays default, uncalibrated
            }

            final Double defaultWeight = defaultWeightsByType.get( type );
            if ( defaultWeight == null ) {
                continue; // no configured default to damp around -- can't calibrate
            }

            double predictedSum = 0.0;
            double realizedSum = 0.0;
            for ( final CalibrationSample sample : group ) {
                predictedSum += sample.predictedPriority();
                realizedSum += sample.realizedClickDelta();
            }

            if ( predictedSum <= 0.0 ) {
                continue; // a zero-or-negative predicted sum makes the ratio meaningless
            }

            final double ratio = realizedSum / predictedSum;
            final double newWeight = defaultWeight * ( 1 + damping * ( ratio - 1 ) );
            final double floor = defaultWeight * CLAMP_FLOOR_FACTOR;
            final double ceiling = defaultWeight * CLAMP_CEILING_FACTOR;
            final double clamped = Math.max( floor, Math.min( ceiling, newWeight ) );

            calibratedTypes.add( type );
            calibratedWeights.put( type, clamped );
        }

        return new CalibrationResult( calibratedTypes, calibratedWeights );
    }
}

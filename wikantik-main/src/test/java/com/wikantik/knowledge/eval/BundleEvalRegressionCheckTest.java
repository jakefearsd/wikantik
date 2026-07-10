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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleEvalRegressionCheckTest {

    private static BundleEvalReport report( final double overall, final double sim, final double rel, final double bnd ) {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, sim );
        recall.put( BundleCategory.RELATIONAL, rel );
        recall.put( BundleCategory.BOUNDARY, bnd );
        return new BundleEvalReport( overall, 0.0, recall, new EnumMap<>( BundleCategory.class ), 10 );
    }

    private static final BundleEvalThresholds FLOORS =
        new BundleEvalThresholds( 0.35, 0.30, 0.40, 0.45 );  // matches thresholds.properties seed

    @Test
    void allAboveFloors_noRegression() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.74, 0.70, 0.80, 0.72 ), FLOORS );
        assertFalse( res.regression() );
    }

    @Test
    void overallBelowFloor_isRegression_withDetail() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.30, 0.70, 0.80, 0.72 ), FLOORS );
        assertTrue( res.regression() );
        assertTrue( res.detail().contains( "OVERALL" ), "detail names the breached floor, was: " + res.detail() );
    }

    @Test
    void oneCategoryBelowFloor_isRegression() {
        // RELATIONAL 0.35 < 0.40 floor; others fine.
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.74, 0.70, 0.35, 0.72 ), FLOORS );
        assertTrue( res.regression() );
        assertTrue( res.detail().contains( "RELATIONAL" ) );
    }

    @Test
    void exactlyAtFloor_isNotRegression() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.35, 0.30, 0.40, 0.45 ), FLOORS );
        assertFalse( res.regression(), "at-floor is acceptable; only strictly-below regresses" );
    }

    @Test
    void thresholdsFromProperties_parseAndMissingIsZero() {
        final Properties p = new Properties();
        p.setProperty( "recall.OVERALL.min", "0.35" );
        p.setProperty( "recall.SIMILARITY.min", "0.30" );
        // RELATIONAL, BOUNDARY absent -> 0.0
        final BundleEvalThresholds t = BundleEvalThresholds.fromProperties( p );
        assertEquals( 0.35, t.overall(), 1e-9 );
        assertEquals( 0.30, t.similarity(), 1e-9 );
        assertEquals( 0.0, t.relational(), 1e-9 );
        assertEquals( 0.0, t.boundary(), 1e-9 );
    }
}

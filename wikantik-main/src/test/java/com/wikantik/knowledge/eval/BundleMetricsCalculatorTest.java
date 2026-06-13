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

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleMetricsCalculatorTest {

    private static BundleSection sec( String id, List<String> path, String text ) {
        return new BundleSection( id, path, text );
    }
    private static GoldSection gold( String id, List<String> path ) {
        return new GoldSection( id, path );
    }

    @Test
    void recall_counts_covered_gold_via_prefix_match() {
        final List<GoldSection> golds = List.of(
            gold( "01A", List.of( "Setup" ) ),
            gold( "01B", List.of( "Usage", "CLI" ) ) );
        final List<BundleSection> bundle = List.of(
            // covers 01A: same id, heading extends "Setup"
            sec( "01A", List.of( "Setup", "Prereqs" ), "..." ),
            // does NOT cover 01B: right id, wrong section
            sec( "01B", List.of( "Overview" ), "..." ) );
        assertEquals( 0.5, BundleMetricsCalculator.contextRecall( golds, bundle ), 1e-9 );
    }

    @Test
    void recall_is_zero_when_canonical_id_differs() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        final List<BundleSection> bundle = List.of( sec( "01Z", List.of( "Setup" ), "..." ) );
        assertEquals( 0.0, BundleMetricsCalculator.contextRecall( golds, bundle ), 1e-9 );
    }

    @Test
    void precisionAtK_is_gold_fraction_of_top_k_slots() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        final List<BundleSection> bundle = List.of(
            sec( "01A", List.of( "Setup" ), "gold" ),     // slot 1: gold
            sec( "01X", List.of( "Noise" ), "filler" ),   // slot 2: not
            sec( "01Y", List.of( "Noise" ), "filler" ) ); // slot 3: not
        // top-2: 1 gold of 2 slots = 0.5
        assertEquals( 0.5, BundleMetricsCalculator.contextPrecisionAtK( golds, bundle, 2 ), 1e-9 );
    }

    @Test
    void precisionAtK_uses_min_k_bundleSize_so_a_small_all_gold_bundle_scores_one() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        // One gold section, k=5: denominator is min(5,1)=1, not 5 — perfect precision.
        final List<BundleSection> tight = List.of( sec( "01A", List.of( "Setup" ), "gold" ) );
        assertEquals( 1.0, BundleMetricsCalculator.contextPrecisionAtK( golds, tight, 5 ), 1e-9 );
        // One non-gold section against k=5 is pure noise → 0.0.
        final List<BundleSection> noise = List.of( sec( "01Z", List.of( "Noise" ), "x" ) );
        assertEquals( 0.0, BundleMetricsCalculator.contextPrecisionAtK( golds, noise, 5 ), 1e-9 );
    }

    @Test
    void citationFaithfulness_passes_only_on_exact_hash_match() {
        final String body = "OntologyPageSync checks canonical_id liveness.";
        final String hash = BundleMetricsCalculator.sha256( body );
        assertTrue( BundleMetricsCalculator.citationFaithful( hash, body ) );
        assertFalse( BundleMetricsCalculator.citationFaithful( hash, body + " edited" ) );
    }

    @Test
    void empty_bundle_scores_zero_not_nan() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        assertEquals( 0.0, BundleMetricsCalculator.contextRecall( golds, List.of() ), 1e-9 );
        assertEquals( 0.0, BundleMetricsCalculator.contextPrecisionAtK( golds, List.of(), 5 ), 1e-9 );
    }
}

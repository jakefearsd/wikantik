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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HdbscanClustererTest {

    private final HdbscanClusterer clusterer = new HdbscanClusterer();

    /**
     * Two tight clusters in 2D plus one far outlier.
     * Each group has 6 points so Tribuo HDBSCAN can establish reliable core distances.
     * Contract: both groups share ONE non-noise label each, those labels are DISTINCT,
     * and the outlier is labelled -1 (noise).
     */
    @Test
    void cluster_findsTwoObviousGroups() {
        final float[][] vectors = {
            // Group A near (0, 0) — 6 points
            { 0.00f, 0.00f }, { 0.01f, 0.02f }, { 0.02f, 0.01f },
            { 0.00f, 0.03f }, { 0.01f, 0.00f }, { 0.02f, 0.02f },
            // Group B near (10, 10) — 6 points
            { 10.00f, 10.00f }, { 10.02f, 10.01f }, { 9.99f, 10.03f },
            { 10.01f, 9.98f },  { 9.98f, 10.02f },  { 10.03f, 9.99f },
            // Outlier far from both
            { 50.0f, 50.0f }
        };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );

        assertEquals( 13, labels.length );
        // Outlier must be noise.
        assertEquals( -1, labels[ 12 ], "Outlier must be noise (-1)" );

        // Group A (indices 0-5): all members share one non-noise label.
        final Set<Integer> groupA = new HashSet<>();
        for ( int i = 0; i < 6; i++ ) groupA.add( labels[ i ] );
        assertEquals( 1, groupA.size(), "Group A members should share one label" );
        assertNotEquals( -1, groupA.iterator().next(), "Group A must not be noise" );

        // Group B (indices 6-11): all members share one non-noise label.
        final Set<Integer> groupB = new HashSet<>();
        for ( int i = 6; i < 12; i++ ) groupB.add( labels[ i ] );
        assertEquals( 1, groupB.size(), "Group B members should share one label" );
        assertNotEquals( -1, groupB.iterator().next(), "Group B must not be noise" );

        // The two cluster labels must be distinct.
        assertNotEquals( groupA.iterator().next(), groupB.iterator().next(),
            "Group A and B must have distinct cluster labels" );
    }

    @Test
    void cluster_emptyInput_returnsEmpty() {
        final int[] labels = clusterer.cluster( new float[ 0 ][ 0 ], 3, 3 );
        assertEquals( 0, labels.length );
    }

    /**
     * When the number of input points is below minClusterSize the wrapper short-circuits
     * without invoking Tribuo and returns all noise, avoiding degenerate training.
     */
    @Test
    void cluster_belowMinClusterSize_allNoise() {
        final float[][] vectors = { { 0.0f, 0.0f }, { 0.01f, 0.01f } };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );
        assertEquals( 2, labels.length );
        assertEquals( -1, labels[ 0 ] );
        assertEquals( -1, labels[ 1 ] );
    }
}

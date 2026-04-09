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

class SmileHdbscanClustererTest {

    private final SmileHdbscanClusterer clusterer = new SmileHdbscanClusterer();

    @Test
    void cluster_findsTwoObviousGroups() {
        // Two tight groups in 2D space plus one outlier.
        final float[][] vectors = {
            // Group A near (0, 0)
            { 0.00f, 0.00f }, { 0.01f, 0.02f }, { 0.02f, 0.01f }, { 0.00f, 0.03f },
            // Group B near (10, 10)
            { 10.00f, 10.00f }, { 10.02f, 10.01f }, { 9.99f, 10.03f }, { 10.01f, 9.98f },
            // Outlier far from both
            { 50.0f, 50.0f }
        };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );

        assertEquals( 9, labels.length );
        // Outlier must be noise.
        assertEquals( -1, labels[ 8 ] );
        // Both groups must share a non-noise label internally and have distinct labels.
        final Set< Integer > groupA = new HashSet<>();
        for ( int i = 0; i < 4; i++ ) groupA.add( labels[ i ] );
        final Set< Integer > groupB = new HashSet<>();
        for ( int i = 4; i < 8; i++ ) groupB.add( labels[ i ] );
        assertEquals( 1, groupA.size(), "Group A members should share one label" );
        assertEquals( 1, groupB.size(), "Group B members should share one label" );
        assertNotEquals( groupA.iterator().next(), groupB.iterator().next(),
            "Group A and B must have distinct cluster labels" );
        assertNotEquals( -1, groupA.iterator().next(), "Group A must not be noise" );
        assertNotEquals( -1, groupB.iterator().next(), "Group B must not be noise" );
    }

    @Test
    void cluster_emptyInput_returnsEmpty() {
        final int[] labels = clusterer.cluster( new float[ 0 ][ 0 ], 3, 3 );
        assertEquals( 0, labels.length );
    }

    @Test
    void cluster_belowMinClusterSize_allNoise() {
        final float[][] vectors = { { 0.0f, 0.0f }, { 0.01f, 0.01f } };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );
        assertEquals( 2, labels.length );
        assertEquals( -1, labels[ 0 ] );
        assertEquals( -1, labels[ 1 ] );
    }
}

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
package com.wikantik.search.embedding.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CosineSimilarityTest {

    private static final double EPS = 1e-9;

    @Test
    void normMatchesKnownValue() {
        // sqrt(1 + 4 + 4) = 3
        assertEquals( 3.0, CosineSimilarity.norm( new float[] { 1f, 2f, 2f } ), EPS );
        assertEquals( 0.0, CosineSimilarity.norm( new float[] { 0f, 0f } ), EPS );
    }

    @Test
    void identicalVectorsReturnOne() {
        final float[] v = { 0.3f, -0.4f, 0.5f };
        assertEquals( 1.0, CosineSimilarity.cosine( v, v ), 1e-6 );
    }

    @Test
    void oppositeVectorsReturnNegativeOne() {
        final float[] a = { 1f, 2f, 3f };
        final float[] b = { -1f, -2f, -3f };
        assertEquals( -1.0, CosineSimilarity.cosine( a, b ), 1e-6 );
    }

    @Test
    void orthogonalVectorsReturnZero() {
        final float[] a = { 1f, 0f };
        final float[] b = { 0f, 1f };
        assertEquals( 0.0, CosineSimilarity.cosine( a, b ), EPS );
    }

    @Test
    void zeroVectorReturnsZero() {
        final float[] a = { 0f, 0f, 0f };
        final float[] b = { 1f, 2f, 3f };
        assertEquals( 0.0, CosineSimilarity.cosine( a, b ), EPS );
        assertEquals( 0.0, CosineSimilarity.cosine( b, a ), EPS );
    }

    @Test
    void preNormalizedVariantMatchesBasic() {
        final float[] a = { 0.2f, 0.9f, -0.3f, 0.4f };
        final float[] b = { -0.1f, 0.5f, 0.7f, 0.2f };
        final double expected = CosineSimilarity.cosine( a, b );
        final double actual = CosineSimilarity.cosine(
            a, CosineSimilarity.norm( a ), b, CosineSimilarity.norm( b ) );
        assertEquals( expected, actual, 1e-9 );
    }

    @Test
    void preNormalizedVariantZeroNormReturnsZero() {
        final float[] a = { 0f, 0f };
        final float[] b = { 1f, 1f };
        assertEquals( 0.0, CosineSimilarity.cosine(
            a, CosineSimilarity.norm( a ), b, CosineSimilarity.norm( b ) ), EPS );
    }

    @Test
    void rejectsDimensionMismatch() {
        assertThrows( IllegalArgumentException.class,
            () -> CosineSimilarity.cosine( new float[] { 1f, 2f }, new float[] { 1f, 2f, 3f } ) );
        assertThrows( IllegalArgumentException.class,
            () -> CosineSimilarity.cosine( new float[] { 1f, 2f }, 1.0,
                new float[] { 1f, 2f, 3f }, 1.0 ) );
    }
}

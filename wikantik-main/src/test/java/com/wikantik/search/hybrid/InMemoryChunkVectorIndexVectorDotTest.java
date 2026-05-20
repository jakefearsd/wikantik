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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-level parity tests for {@link InMemoryChunkVectorIndex#dotAt}. Verifies
 * the new Vector-API (SIMD) implementation produces results indistinguishable
 * from a straightforward scalar reference, both at the canonical 1024-dim
 * embedding size we ship and at off-by-one sizes that exercise the tail loop.
 *
 * <p>No PostgreSQL testcontainer needed — these tests exercise the static
 * dot-product hot path directly.</p>
 */
class InMemoryChunkVectorIndexVectorDotTest {

    /**
     * Reference scalar implementation kept in the test as the source of truth.
     * Matches the pre-Vector-API logic verbatim — including the {@code (double)}
     * accumulator — so a precision regression would surface here.
     */
    private static double scalarDot( final float[] flat, final int offset,
                                     final float[] queryVec, final int dim ) {
        double acc = 0.0;
        for ( int j = 0; j < dim; j++ ) {
            acc += (double) flat[ offset + j ] * (double) queryVec[ j ];
        }
        return acc;
    }

    @Test
    void zeroVectorsProduceZero() {
        final float[] flat = new float[ 64 ];
        final float[] q    = new float[ 64 ];
        assertEquals( 0.0, InMemoryChunkVectorIndex.dotAt( flat, 0, q, 64 ), 0.0 );
    }

    @Test
    void onesVectorsProduceDim() {
        final float[] flat = new float[ 64 ];
        final float[] q    = new float[ 64 ];
        java.util.Arrays.fill( flat, 1.0f );
        java.util.Arrays.fill( q,    1.0f );
        assertEquals( 64.0, InMemoryChunkVectorIndex.dotAt( flat, 0, q, 64 ), 1e-6 );
    }

    @Test
    void orthogonalVectorsProduceZero() {
        // Unit basis vectors in 8 dims: e0 and e1 → dot is zero.
        final float[] flat = new float[ 8 ];
        final float[] q    = new float[ 8 ];
        flat[ 0 ] = 1.0f;
        q[ 1 ]    = 1.0f;
        assertEquals( 0.0, InMemoryChunkVectorIndex.dotAt( flat, 0, q, 8 ), 0.0 );
    }

    @Test
    void parityWithScalarRefAtProductionDim1024() {
        // Production embedding dim. 1024 is divisible by 4/8/16, so the
        // tail loop runs zero iterations on every SPECIES_PREFERRED.
        final int dim = 1024;
        final Random rng = new Random( 42 );
        final float[] flat = randomVec( rng, dim );
        final float[] q    = randomVec( rng, dim );

        final double scalar = scalarDot( flat, 0, q, dim );
        final double vector = InMemoryChunkVectorIndex.dotAt( flat, 0, q, dim );

        // Float-precision tolerance. The two implementations differ in
        // accumulator order (double accumulator vs float-vector lane sum)
        // so bit-exact equality is not expected; agreement to ~1e-3 in
        // absolute terms (or ~1e-6 relative) is comfortably tight.
        final double relative = Math.abs( ( vector - scalar ) / scalar );
        assertEquals( scalar, vector, Math.max( 1e-3, Math.abs( scalar ) * 1e-5 ),
            "vector and scalar dot products must agree within float precision (relative: " + relative + ")" );
    }

    @Test
    void parityAtOffsetWithinFlatBuffer() {
        // Simulates the real call from topKChunks: dotAt with offset = i * dim.
        final int dim     = 1024;
        final int nRows   = 3;
        final int rowIdx  = 2;
        final Random rng = new Random( 7 );
        final float[] flat = randomVec( rng, nRows * dim );
        final float[] q    = randomVec( rng, dim );

        final double scalar = scalarDot( flat, rowIdx * dim, q, dim );
        final double vector = InMemoryChunkVectorIndex.dotAt( flat, rowIdx * dim, q, dim );

        assertEquals( scalar, vector, Math.max( 1e-3, Math.abs( scalar ) * 1e-5 ) );
    }

    @Test
    void parityAtTailExercisingDim1023() {
        // dim = 1023 exercises the tail loop for any SPECIES_PREFERRED whose
        // length divides 1024 (i.e. all of them — 4, 8, 16). The remainder
        // ranges from 1 to 15 elements depending on the host CPU's SIMD width.
        final int dim = 1023;
        final Random rng = new Random( 100 );
        final float[] flat = randomVec( rng, dim );
        final float[] q    = randomVec( rng, dim );

        final double scalar = scalarDot( flat, 0, q, dim );
        final double vector = InMemoryChunkVectorIndex.dotAt( flat, 0, q, dim );

        assertEquals( scalar, vector, Math.max( 1e-3, Math.abs( scalar ) * 1e-5 ) );
    }

    @Test
    void parityAtTinyDim7() {
        // Smaller than any plausible SPECIES.length() (commonly 4/8/16), so the
        // upperBound for the vector loop is 0 or 4 and the tail handles most/all.
        // Bit-exact match expected here because the vector loop iterates at most
        // once and the tail completes in scalar float.
        final int dim = 7;
        final float[] flat = { 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f };
        final float[] q    = { 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f };
        final double scalar = scalarDot( flat, 0, q, dim );
        final double vector = InMemoryChunkVectorIndex.dotAt( flat, 0, q, dim );
        assertEquals( scalar, vector, 1e-5 );
    }

    private static float[] randomVec( final Random rng, final int n ) {
        final float[] v = new float[ n ];
        for ( int i = 0; i < n; i++ ) {
            // Gaussian-ish values close to a real embedding distribution.
            v[ i ] = (float) ( rng.nextGaussian() * 0.5 );
        }
        return v;
    }
}

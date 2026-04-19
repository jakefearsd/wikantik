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

/** Brute-force cosine similarity. Fine for ~10K-vector sandbox corpora. */
public final class CosineSimilarity {

    private CosineSimilarity() {}

    /** Returns the pre-computed L2 norm of a vector. */
    public static double norm( final float[] v ) {
        double sum = 0;
        for( final float f : v ) sum += (double) f * f;
        return Math.sqrt( sum );
    }

    /** Cosine similarity in [-1, 1]. Returns 0 for zero-length vectors. */
    public static double cosine( final float[] a, final float[] b ) {
        if( a.length != b.length ) {
            throw new IllegalArgumentException( "dim mismatch: " + a.length + " vs " + b.length );
        }
        double dot = 0, na = 0, nb = 0;
        for( int i = 0; i < a.length; i++ ) {
            dot += (double) a[ i ] * b[ i ];
            na  += (double) a[ i ] * a[ i ];
            nb  += (double) b[ i ] * b[ i ];
        }
        if( na == 0 || nb == 0 ) return 0;
        return dot / ( Math.sqrt( na ) * Math.sqrt( nb ) );
    }

    /**
     * Faster variant when the corpus side's norm has been pre-computed.
     * Returns 0 for zero-length inputs.
     */
    public static double cosine( final float[] a, final double normA,
                                 final float[] b, final double normB ) {
        if( a.length != b.length ) {
            throw new IllegalArgumentException( "dim mismatch: " + a.length + " vs " + b.length );
        }
        if( normA == 0 || normB == 0 ) return 0;
        double dot = 0;
        for( int i = 0; i < a.length; i++ ) dot += (double) a[ i ] * b[ i ];
        return dot / ( normA * normB );
    }
}

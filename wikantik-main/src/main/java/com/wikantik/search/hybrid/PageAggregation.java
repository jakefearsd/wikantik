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

import java.util.List;

/**
 * Strategy for collapsing a page's per-chunk cosine scores into a single
 * page-level score. Input is required to be sorted descending.
 */
public enum PageAggregation {

    MAX {
        @Override
        public double aggregate( final List< Double > chunkScoresDescending ) {
            requireNonEmpty( chunkScoresDescending );
            return chunkScoresDescending.get( 0 );
        }
    },
    MEAN_TOP_3 {
        @Override
        public double aggregate( final List< Double > chunkScoresDescending ) {
            requireNonEmpty( chunkScoresDescending );
            return meanTop( chunkScoresDescending, 3 );
        }
    },
    SUM_TOP_3 {
        @Override
        public double aggregate( final List< Double > chunkScoresDescending ) {
            requireNonEmpty( chunkScoresDescending );
            return sumTop( chunkScoresDescending, 3 );
        }
    },
    SUM_TOP_5 {
        @Override
        public double aggregate( final List< Double > chunkScoresDescending ) {
            requireNonEmpty( chunkScoresDescending );
            return sumTop( chunkScoresDescending, 5 );
        }
    };

    public abstract double aggregate( List< Double > chunkScoresDescending );

    private static void requireNonEmpty( final List< Double > scores ) {
        if( scores == null || scores.isEmpty() ) {
            throw new IllegalArgumentException( "chunkScoresDescending must not be null or empty" );
        }
    }

    private static double meanTop( final List< Double > s, final int k ) {
        final int kk = Math.min( k, s.size() );
        double sum = 0;
        for( int i = 0; i < kk; i++ ) sum += s.get( i );
        return sum / kk;
    }

    private static double sumTop( final List< Double > s, final int k ) {
        final int kk = Math.min( k, s.size() );
        double sum = 0;
        for( int i = 0; i < kk; i++ ) sum += s.get( i );
        return sum;
    }
}

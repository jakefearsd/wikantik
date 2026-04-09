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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.clustering.Clustering;
import smile.clustering.DBSCAN;

import java.util.Arrays;

/**
 * Thin wrapper around Smile's density-based clusterer that exposes HDBSCAN-like semantics.
 *
 * <p>Smile 4.x removed HDBSCAN; this wrapper uses {@link DBSCAN} internally with an
 * automatically estimated epsilon (the median k-NN distance over the dataset, where k = minPts).
 * This approximates HDBSCAN's core-distance concept while staying entirely within the
 * available Smile 4.3.0 API. All Smile internals are isolated here so any future API
 * change surfaces in a single file.</p>
 *
 * <h3>Noise label convention</h3>
 * <p>Points that are not part of any cluster are returned with label {@code -1}. Smile
 * internally uses {@link Clustering#OUTLIER} ({@code Integer.MAX_VALUE}) which this
 * wrapper translates to {@code -1} before returning.</p>
 *
 * <h3>Input assumption</h3>
 * <p>Vectors are expected to be L2-normalized (unit vectors), as emitted by
 * {@link TfidfModel}. L2-normalization ensures that Euclidean distance is a faithful
 * proxy for cosine similarity and avoids scale bias across dimensions.</p>
 */
public class SmileHdbscanClusterer {

    private static final Logger LOG = LoggerFactory.getLogger( SmileHdbscanClusterer.class );

    /**
     * Clusters the supplied vectors using density-based clustering.
     *
     * @param vectors        L2-normalized float vectors, one per point; may be empty
     * @param minClusterSize minimum number of points required to form a cluster; points in
     *                       groups smaller than this threshold are classified as noise ({@code -1})
     * @param minPts         minimum points required within epsilon radius for a point to be
     *                       a core point; also used as k for epsilon estimation
     * @return per-point cluster labels, noise points labelled {@code -1}, in the same order
     *         as the input {@code vectors} array
     */
    public int[] cluster( final float[][] vectors, final int minClusterSize, final int minPts ) {
        if ( vectors.length == 0 ) {
            return new int[ 0 ];
        }

        final double[][] doubles = toDoubles( vectors );
        final double epsilon = estimateEpsilon( doubles, minPts );

        LOG.debug( "DBSCAN clustering {} points with epsilon={} minPoints={}", doubles.length, epsilon, minPts );

        final DBSCAN<double[]> model = DBSCAN.fit( doubles, minPts, epsilon );
        final int[] raw = model.group();

        // Translate Smile's OUTLIER sentinel (Integer.MAX_VALUE) to the -1 noise convention,
        // and also apply the minClusterSize filter: any cluster with fewer members than the
        // threshold is demoted to noise.
        return applyMinClusterSizeAndNormalize( raw, minClusterSize );
    }

    // ---- private helpers ---------------------------------------------------

    private static double[][] toDoubles( final float[][] vectors ) {
        final double[][] result = new double[ vectors.length ][];
        for ( int i = 0; i < vectors.length; i++ ) {
            final float[] src = vectors[ i ];
            final double[] dst = new double[ src.length ];
            for ( int j = 0; j < src.length; j++ ) {
                dst[ j ] = src[ j ];
            }
            result[ i ] = dst;
        }
        return result;
    }

    /**
     * Estimates a suitable epsilon by computing the k-NN distance for every point and
     * returning the median. Using the median (rather than mean) keeps the estimate
     * robust to the outlier points we explicitly want to exclude.
     */
    private static double estimateEpsilon( final double[][] data, final int k ) {
        final int n = data.length;
        final int effectiveK = Math.min( k, n - 1 );
        if ( effectiveK <= 0 ) {
            // Only one point — any positive epsilon works.
            return 1.0;
        }

        final double[] knnDistances = new double[ n ];
        for ( int i = 0; i < n; i++ ) {
            knnDistances[ i ] = kthNearestDistance( data, i, effectiveK );
        }
        Arrays.sort( knnDistances );
        // Median of k-NN distances.
        return knnDistances[ n / 2 ];
    }

    private static double kthNearestDistance( final double[][] data, final int idx, final int k ) {
        final double[] target = data[ idx ];
        final double[] distances = new double[ data.length - 1 ];
        int pos = 0;
        for ( int i = 0; i < data.length; i++ ) {
            if ( i != idx ) {
                distances[ pos++ ] = euclidean( target, data[ i ] );
            }
        }
        Arrays.sort( distances );
        return distances[ k - 1 ];
    }

    private static double euclidean( final double[] a, final double[] b ) {
        double sum = 0.0;
        for ( int i = 0; i < a.length; i++ ) {
            final double d = a[ i ] - b[ i ];
            sum += d * d;
        }
        return Math.sqrt( sum );
    }

    /**
     * Translates Smile's {@code OUTLIER} ({@code Integer.MAX_VALUE}) to {@code -1}, and
     * demotes any cluster whose total membership falls below {@code minClusterSize} to noise.
     */
    private static int[] applyMinClusterSizeAndNormalize( final int[] raw, final int minClusterSize ) {
        // Count members per cluster (ignore OUTLIER sentinel).
        int maxLabel = -1;
        for ( final int label : raw ) {
            if ( label != Clustering.OUTLIER && label > maxLabel ) {
                maxLabel = label;
        }
        }

        final int[] counts = new int[ maxLabel + 1 ];
        for ( final int label : raw ) {
            if ( label != Clustering.OUTLIER ) {
                counts[ label ]++;
            }
        }

        final int[] result = new int[ raw.length ];
        for ( int i = 0; i < raw.length; i++ ) {
            final int label = raw[ i ];
            if ( label == Clustering.OUTLIER ) {
                result[ i ] = -1;
            } else if ( counts[ label ] < minClusterSize ) {
                result[ i ] = -1;
            } else {
                result[ i ] = label;
            }
        }
        return result;
    }
}

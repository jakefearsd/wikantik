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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tribuo.MutableDataset;
import org.tribuo.clustering.ClusterID;
import org.tribuo.clustering.ClusteringFactory;
import org.tribuo.clustering.hdbscan.HdbscanModel;
import org.tribuo.clustering.hdbscan.HdbscanTrainer;
import org.tribuo.datasource.ListDataSource;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around Oracle Tribuo's HDBSCAN implementation that exposes a minimal
 * clustering interface, isolating all Tribuo-specific types behind a single class so
 * that future library upgrades surface in one place.
 *
 * <h3>Noise label convention</h3>
 * <p>Points that do not belong to any cluster are returned with label {@code -1}.
 * Tribuo internally labels noise points with {@code 0}
 * ({@code HdbscanTrainer.OUTLIER_NOISE_CLUSTER_LABEL}); this wrapper translates
 * {@code 0 → -1} before returning. All genuine cluster labels ({@code 1..N}) are
 * returned as-is.</p>
 *
 * <h3>Input assumption</h3>
 * <p>Vectors are expected to be L2-normalized (unit vectors), as emitted by
 * {@link TfidfModel}. L2-normalization ensures that Euclidean distance is a faithful
 * proxy for cosine similarity and avoids scale bias across dimensions.</p>
 *
 * <h3>Short-circuit behaviour</h3>
 * <ul>
 *   <li>Empty input ({@code vectors.length == 0}): returns {@code new int[0]}.</li>
 *   <li>Fewer points than {@code minClusterSize}: returns an all-{@code -1} array
 *       without invoking Tribuo, avoiding potential crashes on degenerate inputs.</li>
 * </ul>
 */
public class HdbscanClusterer {

    private static final Logger LOG = LogManager.getLogger( HdbscanClusterer.class );

    /** Feature name prefix; Tribuo requires unique, stable names per dimension. */
    private static final String FEATURE_PREFIX = "f";

    /**
     * Clusters the supplied vectors using Tribuo's HDBSCAN algorithm.
     *
     * @param vectors        L2-normalized float vectors, one per point; may be empty
     * @param minClusterSize minimum number of points required to form a cluster
     * @param minPts         HDBSCAN core-distance k parameter, forwarded directly to
     *                       Tribuo's HdbscanTrainer as the k argument
     * @return per-point cluster labels in the same order as the input {@code vectors}
     *         array; noise points are labelled {@code -1}
     */
    public int[] cluster( final float[][] vectors, final int minClusterSize, final int minPts ) {
        if ( vectors.length == 0 ) {
            return new int[ 0 ];
        }
        if ( vectors.length < minClusterSize ) {
            LOG.debug( "HDBSCAN: {} points is below minClusterSize {}; returning all noise",
                vectors.length, minClusterSize );
            final int[] noise = new int[ vectors.length ];
            java.util.Arrays.fill( noise, -1 );
            return noise;
        }

        LOG.debug( "HDBSCAN clustering {} points with minClusterSize={}", vectors.length, minClusterSize );

        final ClusteringFactory factory = new ClusteringFactory();
        final List<ArrayExample<ClusterID>> examples = buildExamples( vectors, factory );

        final SimpleDataSourceProvenance provenance =
            new SimpleDataSourceProvenance( "HdbscanClusterer", factory );
        final List<org.tribuo.Example<ClusterID>> exampleList = new ArrayList<>( examples );
        final ListDataSource<ClusterID> source =
            new ListDataSource<>( exampleList, factory, provenance );
        final MutableDataset<ClusterID> dataset = new MutableDataset<>( source );

        final HdbscanTrainer trainer = new HdbscanTrainer(
            minClusterSize, HdbscanTrainer.Distance.EUCLIDEAN, minPts, 1 );

        final HdbscanModel model;
        try {
            model = trainer.train( dataset );
        } catch ( final RuntimeException e ) {
            // Training failures must surface a full stack trace so an operator can diagnose
            // a Tribuo bug or unexpected input; the caller rethrows so no silent swallowing.
            // LOG.error justified: unexpected Tribuo training failure, diagnostics required
            LOG.error( "HDBSCAN training failed: {} vectors, dim={}, minClusterSize={}, minPts={}",
                vectors.length, vectors[ 0 ].length, minClusterSize, minPts, e );
            throw e;
        }

        final List<Integer> rawLabels = model.getClusterLabels();
        return translateLabels( rawLabels );
    }

    // ---- private helpers ---------------------------------------------------

    /**
     * Converts the float vectors into Tribuo {@link ArrayExample} instances.
     * Feature names are {@code "f0", "f1", ..., "fN"} — they just need to be
     * unique and stable per dimension.
     */
    private static List<ArrayExample<ClusterID>> buildExamples(
            final float[][] vectors, final ClusteringFactory factory ) {

        final int dims = vectors[ 0 ].length;
        final String[] names = new String[ dims ];
        for ( int j = 0; j < dims; j++ ) {
            names[ j ] = FEATURE_PREFIX + j;
        }

        final ClusterID placeholder = factory.getUnknownOutput();
        final List<ArrayExample<ClusterID>> examples = new ArrayList<>( vectors.length );
        for ( final float[] vec : vectors ) {
            final double[] values = new double[ dims ];
            for ( int j = 0; j < dims; j++ ) {
                values[ j ] = vec[ j ];
            }
            examples.add( new ArrayExample<>( placeholder, names, values ) );
        }
        return examples;
    }

    /**
     * Translates Tribuo's noise sentinel {@code 0} to the project-wide {@code -1} convention.
     * {@code HdbscanTrainer.OUTLIER_NOISE_CLUSTER_LABEL} is package-private but its value
     * is {@code 0} (verified against the compiled constant). Genuine cluster labels
     * ({@code 1..N}) are preserved as-is.
     */
    private static int[] translateLabels( final List<Integer> rawLabels ) {
        final int[] result = new int[ rawLabels.size() ];
        for ( int i = 0; i < rawLabels.size(); i++ ) {
            final int label = rawLabels.get( i );
            // Tribuo noise sentinel is 0 (HdbscanTrainer.OUTLIER_NOISE_CLUSTER_LABEL = 0).
            result[ i ] = ( label == 0 ) ? -1 : label;
        }
        return result;
    }
}

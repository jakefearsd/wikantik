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

import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Supplier;

/**
 * Batch service that discovers candidate Hub topics from orphan articles using HDBSCAN clustering.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Load the candidate pool — all article-typed KG nodes that are <em>not</em> already targets
 *       of a {@code related} edge from any hub-typed node.</li>
 *   <li>Retrieve TF-IDF vectors for each candidate from the content model.</li>
 *   <li>Run HDBSCAN over those vectors ({@code minClusterSize}, {@code minPts} from builder).</li>
 *   <li>For each non-noise cluster, compute a normalised centroid, identify the exemplar page
 *       (the member whose vector has the highest dot-product with the centroid), and score the
 *       cluster coherence as the mean dot-product of all member vectors with the centroid.</li>
 *   <li>Insert one {@link HubDiscoveryRepository} row per discovered cluster.</li>
 * </ol>
 *
 * <p>Short-circuit conditions (logged at INFO, return zero-filled {@link RunSummary}):
 * <ul>
 *   <li>Content model is null or has 0 entities.</li>
 *   <li>Candidate pool is smaller than {@code minCandidatePool}.</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to construct — production callers should pass a
 * {@link #contentModelSupplier(Supplier)} so retrains are picked up without re-wiring the service.
 */
public class HubDiscoveryService {

    private static final Logger LOG = LogManager.getLogger( HubDiscoveryService.class );

    public static final String PROP_MIN_CLUSTER_SIZE   = "wikantik.hubDiscovery.minClusterSize";
    public static final String PROP_MIN_PTS            = "wikantik.hubDiscovery.minPts";
    public static final String PROP_MIN_CANDIDATE_POOL = "wikantik.hubDiscovery.minCandidatePool";

    static final int DEFAULT_MIN_CLUSTER_SIZE   = 3;
    static final int DEFAULT_MIN_PTS            = 3;
    static final int DEFAULT_MIN_CANDIDATE_POOL = 6;

    private final JdbcKnowledgeRepository kgRepo;
    private final HubDiscoveryRepository  discoveryRepo;
    private final ContentEmbeddingRepository contentRepo;
    private final HdbscanClusterer        clusterer;
    private final int                     minClusterSize;
    private final int                     minPts;
    private final int                     minCandidatePool;
    private final Supplier< TfidfModel >  contentModelSupplier;
    // Optional — only required when acceptProposal / dismissProposal are called.
    private final PageManager             pageManager;
    private final PageSaveHelper          pageSaveHelper;

    private HubDiscoveryService( final Builder b ) {
        this.kgRepo               = b.kgRepo;
        this.discoveryRepo        = b.discoveryRepo;
        this.contentRepo          = b.contentRepo;
        this.clusterer            = b.clusterer != null ? b.clusterer : new HdbscanClusterer();
        this.minClusterSize       = b.minClusterSize   != null ? b.minClusterSize   : DEFAULT_MIN_CLUSTER_SIZE;
        this.minPts               = b.minPts           != null ? b.minPts           : DEFAULT_MIN_PTS;
        this.minCandidatePool     = b.minCandidatePool != null ? b.minCandidatePool : DEFAULT_MIN_CANDIDATE_POOL;
        this.contentModelSupplier = b.contentModelSupplier;
        this.pageManager          = b.pageManager;
        this.pageSaveHelper       = b.pageSaveHelper;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Public result records ----

    /** Result of accepting a single cluster proposal and creating a new Hub page. */
    public record AcceptResult( String createdPage, int memberCount ) {}

    /** Summary returned by {@link #runDiscovery()}. */
    public record RunSummary( int proposalsCreated, int candidatePoolSize,
                               int noisePages, long durationMs ) {}

    // ---- Public API ----

    /**
     * Convenience wrapper over {@link #runDiscovery()} returning only the created-proposal count.
     *
     * @return number of cluster proposals written to {@link HubDiscoveryRepository}
     */
    public int generateClusterProposals() {
        return runDiscovery().proposalsCreated();
    }

    /**
     * Runs the full discovery pipeline.
     *
     * @return {@link RunSummary} describing the run
     * @throws HubDiscoveryException if an unexpected runtime failure occurs
     */
    public RunSummary runDiscovery() {
        LOG.info( "Hub discovery: starting (minClusterSize={}, minPts={}, minCandidatePool={})",
            minClusterSize, minPts, minCandidatePool );
        final long start = System.currentTimeMillis();

        final TfidfModel model = resolveModel();
        if ( model == null ) {
            return new RunSummary( 0, 0, 0, elapsed( start ) );
        }

        try {
            // Step 1 — candidate pool
            final List< String > candidates = loadCandidatePool( model );
            if ( candidates.size() < minCandidatePool ) {
                LOG.info( "Hub discovery: short-circuit — candidate pool size {} < minCandidatePool {}",
                    candidates.size(), minCandidatePool );
                return new RunSummary( 0, candidates.size(), 0, elapsed( start ) );
            }
            LOG.info( "Hub discovery step 1: {} candidates in pool", candidates.size() );

            // Step 2 — vectors
            final float[][] vectors = buildVectorMatrix( model, candidates );

            // Step 3 — HDBSCAN
            final int[] labels = clusterer.cluster( vectors, minClusterSize, minPts );

            // Step 4 — group by cluster id, ignoring noise (label == -1)
            final Map< Integer, List< Integer > > clusterMap = groupByLabel( labels );
            LOG.info( "Hub discovery step 3: {} non-noise clusters from {} candidates",
                clusterMap.size(), candidates.size() );

            // Count noise pages (label == -1)
            int noiseCount = 0;
            for ( final int label : labels ) {
                if ( label == -1 ) noiseCount++;
            }

            // Step 5 — persist one proposal per cluster
            int created = 0;
            for ( final Map.Entry< Integer, List< Integer > > entry : clusterMap.entrySet() ) {
                final List< Integer > memberIndices = entry.getValue();
                final List< String > memberNames = new ArrayList<>( memberIndices.size() );
                for ( final int idx : memberIndices ) {
                    memberNames.add( candidates.get( idx ) );
                }

                final float[][] clusterVectors = new float[ memberIndices.size() ][];
                for ( int i = 0; i < memberIndices.size(); i++ ) {
                    clusterVectors[ i ] = vectors[ memberIndices.get( i ) ];
                }

                final float[] centroid    = normalizedCentroid( clusterVectors );
                final int     exemplarIdx = argmaxDot( clusterVectors, centroid );
                final double  coherence   = meanDot( clusterVectors, centroid );
                final String  exemplar    = memberNames.get( exemplarIdx );
                // Use exemplar as the suggested name placeholder; Task 10 will refine naming.
                final String  suggestedName = exemplar + " Hub";

                discoveryRepo.insert( suggestedName, exemplar, memberNames, coherence );
                created++;
                LOG.info( "Hub discovery: wrote proposal '{}' with {} members, coherence={:.4f}",
                    suggestedName, memberNames.size(), coherence );
            }

            final long duration = elapsed( start );
            LOG.info( "Hub discovery complete in {}ms: {} proposals created, {} noise pages",
                duration, created, noiseCount );
            return new RunSummary( created, candidates.size(), noiseCount, duration );

        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered batch failure — stack trace must land in the
            // server log because the REST caller only sees a 500 message.
            LOG.error( "Hub discovery generation failed with exception (minClusterSize={}, minPts={})",
                minClusterSize, minPts, e );
            throw new HubDiscoveryException( "Hub discovery generation failed: " + e.getMessage(), e );
        }
    }

    /**
     * Accept a cluster proposal and create a new Hub wiki page.
     * <p><b>Not yet implemented — wired in Task 10.</b>
     *
     * @throws UnsupportedOperationException always, until Task 10
     */
    public AcceptResult acceptProposal( final int proposalId, final String hubName ) {
        throw new UnsupportedOperationException( "implemented in Task 10" );
    }

    /**
     * Dismiss a cluster proposal (delete it from the review queue without creating a page).
     * <p><b>Not yet implemented — wired in Task 10.</b>
     *
     * @throws UnsupportedOperationException always, until Task 10
     */
    public void dismissProposal( final int proposalId ) {
        throw new UnsupportedOperationException( "implemented in Task 10" );
    }

    // ---- Private helpers ----

    /** Resolves the content model; logs and returns null when not ready. */
    private TfidfModel resolveModel() {
        final TfidfModel model = contentModelSupplier.get();
        if ( model == null ) {
            LOG.info( "Hub discovery: skipped — content model supplier returned null" );
            return null;
        }
        if ( model.getEntityCount() == 0 ) {
            LOG.info( "Hub discovery: skipped — content model has 0 entities (not trained yet)" );
            return null;
        }
        LOG.info( "Hub discovery: content model has {} entities, dimension {}",
            model.getEntityCount(), model.getDimension() );
        return model;
    }

    /**
     * Returns names of article-typed KG nodes that are NOT already targets of a {@code related}
     * edge from any hub-typed node, AND that are present in the content model.
     */
    private List< String > loadCandidatePool( final TfidfModel model ) {
        // Load all node names that are targets of 'related' edges (i.e., existing hub members).
        final List< Map< String, Object > > relatedEdges =
            kgRepo.queryEdgesWithNames( "related", null, 100_000, 0 );

        // Identify hub node names (source_name where the source is hub-typed)
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgRepo.queryNodes( null, null, 100_000, 0 );
        final Set< String > hubNames = new HashSet<>();
        for ( final com.wikantik.api.knowledge.KgNode node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubNames.add( node.name() );
            }
        }

        // Collect existing hub-member names (targets of 'related' edges FROM hub-typed nodes).
        final Set< String > hubMemberNames = new HashSet<>();
        for ( final Map< String, Object > edge : relatedEdges ) {
            final String sourceName = (String) edge.get( "source_name" );
            if ( sourceName != null && hubNames.contains( sourceName ) ) {
                final String targetName = (String) edge.get( "target_name" );
                if ( targetName != null ) hubMemberNames.add( targetName );
            }
        }

        // Candidate = entity in model, NOT a hub, NOT already a hub member
        final List< String > candidates = new ArrayList<>();
        for ( final String name : model.getEntityNames() ) {
            if ( hubNames.contains( name ) ) continue;
            if ( hubMemberNames.contains( name ) ) continue;
            candidates.add( name );
        }
        LOG.info( "Hub discovery loadCandidatePool: {} hub members excluded, {} candidates",
            hubMemberNames.size(), candidates.size() );
        return candidates;
    }

    /** Builds the vector matrix for the given candidate names, in the same order. */
    private float[][] buildVectorMatrix( final TfidfModel model, final List< String > candidates ) {
        final float[][] matrix = new float[ candidates.size() ][];
        for ( int i = 0; i < candidates.size(); i++ ) {
            final int entityId = model.entityId( candidates.get( i ) );
            matrix[ i ] = entityId >= 0 ? model.getVector( entityId ) : new float[ model.getDimension() ];
        }
        return matrix;
    }

    /** Groups row indices by their HDBSCAN label, omitting noise (label == -1). */
    private static Map< Integer, List< Integer > > groupByLabel( final int[] labels ) {
        final Map< Integer, List< Integer > > map = new LinkedHashMap<>();
        for ( int i = 0; i < labels.length; i++ ) {
            if ( labels[ i ] == -1 ) continue;
            map.computeIfAbsent( labels[ i ], k -> new ArrayList<>() ).add( i );
        }
        return map;
    }

    /**
     * Computes the normalised centroid of the given row vectors.
     * Sums all rows, then divides by the L2 norm of the sum.
     */
    static float[] normalizedCentroid( final float[][] rows ) {
        final int dim = rows[ 0 ].length;
        final float[] centroid = new float[ dim ];
        for ( final float[] row : rows ) {
            for ( int d = 0; d < dim; d++ ) centroid[ d ] += row[ d ];
        }
        double norm = 0;
        for ( final float v : centroid ) norm += ( double ) v * v;
        if ( norm > 0 ) {
            norm = Math.sqrt( norm );
            for ( int d = 0; d < dim; d++ ) centroid[ d ] /= ( float ) norm;
        }
        return centroid;
    }

    /**
     * Returns the row index with the maximum dot product to the centroid.
     */
    static int argmaxDot( final float[][] rows, final float[] centroid ) {
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for ( int i = 0; i < rows.length; i++ ) {
            double dot = 0;
            for ( int d = 0; d < centroid.length; d++ ) dot += rows[ i ][ d ] * centroid[ d ];
            if ( dot > bestScore ) { bestScore = dot; best = i; }
        }
        return best;
    }

    /**
     * Returns the mean dot product of all rows with the centroid (cluster coherence score).
     */
    static double meanDot( final float[][] rows, final float[] centroid ) {
        double sum = 0;
        for ( final float[] row : rows ) {
            for ( int d = 0; d < centroid.length; d++ ) sum += row[ d ] * centroid[ d ];
        }
        return sum / rows.length;
    }

    private static long elapsed( final long startMs ) {
        return System.currentTimeMillis() - startMs;
    }

    // ---- Builder ----

    /**
     * Fluent builder for {@link HubDiscoveryService}.
     *
     * <p>Required: {@code kgRepo}, {@code discoveryRepo}, {@code contentRepo}, and a content model
     * (via {@link #contentModel(TfidfModel)} or {@link #contentModelSupplier(Supplier)}).
     *
     * <p>Optional: {@code clusterer}, {@code minClusterSize}, {@code minPts},
     * {@code minCandidatePool}, {@code pageManager}, {@code pageSaveHelper}.
     * {@code pageManager} and {@code pageSaveHelper} are only required when
     * {@link HubDiscoveryService#acceptProposal(int, String)} is called (Task 10).
     */
    public static final class Builder {
        private JdbcKnowledgeRepository  kgRepo;
        private HubDiscoveryRepository   discoveryRepo;
        private ContentEmbeddingRepository contentRepo;
        private HdbscanClusterer         clusterer;
        private Integer                  minClusterSize;
        private Integer                  minPts;
        private Integer                  minCandidatePool;
        private Supplier< TfidfModel >   contentModelSupplier;
        private PageManager              pageManager;
        private PageSaveHelper           pageSaveHelper;

        private Builder() {}

        public Builder kgRepo( final JdbcKnowledgeRepository kgRepo ) {
            this.kgRepo = kgRepo; return this;
        }
        public Builder discoveryRepo( final HubDiscoveryRepository discoveryRepo ) {
            this.discoveryRepo = discoveryRepo; return this;
        }
        public Builder contentRepo( final ContentEmbeddingRepository contentRepo ) {
            this.contentRepo = contentRepo; return this;
        }
        public Builder clusterer( final HdbscanClusterer clusterer ) {
            this.clusterer = clusterer; return this;
        }
        public Builder minClusterSize( final int minClusterSize ) {
            this.minClusterSize = minClusterSize; return this;
        }
        public Builder minPts( final int minPts ) {
            this.minPts = minPts; return this;
        }
        public Builder minCandidatePool( final int minCandidatePool ) {
            this.minCandidatePool = minCandidatePool; return this;
        }
        /** Wraps a fixed model in a constant supplier — useful for tests. */
        public Builder contentModel( final TfidfModel contentModel ) {
            this.contentModelSupplier = () -> contentModel; return this;
        }
        /** Production use — called fresh each run so retrains are picked up. */
        public Builder contentModelSupplier( final Supplier< TfidfModel > supplier ) {
            this.contentModelSupplier = supplier; return this;
        }
        public Builder pageManager( final PageManager pageManager ) {
            this.pageManager = pageManager; return this;
        }
        public Builder pageSaveHelper( final PageSaveHelper pageSaveHelper ) {
            this.pageSaveHelper = pageSaveHelper; return this;
        }
        /** Reads known properties from {@code props}, falling back to defaults. */
        public Builder propsFrom( final Properties props ) {
            this.minClusterSize = Integer.parseInt(
                props.getProperty( PROP_MIN_CLUSTER_SIZE, String.valueOf( DEFAULT_MIN_CLUSTER_SIZE ) ) );
            this.minPts = Integer.parseInt(
                props.getProperty( PROP_MIN_PTS, String.valueOf( DEFAULT_MIN_PTS ) ) );
            this.minCandidatePool = Integer.parseInt(
                props.getProperty( PROP_MIN_CANDIDATE_POOL, String.valueOf( DEFAULT_MIN_CANDIDATE_POOL ) ) );
            return this;
        }

        public HubDiscoveryService build() {
            if ( kgRepo == null || discoveryRepo == null
                    || contentRepo == null || contentModelSupplier == null ) {
                throw new IllegalStateException(
                    "HubDiscoveryService.Builder: kgRepo, discoveryRepo, contentRepo, and a content "
                    + "model (contentModel or contentModelSupplier) are all required" );
            }
            return new HubDiscoveryService( this );
        }
    }
}

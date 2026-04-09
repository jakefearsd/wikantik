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

import com.wikantik.api.knowledge.KgNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Supplier;

/**
 * Batch service that computes Hub membership proposals using TF-IDF content embeddings.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Compute centroid embedding for each Hub (average of member page vectors)</li>
 *   <li>Score all candidate pages against all Hub centroids (cosine similarity)</li>
 *   <li>Convert raw scores to percentile ranks across all page-Hub combinations</li>
 *   <li>Write proposals above the review threshold to the review queue</li>
 * </ol>
 */
public class HubProposalService {

    private static final Logger LOG = LogManager.getLogger( HubProposalService.class );

    public static final String PROP_REVIEW_PERCENTILE = "wikantik.hub.reviewPercentile";
    private static final int DEFAULT_REVIEW_PERCENTILE = 50;

    private final JdbcKnowledgeRepository kgRepo;
    private final HubProposalRepository proposalRepo;
    private final ContentEmbeddingRepository contentRepo;
    private final int reviewPercentile;
    private final Supplier< TfidfModel > contentModelSupplier;

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final int reviewPercentile,
                                final TfidfModel contentModel ) {
        this.kgRepo = kgRepo;
        this.proposalRepo = proposalRepo;
        this.contentRepo = contentRepo;
        this.reviewPercentile = reviewPercentile;
        this.contentModelSupplier = () -> contentModel;
    }

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final Properties props,
                                final TfidfModel contentModel ) {
        this( kgRepo, proposalRepo, contentRepo,
            Integer.parseInt( props.getProperty( PROP_REVIEW_PERCENTILE,
                String.valueOf( DEFAULT_REVIEW_PERCENTILE ) ) ),
            contentModel );
    }

    /**
     * Production constructor — the content model supplier is invoked fresh on every
     * call to {@link #generateProposals()} so the service always uses the latest
     * trained model without needing to be re-registered after retrains.
     */
    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final int reviewPercentile,
                                final Supplier< TfidfModel > contentModelSupplier ) {
        this.kgRepo = kgRepo;
        this.proposalRepo = proposalRepo;
        this.contentRepo = contentRepo;
        this.reviewPercentile = reviewPercentile;
        this.contentModelSupplier = contentModelSupplier;
    }

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final Properties props,
                                final Supplier< TfidfModel > contentModelSupplier ) {
        this( kgRepo, proposalRepo, contentRepo,
            Integer.parseInt( props.getProperty( PROP_REVIEW_PERCENTILE,
                String.valueOf( DEFAULT_REVIEW_PERCENTILE ) ) ),
            contentModelSupplier );
    }

    /**
     * Generates Hub membership proposals. This is a blocking batch operation.
     *
     * @return number of proposals created
     * @throws HubProposalException if generation fails (caller should log and surface the error)
     */
    public int generateProposals() {
        LOG.info( "Hub proposals: generation starting (reviewPercentile={})", reviewPercentile );
        final long start = System.currentTimeMillis();

        final TfidfModel contentModel = resolveReadyContentModel();
        if ( contentModel == null ) return 0;

        try {
            final HubMembership membership = loadHubMembership();
            if ( membership.hubMembers().isEmpty() ) return 0;

            final Map< String, float[] > centroids = computeAndSaveCentroids( contentModel, membership.hubMembers() );
            if ( centroids.isEmpty() ) {
                LOG.warn( "Hub proposals ending early: no computable centroids "
                    + "— ensure hub members have been indexed by the content model" );
                return 0;
            }

            final List< Score > allScores = scoreCandidates( contentModel, membership, centroids );
            if ( allScores.isEmpty() ) {
                LOG.warn( "Hub proposals ending early: no candidate scores computed "
                    + "— no non-hub, non-member pages in the content model" );
                return 0;
            }

            final int created = writeProposalsAboveThreshold( allScores );
            LOG.info( "Hub proposals complete in {}ms: threshold={}th percentile, "
                + "{} scores computed, {} created",
                System.currentTimeMillis() - start, reviewPercentile, allScores.size(), created );
            return created;
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered batch failure — stack trace must land in the server log since the REST caller only sees a 500 message.
            LOG.error( "Hub proposals generation failed with exception", e );
            throw new HubProposalException( "Hub proposals generation failed: " + e.getMessage(), e );
        }
    }

    /** Carrier for hub→members map plus the set of all hub names (used to skip hub-typed candidates in scoring). */
    private record HubMembership( Set< String > allHubNames, Map< String, List< String > > hubMembers ) {}

    /** Candidate (hub × page) similarity score, populated in step 3 and consumed in step 4. */
    private record Score( String hubName, String pageName, double rawSimilarity ) {}

    /** Returns the content model if ready, logging and returning null otherwise. */
    private TfidfModel resolveReadyContentModel() {
        final TfidfModel contentModel = contentModelSupplier.get();
        if ( contentModel == null ) {
            LOG.warn( "Hub proposals skipped: content model is null (EmbeddingService not ready or not wired)" );
            return null;
        }
        if ( contentModel.getEntityCount() == 0 ) {
            LOG.warn( "Hub proposals skipped: content model has 0 entities (not trained yet)" );
            return null;
        }
        LOG.info( "Hub proposals: using content model with {} entities, dimension {}",
            contentModel.getEntityCount(), contentModel.getDimension() );
        return contentModel;
    }

    /**
     * Step 1 — Load every hub's membership from `related` edges.
     *
     * <p>Membership is the single source of truth stored in kg_edges — the GraphProjector routes
     * any frontmatter key whose value is a List&lt;String&gt; into edges, not node properties, so
     * reading node.properties().get("related") would always be null for real data.
     */
    private HubMembership loadHubMembership() {
        final Set< String > allHubNames = loadHubNames();
        if ( allHubNames.isEmpty() ) {
            LOG.warn( "Hub proposals ending early: no KG nodes with type=hub found" );
            return new HubMembership( allHubNames, Map.of() );
        }

        final Map< String, List< String > > hubMembers = groupRelatedEdgesByHub( allHubNames );
        final int hubsWithTooFewMembers = dropHubsWithFewerThanTwoDistinctMembers( hubMembers );
        final int hubsWithNoRelatedEdges = allHubNames.size() - ( hubMembers.size() + hubsWithTooFewMembers );
        LOG.info( "Hub proposals step 1 summary: {} hubs total, {} usable (>=2 members), "
            + "{} have <2 members, {} have zero 'related' edges",
            allHubNames.size(), hubMembers.size(), hubsWithTooFewMembers, hubsWithNoRelatedEdges );

        if ( hubMembers.isEmpty() ) {
            LOG.warn( "Hub proposals ending early: no hubs with 2+ members found "
                + "(hubsSeen={}, hubsWith<2Members={}, hubsWithNoEdges={}) — "
                + "verify that hub pages have 'related: [...]' frontmatter and have been "
                + "projected into the KG (try POST /admin/knowledge/project-all)",
                allHubNames.size(), hubsWithTooFewMembers, hubsWithNoRelatedEdges );
        }
        return new HubMembership( allHubNames, hubMembers );
    }

    /** Step 1a — identify hub-typed KG nodes. */
    private Set< String > loadHubNames() {
        final List< KgNode > allNodes = kgRepo.queryNodes( null, null, 100_000, 0 );
        LOG.info( "Hub proposals step 1a: loaded {} KG nodes", allNodes.size() );
        final Set< String > allHubNames = new HashSet<>();
        for ( final KgNode node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                allHubNames.add( node.name() );
            }
        }
        LOG.info( "Hub proposals step 1a summary: {} hubs identified", allHubNames.size() );
        return allHubNames;
    }

    /** Step 1b — group `related` edges by their hub source. */
    private Map< String, List< String > > groupRelatedEdgesByHub( final Set< String > allHubNames ) {
        final List< Map< String, Object > > relatedEdges =
            kgRepo.queryEdgesWithNames( "related", null, 100_000, 0 );
        LOG.info( "Hub proposals step 1b: loaded {} 'related' edges", relatedEdges.size() );

        final Map< String, List< String > > hubMembers = new LinkedHashMap<>();
        int edgesFromHubs = 0;
        int edgesFromNonHubs = 0;
        for ( final Map< String, Object > edge : relatedEdges ) {
            final String source = (String) edge.get( "source_name" );
            final String target = (String) edge.get( "target_name" );
            if ( source == null || target == null ) continue;
            if ( !allHubNames.contains( source ) ) {
                edgesFromNonHubs++;
                continue;
            }
            edgesFromHubs++;
            hubMembers.computeIfAbsent( source, k -> new ArrayList<>() ).add( target );
        }
        LOG.info( "Hub proposals step 1b summary: {} edges grouped under {} hubs "
            + "(ignored {} 'related' edges whose source is not a hub)",
            edgesFromHubs, hubMembers.size(), edgesFromNonHubs );
        return hubMembers;
    }

    /** Step 1c — drop hubs with fewer than 2 distinct members, in place. Returns count dropped. */
    private int dropHubsWithFewerThanTwoDistinctMembers( final Map< String, List< String > > hubMembers ) {
        int hubsWithTooFewMembers = 0;
        final Iterator< Map.Entry< String, List< String > > > iter = hubMembers.entrySet().iterator();
        while ( iter.hasNext() ) {
            final Map.Entry< String, List< String > > entry = iter.next();
            final List< String > distinctMembers = entry.getValue().stream().distinct().toList();
            if ( distinctMembers.size() < 2 ) {
                iter.remove();
                hubsWithTooFewMembers++;
            } else {
                entry.setValue( distinctMembers );
            }
        }
        return hubsWithTooFewMembers;
    }

    /** Step 2 — compute and persist hub centroid embeddings. */
    private Map< String, float[] > computeAndSaveCentroids( final TfidfModel contentModel,
                                                              final Map< String, List< String > > hubMembers ) {
        final Map< String, float[] > centroids = new LinkedHashMap<>();
        int centroidsSaved = 0;
        int centroidsSkipped = 0;
        for ( final var entry : hubMembers.entrySet() ) {
            final String hubName = entry.getKey();
            final List< String > members = entry.getValue();
            final float[] centroid = computeCentroid( contentModel, members );
            if ( centroid == null ) {
                centroidsSkipped++;
                LOG.warn( "Hub proposals: could not compute centroid for hub '{}' — "
                    + "fewer than 2 members had content embeddings (members={})", hubName, members );
                continue;
            }
            centroids.put( hubName, centroid );
            proposalRepo.saveCentroid( hubName, centroid, 0, members.size() );
            centroidsSaved++;
        }
        LOG.info( "Hub proposals step 2 summary: {} centroids computed and saved, {} skipped",
            centroidsSaved, centroidsSkipped );
        return centroids;
    }

    /** Step 3 — score every non-hub, non-member page against every hub centroid. */
    private List< Score > scoreCandidates( final TfidfModel contentModel,
                                             final HubMembership membership,
                                             final Map< String, float[] > centroids ) {
        final List< Score > allScores = new ArrayList<>();
        int skippedBecauseIsHub = 0;
        int skippedBecauseExistingMember = 0;
        int skippedBecauseUnknownInModel = 0;
        for ( final var hubEntry : centroids.entrySet() ) {
            final String hubName = hubEntry.getKey();
            final float[] centroid = hubEntry.getValue();
            final Set< String > existingMembers = new HashSet<>( membership.hubMembers().get( hubName ) );

            for ( final String pageName : contentModel.getEntityNames() ) {
                if ( membership.allHubNames().contains( pageName ) ) { skippedBecauseIsHub++; continue; }
                if ( existingMembers.contains( pageName ) ) { skippedBecauseExistingMember++; continue; }
                final int pageId = contentModel.entityId( pageName );
                if ( pageId < 0 ) { skippedBecauseUnknownInModel++; continue; }

                final double sim = cosineSimilarity( contentModel.getVector( pageId ), centroid );
                allScores.add( new Score( hubName, pageName, sim ) );
            }
        }
        LOG.info( "Hub proposals step 3 summary: {} scores computed "
            + "(skipped: {} hubs, {} existing members, {} unknown-in-model)",
            allScores.size(), skippedBecauseIsHub,
            skippedBecauseExistingMember, skippedBecauseUnknownInModel );
        return allScores;
    }

    /** Steps 4 + 5 — compute percentile ranks and insert proposals that clear the threshold. */
    private int writeProposalsAboveThreshold( final List< Score > allScores ) {
        final double[] rawValues = allScores.stream().mapToDouble( Score::rawSimilarity ).sorted().toArray();
        LOG.info( "Hub proposals step 4: raw similarity range [{}, {}]",
            rawValues[ 0 ], rawValues[ rawValues.length - 1 ] );

        int aboveThreshold = 0;
        int skippedExisting = 0;
        int skippedRejected = 0;
        int created = 0;
        for ( final Score score : allScores ) {
            final double percentile = computePercentile( rawValues, score.rawSimilarity );
            if ( percentile < reviewPercentile ) continue;
            aboveThreshold++;

            if ( proposalRepo.exists( score.hubName, score.pageName ) ) {
                skippedExisting++;
                continue;
            }
            if ( proposalRepo.isRejected( score.hubName, score.pageName ) ) {
                skippedRejected++;
                continue;
            }

            proposalRepo.insertProposal( score.hubName, score.pageName,
                score.rawSimilarity, percentile );
            created++;
        }
        LOG.info( "Hub proposals step 5 summary: {} above threshold, {} skipped-existing, "
            + "{} skipped-rejected, {} created",
            aboveThreshold, skippedExisting, skippedRejected, created );
        return created;
    }

    /** Thrown when hub proposal generation fails unexpectedly. */
    public static class HubProposalException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public HubProposalException( final String message, final Throwable cause ) {
            super( message, cause );
        }
    }

    private float[] computeCentroid( final TfidfModel contentModel, final List< String > memberNames ) {
        final List< float[] > vectors = new ArrayList<>();
        for ( final String name : memberNames ) {
            final int id = contentModel.entityId( name );
            if ( id >= 0 ) {
                vectors.add( contentModel.getVector( id ) );
            }
        }
        if ( vectors.size() < 2 ) return null;

        final float[] centroid = new float[ TfidfModel.DIMENSION ];
        for ( final float[] v : vectors ) {
            for ( int i = 0; i < centroid.length; i++ ) {
                centroid[ i ] += v[ i ];
            }
        }
        // Average
        for ( int i = 0; i < centroid.length; i++ ) {
            centroid[ i ] /= vectors.size();
        }
        // Normalize for cosine similarity
        double norm = 0;
        for ( final float v : centroid ) norm += v * v;
        if ( norm > 0 ) {
            norm = Math.sqrt( norm );
            for ( int i = 0; i < centroid.length; i++ ) centroid[ i ] /= (float) norm;
        }
        return centroid;
    }

    private static double cosineSimilarity( final float[] a, final float[] b ) {
        double dot = 0;
        for ( int i = 0; i < a.length; i++ ) {
            dot += a[ i ] * b[ i ];
        }
        return dot; // vectors are L2-normalized
    }

    static double computePercentile( final double[] sortedValues, final double value ) {
        int count = 0;
        for ( final double v : sortedValues ) {
            if ( v < value ) count++;
        }
        return ( count * 100.0 ) / sortedValues.length;
    }
}

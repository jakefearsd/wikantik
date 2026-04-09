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
    @SuppressWarnings( "unchecked" )
    public int generateProposals() {
        LOG.info( "Hub proposals: generation starting (reviewPercentile={})", reviewPercentile );
        final long start = System.currentTimeMillis();

        final TfidfModel contentModel = contentModelSupplier.get();
        if ( contentModel == null ) {
            LOG.warn( "Hub proposals skipped: content model is null (EmbeddingService not ready or not wired)" );
            return 0;
        }
        if ( contentModel.getEntityCount() == 0 ) {
            LOG.warn( "Hub proposals skipped: content model has 0 entities (not trained yet)" );
            return 0;
        }
        LOG.info( "Hub proposals: using content model with {} entities, dimension {}",
            contentModel.getEntityCount(), contentModel.getDimension() );

        try {
            // Step 1a: Find all Hub pages (nodes with type=hub).
            final List< KgNode > allNodes = kgRepo.queryNodes( null, null, 100_000, 0 );
            LOG.info( "Hub proposals step 1a: loaded {} KG nodes", allNodes.size() );

            final Set< String > allHubNames = new HashSet<>();
            for ( final KgNode node : allNodes ) {
                if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                    allHubNames.add( node.name() );
                }
            }
            LOG.info( "Hub proposals step 1a summary: {} hubs identified", allHubNames.size() );
            if ( allHubNames.isEmpty() ) {
                LOG.warn( "Hub proposals ending early: no KG nodes with type=hub found" );
                return 0;
            }

            // Step 1b: Load hub members from `related` edges. Membership is the single
            // source of truth stored in kg_edges — the GraphProjector routes any frontmatter
            // key whose value is a List<String> into edges, not node properties, so reading
            // node.properties().get("related") would always be null for real data.
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

            // Step 1c: Drop hubs that don't have at least 2 distinct members.
            int hubsWithTooFewMembers = 0;
            final Iterator< Map.Entry< String, List< String > > > iter = hubMembers.entrySet().iterator();
            while ( iter.hasNext() ) {
                final Map.Entry< String, List< String > > entry = iter.next();
                final List< String > distinctMembers = entry.getValue().stream().distinct().toList();
                if ( distinctMembers.size() < 2 ) {
                    LOG.debug( "Hub proposals: hub '{}' skipped — only {} distinct member(s)",
                        entry.getKey(), distinctMembers.size() );
                    iter.remove();
                    hubsWithTooFewMembers++;
                } else {
                    entry.setValue( distinctMembers );
                    LOG.debug( "Hub proposals: hub '{}' has {} distinct members",
                        entry.getKey(), distinctMembers.size() );
                }
            }

            // Additionally count hubs that have no edges at all (they never even appeared in hubMembers).
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
                return 0;
            }

            // Step 2: Compute Hub centroid embeddings
            final Map< String, float[] > centroids = new LinkedHashMap<>();
            int centroidsSaved = 0;
            int centroidsSkipped = 0;
            for ( final var entry : hubMembers.entrySet() ) {
                final String hubName = entry.getKey();
                final List< String > members = entry.getValue();
                final float[] centroid = computeCentroid( contentModel, members );
                if ( centroid != null ) {
                    centroids.put( hubName, centroid );
                    proposalRepo.saveCentroid( hubName, centroid,
                        0, members.size() );
                    centroidsSaved++;
                } else {
                    centroidsSkipped++;
                    LOG.warn( "Hub proposals: could not compute centroid for hub '{}' — "
                        + "fewer than 2 members had content embeddings (members={})", hubName, members );
                }
            }
            LOG.info( "Hub proposals step 2 summary: {} centroids computed and saved, {} skipped",
                centroidsSaved, centroidsSkipped );

            if ( centroids.isEmpty() ) {
                LOG.warn( "Hub proposals ending early: no computable centroids "
                    + "— ensure hub members have been indexed by the content model" );
                return 0;
            }

            // Step 3: Score all candidate pages against all Hub centroids
            record Score( String hubName, String pageName, double rawSimilarity ) {}
            final List< Score > allScores = new ArrayList<>();

            int skippedBecauseIsHub = 0;
            int skippedBecauseExistingMember = 0;
            int skippedBecauseUnknownInModel = 0;
            for ( final var hubEntry : centroids.entrySet() ) {
                final String hubName = hubEntry.getKey();
                final float[] centroid = hubEntry.getValue();
                final Set< String > existingMembers = new HashSet<>( hubMembers.get( hubName ) );

                for ( final String pageName : contentModel.getEntityNames() ) {
                    // Skip Hubs themselves and existing members
                    if ( allHubNames.contains( pageName ) ) { skippedBecauseIsHub++; continue; }
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

            if ( allScores.isEmpty() ) {
                LOG.warn( "Hub proposals ending early: no candidate scores computed "
                    + "— no non-hub, non-member pages in the content model" );
                return 0;
            }

            // Step 4: Percentile normalization
            final double[] rawValues = allScores.stream().mapToDouble( Score::rawSimilarity ).sorted().toArray();
            LOG.info( "Hub proposals step 4: raw similarity range [{}, {}]",
                rawValues[ 0 ], rawValues[ rawValues.length - 1 ] );

            // Step 5: Write proposals above threshold
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

            final long elapsed = System.currentTimeMillis() - start;
            LOG.info( "Hub proposals complete in {}ms: threshold={}th percentile, "
                + "{} scores computed, {} above threshold, {} skipped-existing, "
                + "{} skipped-rejected, {} created",
                elapsed, reviewPercentile, allScores.size(), aboveThreshold,
                skippedExisting, skippedRejected, created );
            return created;
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered batch failure — stack trace must land in the server log since the REST caller only sees a 500 message.
            LOG.error( "Hub proposals generation failed with exception", e );
            throw new HubProposalException( "Hub proposals generation failed: " + e.getMessage(), e );
        }
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

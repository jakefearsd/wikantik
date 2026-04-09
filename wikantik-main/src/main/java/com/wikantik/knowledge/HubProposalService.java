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
    private static final int DEFAULT_REVIEW_PERCENTILE = 90;

    private final JdbcKnowledgeRepository kgRepo;
    private final HubProposalRepository proposalRepo;
    private final ContentEmbeddingRepository contentRepo;
    private final int reviewPercentile;
    private final TfidfModel contentModel;

    public HubProposalService( final JdbcKnowledgeRepository kgRepo,
                                final HubProposalRepository proposalRepo,
                                final ContentEmbeddingRepository contentRepo,
                                final int reviewPercentile,
                                final TfidfModel contentModel ) {
        this.kgRepo = kgRepo;
        this.proposalRepo = proposalRepo;
        this.contentRepo = contentRepo;
        this.reviewPercentile = reviewPercentile;
        this.contentModel = contentModel;
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
     * Generates Hub membership proposals. This is a blocking batch operation.
     *
     * @return number of proposals created
     */
    @SuppressWarnings( "unchecked" )
    public int generateProposals() {
        if ( contentModel == null || contentModel.getEntityCount() == 0 ) {
            LOG.warn( "Hub proposals skipped: content model not trained" );
            return 0;
        }

        final long start = System.currentTimeMillis();

        // Step 1: Find all Hub pages and their current members
        final List< KgNode > allNodes = kgRepo.queryNodes( null, null, 100_000, 0 );
        final Map< String, List< String > > hubMembers = new LinkedHashMap<>();
        final Set< String > allHubNames = new HashSet<>();

        for ( final KgNode node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                allHubNames.add( node.name() );
                final Object related = node.properties().get( "related" );
                if ( related instanceof List<?> list ) {
                    final List< String > members = list.stream()
                        .filter( String.class::isInstance )
                        .map( String.class::cast )
                        .toList();
                    if ( members.size() >= 2 ) {
                        hubMembers.put( node.name(), members );
                    }
                }
            }
        }

        if ( hubMembers.isEmpty() ) {
            LOG.info( "Hub proposals skipped: no hubs with 2+ members found" );
            return 0;
        }

        // Step 2: Compute Hub centroid embeddings
        final Map< String, float[] > centroids = new LinkedHashMap<>();
        for ( final var entry : hubMembers.entrySet() ) {
            final String hubName = entry.getKey();
            final List< String > members = entry.getValue();
            final float[] centroid = computeCentroid( members );
            if ( centroid != null ) {
                centroids.put( hubName, centroid );
                proposalRepo.saveCentroid( hubName, centroid,
                    0, members.size() );
            }
        }

        if ( centroids.isEmpty() ) {
            LOG.info( "Hub proposals skipped: no computable centroids" );
            return 0;
        }

        // Step 3: Score all candidate pages against all Hub centroids
        record Score( String hubName, String pageName, double rawSimilarity ) {}
        final List< Score > allScores = new ArrayList<>();

        for ( final var hubEntry : centroids.entrySet() ) {
            final String hubName = hubEntry.getKey();
            final float[] centroid = hubEntry.getValue();
            final Set< String > existingMembers = new HashSet<>( hubMembers.get( hubName ) );

            for ( final String pageName : contentModel.getEntityNames() ) {
                // Skip Hubs themselves and existing members
                if ( allHubNames.contains( pageName ) || existingMembers.contains( pageName ) ) {
                    continue;
                }
                final int pageId = contentModel.entityId( pageName );
                if ( pageId < 0 ) continue;

                final double sim = cosineSimilarity( contentModel.getVector( pageId ), centroid );
                allScores.add( new Score( hubName, pageName, sim ) );
            }
        }

        if ( allScores.isEmpty() ) {
            LOG.info( "Hub proposals: no candidate scores computed" );
            return 0;
        }

        // Step 4: Percentile normalization
        final double[] rawValues = allScores.stream().mapToDouble( Score::rawSimilarity ).sorted().toArray();

        // Step 5: Write proposals above threshold
        int created = 0;
        for ( final Score score : allScores ) {
            final double percentile = computePercentile( rawValues, score.rawSimilarity );
            if ( percentile < reviewPercentile ) continue;

            // Skip if already exists or was rejected
            if ( proposalRepo.exists( score.hubName, score.pageName )
                || proposalRepo.isRejected( score.hubName, score.pageName ) ) {
                continue;
            }

            proposalRepo.insertProposal( score.hubName, score.pageName,
                score.rawSimilarity, percentile );
            created++;
        }

        final long elapsed = System.currentTimeMillis() - start;
        LOG.info( "Hub proposals generated in {}ms: {} scores computed, {} proposals created "
            + "(threshold: {}th percentile)", elapsed, allScores.size(), created, reviewPercentile );
        return created;
    }

    private float[] computeCentroid( final List< String > memberNames ) {
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

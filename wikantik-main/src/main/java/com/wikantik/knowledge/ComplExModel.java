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

import java.util.*;

/**
 * Pure-Java implementation of the ComplEx (Complex Embeddings) knowledge graph
 * embedding model. ComplEx represents entities and relations as complex-valued
 * vectors, allowing it to model both symmetric and antisymmetric relations.
 *
 * <p>Scoring function: {@code Re(Σ(hRe*rRe*tRe + hIm*rRe*tIm + hRe*rIm*tIm - hIm*rIm*tRe))}</p>
 *
 * <p>Training uses stochastic gradient descent with negative sampling and
 * margin-based ranking loss.</p>
 *
 * @since 1.0
 */
public class ComplExModel {

    /** A scored prediction result. */
    public record Prediction( int entityId, String entityName, double score ) implements Comparable< Prediction > {
        @Override public int compareTo( final Prediction o ) {
            return Double.compare( o.score, this.score ); // descending
        }
    }

    /** A triple of integer indices: (head, relation, tail). */
    public record Triple( int head, int relation, int tail ) {}

    private float[][] entityReal;
    private float[][] entityImag;
    private float[][] relationReal;
    private float[][] relationImag;
    private final Map< String, Integer > entityIndex = new LinkedHashMap<>();
    private final Map< String, Integer > relationIndex = new LinkedHashMap<>();
    private final List< String > entityNames = new ArrayList<>();
    private final List< String > relationNames = new ArrayList<>();
    private int dim;

    /** Returns the embedding dimension. */
    public int getDimension() { return dim; }

    /** Returns the number of entities. */
    public int getEntityCount() { return entityNames.size(); }

    /** Returns the number of relation types. */
    public int getRelationCount() { return relationNames.size(); }

    /** Returns unmodifiable entity name list (index-aligned). */
    public List< String > getEntityNames() { return Collections.unmodifiableList( entityNames ); }

    /** Returns unmodifiable relation name list (index-aligned). */
    public List< String > getRelationNames() { return Collections.unmodifiableList( relationNames ); }

    /** Returns the internal index for an entity name, or -1 if unknown. */
    public int entityId( final String name ) { return entityIndex.getOrDefault( name, -1 ); }

    /** Returns the internal index for a relation type, or -1 if unknown. */
    public int relationId( final String name ) { return relationIndex.getOrDefault( name, -1 ); }

    /** Returns the real component of an entity's embedding. */
    public float[] getEntityReal( final int id ) { return entityReal[ id ]; }

    /** Returns the imaginary component of an entity's embedding. */
    public float[] getEntityImag( final int id ) { return entityImag[ id ]; }

    /** Returns the real component of a relation's embedding. */
    public float[] getRelationReal( final int id ) { return relationReal[ id ]; }

    /** Returns the imaginary component of a relation's embedding. */
    public float[] getRelationImag( final int id ) { return relationImag[ id ]; }

    /**
     * Trains the ComplEx model on the provided triples.
     *
     * @param entityNameList   ordered list of entity names
     * @param relationNameList ordered list of relation type names
     * @param triples          training triples (indices into the name lists)
     * @param dim              embedding dimension
     * @param epochs           number of training epochs
     * @param lr               learning rate
     * @param negSamples       number of negative samples per positive triple
     * @param margin           margin for ranking loss
     */
    public void train( final List< String > entityNameList, final List< String > relationNameList,
                       final List< Triple > triples, final int dim, final int epochs,
                       final float lr, final int negSamples, final float margin ) {
        this.dim = dim;
        entityNames.clear();
        entityNames.addAll( entityNameList );
        relationNames.clear();
        relationNames.addAll( relationNameList );
        entityIndex.clear();
        relationIndex.clear();
        for( int i = 0; i < entityNames.size(); i++ ) entityIndex.put( entityNames.get( i ), i );
        for( int i = 0; i < relationNames.size(); i++ ) relationIndex.put( relationNames.get( i ), i );

        final int numEntities = entityNames.size();
        final int numRelations = relationNames.size();
        final Random rng = new Random( 42 );

        // Xavier initialization
        final float scale = ( float ) Math.sqrt( 6.0 / dim );
        entityReal = initMatrix( numEntities, dim, scale, rng );
        entityImag = initMatrix( numEntities, dim, scale, rng );
        relationReal = initMatrix( numRelations, dim, scale, rng );
        relationImag = initMatrix( numRelations, dim, scale, rng );

        if( triples.isEmpty() ) return;

        // SGD with negative sampling
        for( int epoch = 0; epoch < epochs; epoch++ ) {
            final List< Triple > shuffled = new ArrayList<>( triples );
            Collections.shuffle( shuffled, rng );

            for( final Triple pos : shuffled ) {
                final double posScore = score( pos.head, pos.relation, pos.tail );

                for( int n = 0; n < negSamples; n++ ) {
                    // Corrupt either head or tail
                    final Triple neg;
                    if( rng.nextBoolean() ) {
                        neg = new Triple( rng.nextInt( numEntities ), pos.relation, pos.tail );
                    } else {
                        neg = new Triple( pos.head, pos.relation, rng.nextInt( numEntities ) );
                    }
                    final double negScore = score( neg.head, neg.relation, neg.tail );

                    // Margin-based ranking loss: max(0, margin - posScore + negScore)
                    final double loss = margin - posScore + negScore;
                    if( loss > 0 ) {
                        updateGradients( pos, +lr, neg, -lr );
                    }
                }
            }

            // L2 normalize all embeddings every epoch to prevent drift
            normalizeRows( entityReal );
            normalizeRows( entityImag );
            normalizeRows( relationReal );
            normalizeRows( relationImag );
        }

        validateFinite();
    }

    private void validateFinite() {
        for( int i = 0; i < entityReal.length; i++ ) {
            checkRow( entityReal[ i ], "entityReal", i );
            checkRow( entityImag[ i ], "entityImag", i );
        }
        for( int i = 0; i < relationReal.length; i++ ) {
            checkRow( relationReal[ i ], "relationReal", i );
            checkRow( relationImag[ i ], "relationImag", i );
        }
    }

    private void checkRow( final float[] row, final String label, final int idx ) {
        for( int j = 0; j < row.length; j++ ) {
            if( !Float.isFinite( row[ j ] ) ) {
                throw new ArithmeticException(
                    label + "[" + idx + "][" + j + "] is " + row[ j ] + " after training" );
            }
        }
    }

    /**
     * Scores a triple (h, r, t) using the ComplEx scoring function.
     * Higher scores indicate more plausible triples.
     */
    public double score( final int h, final int r, final int t ) {
        double s = 0;
        final float[] hRe = entityReal[ h ], hIm = entityImag[ h ];
        final float[] rRe = relationReal[ r ], rIm = relationImag[ r ];
        final float[] tRe = entityReal[ t ], tIm = entityImag[ t ];
        for( int d = 0; d < dim; d++ ) {
            s += hRe[ d ] * rRe[ d ] * tRe[ d ]
               + hIm[ d ] * rRe[ d ] * tIm[ d ]
               + hRe[ d ] * rIm[ d ] * tIm[ d ]
               - hIm[ d ] * rIm[ d ] * tRe[ d ];
        }
        return s;
    }

    /**
     * Predicts the top-K candidate tail entities for (head, relation, ?).
     *
     * @param h    head entity index
     * @param r    relation index
     * @param topK number of results
     * @return sorted list of predictions, highest score first
     */
    public List< Prediction > predictTails( final int h, final int r, final int topK ) {
        final PriorityQueue< Prediction > pq = new PriorityQueue<>( Comparator.comparingDouble( p -> p.score ) );
        for( int t = 0; t < entityNames.size(); t++ ) {
            if( t == h ) continue;
            final double s = score( h, r, t );
            if( pq.size() < topK ) {
                pq.add( new Prediction( t, entityNames.get( t ), s ) );
            } else if( s > pq.peek().score ) {
                pq.poll();
                pq.add( new Prediction( t, entityNames.get( t ), s ) );
            }
        }
        final List< Prediction > result = new ArrayList<>( pq );
        result.sort( Prediction::compareTo );
        return result;
    }

    /**
     * Predicts the top-K candidate head entities for (?, relation, tail).
     */
    public List< Prediction > predictHeads( final int r, final int t, final int topK ) {
        final PriorityQueue< Prediction > pq = new PriorityQueue<>( Comparator.comparingDouble( p -> p.score ) );
        for( int h = 0; h < entityNames.size(); h++ ) {
            if( h == t ) continue;
            final double s = score( h, r, t );
            if( pq.size() < topK ) {
                pq.add( new Prediction( h, entityNames.get( h ), s ) );
            } else if( s > pq.peek().score ) {
                pq.poll();
                pq.add( new Prediction( h, entityNames.get( h ), s ) );
            }
        }
        final List< Prediction > result = new ArrayList<>( pq );
        result.sort( Prediction::compareTo );
        return result;
    }

    /**
     * Computes cosine similarity between two entities using their concatenated
     * [real, imag] embedding vectors.
     *
     * @return similarity in [-1, 1]
     */
    public double similarity( final int a, final int b ) {
        double dot = 0, normA = 0, normB = 0;
        for( int d = 0; d < dim; d++ ) {
            final float aRe = entityReal[ a ][ d ], aIm = entityImag[ a ][ d ];
            final float bRe = entityReal[ b ][ d ], bIm = entityImag[ b ][ d ];
            dot += aRe * bRe + aIm * bIm;
            normA += aRe * aRe + aIm * aIm;
            normB += bRe * bRe + bIm * bIm;
        }
        final double denom = Math.sqrt( normA ) * Math.sqrt( normB );
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * Finds the top-K most similar entities to the given entity.
     *
     * @param entityId the entity to find neighbors for
     * @param topK     number of results
     * @return sorted list of predictions with similarity as score
     */
    public List< Prediction > mostSimilar( final int entityId, final int topK ) {
        final PriorityQueue< Prediction > pq = new PriorityQueue<>( Comparator.comparingDouble( p -> p.score ) );
        for( int i = 0; i < entityNames.size(); i++ ) {
            if( i == entityId ) continue;
            final double sim = similarity( entityId, i );
            if( pq.size() < topK ) {
                pq.add( new Prediction( i, entityNames.get( i ), sim ) );
            } else if( sim > pq.peek().score ) {
                pq.poll();
                pq.add( new Prediction( i, entityNames.get( i ), sim ) );
            }
        }
        final List< Prediction > result = new ArrayList<>( pq );
        result.sort( Prediction::compareTo );
        return result;
    }

    // ---- Gradient update ----

    private void updateGradients( final Triple pos, final float posLr, final Triple neg, final float negLr ) {
        updateTripleGradient( pos.head, pos.relation, pos.tail, posLr );
        updateTripleGradient( neg.head, neg.relation, neg.tail, negLr );
    }

    private static final float GRAD_CLIP = 5.0f;

    private static float clip( final float v ) {
        return Math.max( -GRAD_CLIP, Math.min( GRAD_CLIP, v ) );
    }

    private void updateTripleGradient( final int h, final int r, final int t, final float lr ) {
        for( int d = 0; d < dim; d++ ) {
            final float hRe = entityReal[ h ][ d ], hIm = entityImag[ h ][ d ];
            final float rRe = relationReal[ r ][ d ], rIm = relationImag[ r ][ d ];
            final float tRe = entityReal[ t ][ d ], tIm = entityImag[ t ][ d ];

            entityReal[ h ][ d ] += lr * clip( rRe * tRe + rIm * tIm );
            entityImag[ h ][ d ] += lr * clip( rRe * tIm - rIm * tRe );
            relationReal[ r ][ d ] += lr * clip( hRe * tRe + hIm * tIm );
            relationImag[ r ][ d ] += lr * clip( hRe * tIm - hIm * tRe );
            entityReal[ t ][ d ] += lr * clip( hRe * rRe - hIm * rIm );
            entityImag[ t ][ d ] += lr * clip( hIm * rRe + hRe * rIm );
        }
    }

    // ---- Initialization helpers ----

    private static float[][] initMatrix( final int rows, final int cols, final float scale, final Random rng ) {
        final float[][] m = new float[ rows ][ cols ];
        for( int i = 0; i < rows; i++ ) {
            for( int j = 0; j < cols; j++ ) {
                m[ i ][ j ] = ( rng.nextFloat() * 2 - 1 ) * scale;
            }
        }
        return m;
    }

    private static void normalizeRows( final float[][] m ) {
        for( final float[] row : m ) {
            double norm = 0;
            for( final float v : row ) norm += (double) v * v;
            if( norm > 0 ) {
                final float invNorm = ( float ) ( 1.0 / Math.sqrt( norm ) );
                for( int j = 0; j < row.length; j++ ) row[ j ] *= invNorm;
            }
        }
    }
}

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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * TF-IDF text vectorizer with feature hashing for fixed-dimension output.
 *
 * <p>Uses Lucene's {@link EnglishAnalyzer} for tokenization, stop word removal,
 * and Porter stemming. Feature hashing maps the variable-size vocabulary to a
 * fixed {@value #DIMENSION}-dimensional vector, compatible with pgvector storage.</p>
 *
 * @since 1.0
 */
public class TfidfModel {

    /** Fixed output dimension for all vectors. */
    public static final int DIMENSION = 512;

    /** A scored entity pair from similarity search. */
    public record ScoredPair( String name, double score ) {}

    /** A scored pair of entities from all-pairs similarity. */
    public record SimilarPagePair( String nameA, String nameB, double score ) {}

    private List< String > entityNames;
    private Map< String, Integer > entityIndex;
    private float[][] vectors; // [entityCount][DIMENSION]

    /**
     * Builds TF-IDF vectors from the given documents using feature hashing.
     *
     * @param names     entity names, index-aligned with documents
     * @param documents text documents, one per entity
     */
    public void build( final List< String > names, final List< String > documents ) {
        this.entityNames = new ArrayList<>( names );
        this.entityIndex = new HashMap<>();
        for( int i = 0; i < names.size(); i++ ) entityIndex.put( names.get( i ), i );

        final int n = documents.size();

        // Step 1: Tokenize and compute term frequencies per document
        final List< Map< String, Integer > > docTermFreqs = new ArrayList<>( n );
        final Map< String, Integer > docFreq = new HashMap<>(); // term -> # docs containing it

        try( final Analyzer analyzer = new EnglishAnalyzer() ) {
            for( final String doc : documents ) {
                final Map< String, Integer > tf = tokenize( analyzer, doc );
                docTermFreqs.add( tf );
                for( final String term : tf.keySet() ) {
                    docFreq.merge( term, 1, Integer::sum );
                }
            }
        }

        // Step 2: Compute IDF for each term
        final Map< String, Double > idf = new HashMap<>();
        for( final var entry : docFreq.entrySet() ) {
            idf.put( entry.getKey(), Math.log( ( double ) n / entry.getValue() ) + 1.0 );
        }

        // Step 3: Build feature-hashed TF-IDF vectors
        vectors = new float[ n ][ DIMENSION ];
        for( int i = 0; i < n; i++ ) {
            final Map< String, Integer > tf = docTermFreqs.get( i );
            for( final var entry : tf.entrySet() ) {
                final int bucket = ( entry.getKey().hashCode() & 0x7FFFFFFF ) % DIMENSION;
                final double tfidf = entry.getValue() * idf.getOrDefault( entry.getKey(), 1.0 );
                vectors[ i ][ bucket ] += ( float ) tfidf;
            }
            normalize( vectors[ i ] );
        }
    }

    /**
     * Restores a model from previously saved vectors (loaded from database).
     */
    public void restore( final List< String > names, final List< float[] > savedVectors ) {
        this.entityNames = new ArrayList<>( names );
        this.entityIndex = new HashMap<>();
        for( int i = 0; i < names.size(); i++ ) entityIndex.put( names.get( i ), i );
        this.vectors = savedVectors.toArray( new float[ 0 ][] );
    }

    public float[] getVector( final int entityId ) {
        return vectors[ entityId ];
    }

    public double similarity( final int a, final int b ) {
        // Vectors are L2-normalized, so cosine = dot product
        double dot = 0;
        for( int d = 0; d < DIMENSION; d++ ) {
            dot += vectors[ a ][ d ] * vectors[ b ][ d ];
        }
        return dot;
    }

    public List< ScoredPair > mostSimilar( final int entityId, final int topK ) {
        final PriorityQueue< ScoredPair > pq = new PriorityQueue<>(
            Comparator.comparingDouble( ScoredPair::score ) );
        for( int i = 0; i < vectors.length; i++ ) {
            if( i == entityId ) continue;
            final double sim = similarity( entityId, i );
            if( pq.size() < topK ) {
                pq.add( new ScoredPair( entityNames.get( i ), sim ) );
            } else if( sim > pq.peek().score() ) {
                pq.poll();
                pq.add( new ScoredPair( entityNames.get( i ), sim ) );
            }
        }
        final List< ScoredPair > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( ScoredPair::score ).reversed() );
        return result;
    }

    /**
     * Finds the top-K most similar page pairs across the entire corpus.
     * Brute-force all-pairs comparison using cosine similarity (dot product on L2-normalized vectors).
     */
    public List< SimilarPagePair > topSimilarPairs( final int topK ) {
        if( vectors == null || vectors.length < 2 ) return List.of();
        final PriorityQueue< SimilarPagePair > pq = new PriorityQueue<>(
            Comparator.comparingDouble( SimilarPagePair::score ) );
        for( int i = 0; i < vectors.length; i++ ) {
            for( int j = i + 1; j < vectors.length; j++ ) {
                final double sim = similarity( i, j );
                if( pq.size() < topK ) {
                    pq.add( new SimilarPagePair( entityNames.get( i ), entityNames.get( j ), sim ) );
                } else if( sim > pq.peek().score() ) {
                    pq.poll();
                    pq.add( new SimilarPagePair( entityNames.get( i ), entityNames.get( j ), sim ) );
                }
            }
        }
        final List< SimilarPagePair > result = new ArrayList<>( pq );
        result.sort( Comparator.comparingDouble( SimilarPagePair::score ).reversed() );
        return result;
    }

    public int entityId( final String name ) {
        return entityIndex.getOrDefault( name, -1 );
    }

    public int getEntityCount() {
        return entityNames != null ? entityNames.size() : 0;
    }

    public int getDimension() {
        return DIMENSION;
    }

    public List< String > getEntityNames() {
        return entityNames;
    }

    // ---- Internal ----

    private Map< String, Integer > tokenize( final Analyzer analyzer, final String text ) {
        final Map< String, Integer > tf = new HashMap<>();
        if( text == null || text.isBlank() ) return tf;
        try( final TokenStream stream = analyzer.tokenStream( "content", new StringReader( text ) ) ) {
            final CharTermAttribute termAttr = stream.addAttribute( CharTermAttribute.class );
            stream.reset();
            while( stream.incrementToken() ) {
                tf.merge( termAttr.toString(), 1, Integer::sum );
            }
            stream.end();
        } catch( final IOException e ) {
            // StringReader won't throw IOException in practice
        }
        return tf;
    }

    private static void normalize( final float[] vec ) {
        double norm = 0;
        for( final float v : vec ) norm += v * v;
        if( norm == 0 ) return;
        norm = Math.sqrt( norm );
        for( int i = 0; i < vec.length; i++ ) vec[ i ] /= ( float ) norm;
    }
}

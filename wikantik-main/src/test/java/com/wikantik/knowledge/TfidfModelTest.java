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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TfidfModelTest {

    @Test
    void identicalDocumentsHaveHighSimilarity() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B" ), List.of(
            "knowledge graph embeddings for link prediction",
            "knowledge graph embeddings for link prediction"
        ) );
        final double sim = model.similarity( 0, 1 );
        assertTrue( sim > 0.99, "Identical docs should have similarity ~1.0, got " + sim );
    }

    @Test
    void disjointDocumentsHaveLowSimilarity() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B" ), List.of(
            "knowledge graph embeddings link prediction neural network",
            "chocolate cake recipe baking flour sugar butter"
        ) );
        final double sim = model.similarity( 0, 1 );
        assertTrue( sim < 0.1, "Disjoint docs should have low similarity, got " + sim );
    }

    @Test
    void mostSimilarReturnsCorrectOrder() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B", "C" ), List.of(
            "machine learning algorithms for classification",
            "machine learning models for prediction",
            "chocolate cake recipe baking flour"
        ) );
        final var similar = model.mostSimilar( 0, 2 );
        assertEquals( 2, similar.size() );
        assertEquals( "B", similar.get( 0 ).name() );
        assertTrue( similar.get( 0 ).score() > similar.get( 1 ).score() );
    }

    @Test
    void emptyDocumentProducesZeroVector() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B" ), List.of( "", "some actual content here" ) );
        final float[] vec = model.getVector( 0 );
        assertNotNull( vec );
        assertEquals( TfidfModel.DIMENSION, vec.length );
        float sum = 0;
        for( final float v : vec ) sum += Math.abs( v );
        assertEquals( 0f, sum, 1e-6f, "Empty doc should have zero vector" );
    }

    @Test
    void dimensionIsAlwaysFixed() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A" ), List.of( "hello world" ) );
        assertEquals( TfidfModel.DIMENSION, model.getDimension() );
        assertEquals( TfidfModel.DIMENSION, model.getVector( 0 ).length );
    }

    @Test
    void unknownEntityReturnsNegativeId() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A" ), List.of( "hello" ) );
        assertEquals( -1, model.entityId( "Unknown" ) );
    }

    @Test
    void restoreReconstructsModel() {
        final TfidfModel original = new TfidfModel();
        original.build( List.of( "A", "B" ), List.of(
            "graph algorithms traversal",
            "baking recipes dessert"
        ) );

        final TfidfModel restored = new TfidfModel();
        restored.restore( List.of( "A", "B" ), List.of(
            original.getVector( 0 ).clone(), original.getVector( 1 ).clone()
        ) );

        assertEquals( original.similarity( 0, 1 ), restored.similarity( 0, 1 ), 1e-6 );
    }

    @Test
    void stemmingReducesWordForms() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B" ), List.of(
            "computing algorithms computational algorithmic",
            "computed algorithm computation"
        ) );
        final double sim = model.similarity( 0, 1 );
        assertTrue( sim > 0.8, "Stemmed word forms should be highly similar, got " + sim );
    }
}

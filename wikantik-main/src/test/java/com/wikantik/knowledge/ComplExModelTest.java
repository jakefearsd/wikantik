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

import com.wikantik.knowledge.ComplExModel.Prediction;
import com.wikantik.knowledge.ComplExModel.Triple;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComplExModelTest {

    // Build a graph with a clear structural pattern:
    //   Multiple services depend on shared infrastructure.
    //   ServiceA, ServiceB, ServiceC all depend-on DatabaseX.
    //   ServiceA, ServiceB also depend-on CacheY.
    //   ServiceA, ServiceC also depend-on QueueZ.
    //   We hold out ServiceC --depends-on--> CacheY and expect the model to predict it.

    private static final List< String > ENTITIES = List.of(
        "ServiceA", "ServiceB", "ServiceC", "ServiceD",
        "DatabaseX", "CacheY", "QueueZ", "MonitorW"
    );
    private static final List< String > RELATIONS = List.of( "depends-on", "related" );
    private static final List< Triple > TRIPLES = List.of(
        new Triple( 0, 0, 4 ),  // ServiceA depends-on DatabaseX
        new Triple( 1, 0, 4 ),  // ServiceB depends-on DatabaseX
        new Triple( 2, 0, 4 ),  // ServiceC depends-on DatabaseX
        new Triple( 3, 0, 4 ),  // ServiceD depends-on DatabaseX
        new Triple( 0, 0, 5 ),  // ServiceA depends-on CacheY
        new Triple( 1, 0, 5 ),  // ServiceB depends-on CacheY
        new Triple( 3, 0, 5 ),  // ServiceD depends-on CacheY
        new Triple( 0, 0, 6 ),  // ServiceA depends-on QueueZ
        new Triple( 2, 0, 6 ),  // ServiceC depends-on QueueZ
        new Triple( 0, 1, 1 ),  // ServiceA related ServiceB
        new Triple( 1, 1, 0 ),  // ServiceB related ServiceA
        new Triple( 2, 1, 3 )   // ServiceC related ServiceD
    );
    // Held-out: ServiceC depends-on CacheY (index 2, 0, 5)

    private ComplExModel model;

    @BeforeEach
    void trainModel() {
        model = new ComplExModel();
        model.train( ENTITIES, RELATIONS, TRIPLES, 50, 300, 0.05f, 10, 1.0f );
    }

    @Test
    void knownTriplesScoreHigherThanRandom() {
        final double knownScore = model.score( 0, 0, 4 ); // ServiceA depends-on DatabaseX
        final double randomScore = model.score( 4, 0, 0 ); // DatabaseX depends-on ServiceA (not in training)
        assertTrue( knownScore > randomScore,
            "Known triple should score higher than reversed triple. known=" + knownScore + " random=" + randomScore );
    }

    @Test
    void predictMissingEdge() {
        // Held-out: ServiceC depends-on CacheY. The model should score this higher than a random triple.
        final int serviceC = model.entityId( "ServiceC" );
        final int dependsOn = model.relationId( "depends-on" );
        final int cacheY = model.entityId( "CacheY" );
        final int monitorW = model.entityId( "MonitorW" );

        final double heldOutScore = model.score( serviceC, dependsOn, cacheY );
        final double randomScore = model.score( serviceC, dependsOn, monitorW );
        assertTrue( heldOutScore > randomScore,
            "Held-out triple ServiceC->CacheY (" + heldOutScore +
            ") should score higher than random ServiceC->MonitorW (" + randomScore + ")" );
    }

    @Test
    void similarServicesCluster() {
        // Services that share dependencies should be more similar to each other than to infrastructure
        final int sA = model.entityId( "ServiceA" );
        final int sB = model.entityId( "ServiceB" );
        final int monW = model.entityId( "MonitorW" );

        final double simAB = model.similarity( sA, sB );
        final double simAMon = model.similarity( sA, monW );
        assertTrue( simAB > simAMon,
            "ServiceA-ServiceB similarity (" + simAB + ") should exceed ServiceA-MonitorW (" + simAMon + ")" );
    }

    @Test
    void mostSimilarReturnsCorrectCount() {
        final int sA = model.entityId( "ServiceA" );
        final List< Prediction > similar = model.mostSimilar( sA, 3 );
        assertEquals( 3, similar.size() );
        // Should not include self
        assertTrue( similar.stream().noneMatch( p -> p.entityName().equals( "ServiceA" ) ) );
        // Should be in descending order
        for( int i = 1; i < similar.size(); i++ ) {
            assertTrue( similar.get( i - 1 ).score() >= similar.get( i ).score() );
        }
    }

    @Test
    void entityAndRelationLookup() {
        assertEquals( 0, model.entityId( "ServiceA" ) );
        assertEquals( -1, model.entityId( "NonExistent" ) );
        assertEquals( 0, model.relationId( "depends-on" ) );
        assertEquals( -1, model.relationId( "unknown-rel" ) );
        assertEquals( 8, model.getEntityCount() );
        assertEquals( 2, model.getRelationCount() );
        assertEquals( 50, model.getDimension() );
    }

    @Test
    void embeddingsContainNoNaN() {
        for( int i = 0; i < model.getEntityCount(); i++ ) {
            for( final float v : model.getEntityReal( i ) ) {
                assertFalse( Float.isNaN( v ), "NaN in entityReal[" + i + "] (" + model.getEntityNames().get( i ) + ")" );
                assertFalse( Float.isInfinite( v ), "Infinity in entityReal[" + i + "]" );
            }
            for( final float v : model.getEntityImag( i ) ) {
                assertFalse( Float.isNaN( v ), "NaN in entityImag[" + i + "] (" + model.getEntityNames().get( i ) + ")" );
                assertFalse( Float.isInfinite( v ), "Infinity in entityImag[" + i + "]" );
            }
        }
        for( int i = 0; i < model.getRelationCount(); i++ ) {
            for( final float v : model.getRelationReal( i ) ) {
                assertFalse( Float.isNaN( v ), "NaN in relationReal[" + i + "]" );
                assertFalse( Float.isInfinite( v ), "Infinity in relationReal[" + i + "]" );
            }
            for( final float v : model.getRelationImag( i ) ) {
                assertFalse( Float.isNaN( v ), "NaN in relationImag[" + i + "]" );
                assertFalse( Float.isInfinite( v ), "Infinity in relationImag[" + i + "]" );
            }
        }
    }

    @Test
    void highEpochTrainingProducesFiniteEmbeddings() {
        // Stress test: aggressive lr + many epochs on a dense graph to trigger float overflow
        // in normalizeRows when relation embeddings are not normalized.
        // Before the fix, unbounded relation growth amplified entity gradients until
        // float accumulation in normalizeRows overflowed to Infinity → NaN.
        final List< String > entities = new java.util.ArrayList<>();
        for( int i = 0; i < 30; i++ ) entities.add( "E" + i );
        final List< String > relations = List.of( "r0", "r1" );
        final List< Triple > triples = new java.util.ArrayList<>();
        for( int i = 0; i < 30; i++ ) {
            triples.add( new Triple( i, 0, ( i + 1 ) % 30 ) );
            triples.add( new Triple( i, 1, ( i + 7 ) % 30 ) );
        }
        final ComplExModel stress = new ComplExModel();
        stress.train( entities, relations, triples, 20, 200, 0.05f, 15, 1.0f );
        for( int i = 0; i < stress.getEntityCount(); i++ ) {
            for( final float v : stress.getEntityReal( i ) ) {
                assertTrue( Float.isFinite( v ), "Non-finite in entityReal[" + i + "] after stress training" );
            }
            for( final float v : stress.getEntityImag( i ) ) {
                assertTrue( Float.isFinite( v ), "Non-finite in entityImag[" + i + "] after stress training" );
            }
        }
        for( int i = 0; i < stress.getRelationCount(); i++ ) {
            for( final float v : stress.getRelationReal( i ) ) {
                assertTrue( Float.isFinite( v ), "Non-finite in relationReal[" + i + "] after stress training" );
            }
            for( final float v : stress.getRelationImag( i ) ) {
                assertTrue( Float.isFinite( v ), "Non-finite in relationImag[" + i + "] after stress training" );
            }
        }
    }

    @Test
    void emptyTriplesDoesNotFail() {
        final ComplExModel empty = new ComplExModel();
        empty.train( ENTITIES, RELATIONS, List.of(), 20, 10, 0.01f, 3, 1.0f );
        assertEquals( 8, empty.getEntityCount() );
        assertEquals( 20, empty.getDimension() );
    }

    @Test
    void predictHeadsWorks() {
        // Who depends-on DatabaseX? Should rank ServiceA/B/C highly.
        final int dependsOn = model.relationId( "depends-on" );
        final int dbX = model.entityId( "DatabaseX" );
        final List< Prediction > heads = model.predictHeads( dependsOn, dbX, 3 );

        assertFalse( heads.isEmpty() );
        final boolean hasService = heads.stream().anyMatch(
            p -> p.entityName().startsWith( "Service" ) );
        assertTrue( hasService, "A service should be predicted as head. Got: " + heads );
    }
}

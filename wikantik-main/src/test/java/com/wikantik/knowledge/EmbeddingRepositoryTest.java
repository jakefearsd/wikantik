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

import com.wikantik.PostgresTestContainer;
import com.wikantik.knowledge.ComplExModel.Triple;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class EmbeddingRepositoryTest {

    private static DataSource dataSource;
    private EmbeddingRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_embeddings" );
        }
        repo = new EmbeddingRepository( dataSource );
    }

    @Test
    void saveAndLoadRoundTrip() {
        final ComplExModel original = trainSmallModel();
        final Map< String, UUID > uuids = Map.of(
            "A", UUID.randomUUID(), "B", UUID.randomUUID(), "C", UUID.randomUUID() );

        repo.saveEmbeddings( 1, original, uuids );

        final ComplExModel loaded = repo.loadLatestModel();
        assertNotNull( loaded );
        assertEquals( original.getEntityCount(), loaded.getEntityCount() );
        assertEquals( original.getRelationCount(), loaded.getRelationCount() );
        assertEquals( original.getDimension(), loaded.getDimension() );

        // Verify embedding values are preserved
        for( int i = 0; i < original.getEntityCount(); i++ ) {
            assertArrayEquals( original.getEntityReal( i ), loaded.getEntityReal( i ), 1e-5f );
            assertArrayEquals( original.getEntityImag( i ), loaded.getEntityImag( i ), 1e-5f );
        }

        // Verify scores are preserved
        final double origScore = original.score( 0, 0, 1 );
        final double loadedScore = loaded.score( 0, 0, 1 );
        assertEquals( origScore, loadedScore, 1e-4 );
    }

    @Test
    void loadReturnsNullWhenEmpty() {
        assertNull( repo.loadLatestModel() );
    }

    @Test
    void getLatestModelVersionReturnsCorrectVersion() {
        assertEquals( -1, repo.getLatestModelVersion() );

        final ComplExModel model = trainSmallModel();
        repo.saveEmbeddings( 1, model, Map.of() );
        assertEquals( 1, repo.getLatestModelVersion() );

        repo.saveEmbeddings( 2, model, Map.of() );
        assertEquals( 2, repo.getLatestModelVersion() );
    }

    @Test
    void deleteOldVersions() {
        final ComplExModel model = trainSmallModel();
        repo.saveEmbeddings( 1, model, Map.of() );
        repo.saveEmbeddings( 2, model, Map.of() );
        repo.saveEmbeddings( 3, model, Map.of() );

        repo.deleteOldVersions( 3 );
        assertEquals( 3, repo.getLatestModelVersion() );

        // Version 3 should still be loadable
        final ComplExModel loaded = repo.loadLatestModel();
        assertNotNull( loaded );
        assertEquals( model.getEntityCount(), loaded.getEntityCount() );
    }

    private ComplExModel trainSmallModel() {
        final ComplExModel model = new ComplExModel();
        model.train(
            List.of( "A", "B", "C" ),
            List.of( "rel" ),
            List.of( new Triple( 0, 0, 1 ), new Triple( 1, 0, 2 ) ),
            20, 50, 0.05f, 3, 1.0f
        );
        return model;
    }
}

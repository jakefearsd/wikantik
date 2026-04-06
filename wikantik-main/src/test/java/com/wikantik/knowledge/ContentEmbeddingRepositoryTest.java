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
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class ContentEmbeddingRepositoryTest {

    private static DataSource dataSource;
    private ContentEmbeddingRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_content_embeddings" );
        }
        repo = new ContentEmbeddingRepository( dataSource );
    }

    @Test
    void saveAndLoadRoundTrip() {
        final TfidfModel original = buildModel();
        final Map< String, UUID > uuids = Map.of(
            "A", UUID.randomUUID(), "B", UUID.randomUUID() );

        repo.saveEmbeddings( 1, original, uuids );

        final TfidfModel loaded = repo.loadLatestModel();
        assertNotNull( loaded );
        assertEquals( original.getEntityCount(), loaded.getEntityCount() );
        assertEquals( original.getDimension(), loaded.getDimension() );

        // Verify vectors are preserved
        for( int i = 0; i < original.getEntityCount(); i++ ) {
            assertArrayEquals( original.getVector( i ), loaded.getVector( i ), 1e-5f );
        }

        // Verify similarity is preserved
        assertEquals(
            original.similarity( 0, 1 ),
            loaded.similarity( 0, 1 ),
            1e-5
        );
    }

    @Test
    void loadReturnsNullWhenEmpty() {
        assertNull( repo.loadLatestModel() );
    }

    @Test
    void getLatestModelVersionReturnsCorrectVersion() {
        assertEquals( -1, repo.getLatestModelVersion() );

        final TfidfModel model = buildModel();
        repo.saveEmbeddings( 1, model, Map.of() );
        assertEquals( 1, repo.getLatestModelVersion() );

        repo.saveEmbeddings( 2, model, Map.of() );
        assertEquals( 2, repo.getLatestModelVersion() );
    }

    @Test
    void deleteOldVersions() {
        final TfidfModel model = buildModel();
        repo.saveEmbeddings( 1, model, Map.of() );
        repo.saveEmbeddings( 2, model, Map.of() );
        repo.saveEmbeddings( 3, model, Map.of() );

        repo.deleteOldVersions( 3 );
        assertEquals( 3, repo.getLatestModelVersion() );

        final TfidfModel loaded = repo.loadLatestModel();
        assertNotNull( loaded );
        assertEquals( model.getEntityCount(), loaded.getEntityCount() );
    }

    private TfidfModel buildModel() {
        final TfidfModel model = new TfidfModel();
        model.build( List.of( "A", "B" ), List.of(
            "knowledge graph embeddings link prediction",
            "recipe baking chocolate cake flour"
        ) );
        return model;
    }
}

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
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression lock: KgEdgeRepository.upsertEdge always stamps tier='human' and
 * provenance_proposal_id=NULL regardless of the caller's intent. The admin-UI
 * edge curation flow depends on this — admin-curated edges must not be
 * subject to machine-tier re-extraction overwrite.
 */
@Testcontainers( disabledWithoutDocker = true )
class KgEdgeProvenanceStampingTest {

    private static DataSource dataSource;
    private KgEdgeRepository edges;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        edges = new KgEdgeRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    @Test
    void upsertEdgeAlwaysStampsHumanTierAndNullProposalId() {
        final UUID a = nodes.upsertNode( "StampA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "StampB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();

        final KgEdge e = edges.upsertEdge( a, b, "related",
                Provenance.HUMAN_CURATED, Map.of( "k", "v" ) );

        assertNotNull( e );
        assertEquals( "human", e.tier() );
        assertNull( e.provenanceProposalId() );
        assertEquals( Provenance.HUMAN_CURATED, e.provenance() );
    }
}

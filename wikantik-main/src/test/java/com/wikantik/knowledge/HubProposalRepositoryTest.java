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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubProposalRepositoryTest {

    private static DataSource dataSource;
    private HubProposalRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = new HubProposalRepository( dataSource );
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
        }
    }

    @Test
    void insertAndQueryProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );

        final List< HubProposalRepository.HubProposal > pending =
            repo.listProposals( "pending", null, 50, 0 );
        assertEquals( 1, pending.size() );
        assertEquals( "TechHub", pending.get( 0 ).hubName() );
        assertEquals( "MyArticle", pending.get( 0 ).pageName() );
        assertEquals( 92.5, pending.get( 0 ).percentileScore(), 0.01 );
    }

    @Test
    void approveProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        repo.updateStatus( pending.get( 0 ).id(), "approved", "admin", null );

        final List< HubProposalRepository.HubProposal > approved = repo.listProposals( "approved", null, 50, 0 );
        assertEquals( 1, approved.size() );
        assertEquals( "approved", approved.get( 0 ).status() );
    }

    @Test
    void rejectProposal() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        repo.updateStatus( pending.get( 0 ).id(), "rejected", "admin", "Not relevant" );

        assertTrue( repo.isRejected( "TechHub", "MyArticle" ) );
    }

    @Test
    void duplicateProposalIsSkipped() {
        repo.insertProposal( "TechHub", "MyArticle", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "MyArticle", 0.90, 95.0 ); // duplicate

        final List< HubProposalRepository.HubProposal > all = repo.listProposals( "pending", null, 50, 0 );
        assertEquals( 1, all.size() );
    }

    @Test
    void filterByHub() {
        repo.insertProposal( "TechHub", "ArticleA", 0.85, 92.5 );
        repo.insertProposal( "SciHub", "ArticleB", 0.80, 88.0 );

        final List< HubProposalRepository.HubProposal > techOnly =
            repo.listProposals( "pending", "TechHub", 50, 0 );
        assertEquals( 1, techOnly.size() );
        assertEquals( "TechHub", techOnly.get( 0 ).hubName() );
    }

    @Test
    void saveCentroid() {
        final float[] centroid = new float[ 512 ];
        centroid[ 0 ] = 1.0f;
        centroid[ 1 ] = 0.5f;

        repo.saveCentroid( "TechHub", centroid, 1, 5 );

        final float[] loaded = repo.loadCentroid( "TechHub" );
        assertNotNull( loaded );
        assertEquals( 1.0f, loaded[ 0 ], 0.001f );
        assertEquals( 0.5f, loaded[ 1 ], 0.001f );
    }

    @Test
    void countPending() {
        repo.insertProposal( "TechHub", "A", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "B", 0.80, 88.0 );
        assertEquals( 2, repo.countByStatus( "pending" ) );
    }

    @Test
    void bulkApprove() {
        repo.insertProposal( "TechHub", "A", 0.85, 92.5 );
        repo.insertProposal( "TechHub", "B", 0.80, 88.0 );
        final List< HubProposalRepository.HubProposal > pending = repo.listProposals( "pending", null, 50, 0 );
        final List< Integer > ids = pending.stream().map( HubProposalRepository.HubProposal::id ).toList();

        repo.bulkUpdateStatus( ids, "approved", "admin", null );

        assertEquals( 0, repo.countByStatus( "pending" ) );
        assertEquals( 2, repo.countByStatus( "approved" ) );
    }

    @Test
    void approveByThreshold() {
        repo.insertProposal( "TechHub", "A", 0.95, 97.0 );
        repo.insertProposal( "TechHub", "B", 0.80, 85.0 );

        final List< HubProposalRepository.HubProposal > above =
            repo.listProposalsAboveThreshold( 90.0 );
        assertEquals( 1, above.size() );
        assertEquals( "A", above.get( 0 ).pageName() );
    }
}

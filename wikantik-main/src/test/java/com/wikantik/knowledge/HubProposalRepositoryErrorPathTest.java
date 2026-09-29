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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HubProposalRepository} deliberately never rethrows a {@link SQLException} from its
 * read/status methods — each swallows it after a {@code LOG.warn} and returns a safe default
 * (empty list / false / 0 / null), except {@link HubProposalRepository#insertProposal} which
 * rethrows wrapped in a {@link RuntimeException} so batch callers can abort. A {@link DataSource}
 * that always fails {@code getConnection()} drives every method into its catch block without a
 * live database.
 */
class HubProposalRepositoryErrorPathTest {

    private HubProposalRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        repo = new HubProposalRepository( broken );
    }

    @Test
    void insertProposalRethrowsWrapped() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.insertProposal( "TechHub", "Page", 0.9, 95.0 ) );
        assertTrue( ex.getMessage().contains( "TechHub" ) );
    }

    @Test
    void listProposalsReturnsEmptyOnFailure() {
        assertEquals( List.of(), repo.listProposals( "pending", null, 50, 0 ) );
        assertEquals( List.of(), repo.listProposals( "pending", "TechHub", 50, 0 ) );
    }

    @Test
    void listProposalsAboveThresholdReturnsEmptyOnFailure() {
        assertEquals( List.of(), repo.listProposalsAboveThreshold( 90.0 ) );
    }

    @Test
    void updateStatusSwallowsFailure() {
        assertDoesNotThrow( () -> repo.updateStatus( 1, "approved", "admin", null ) );
    }

    @Test
    void bulkUpdateStatusSwallowsFailure() {
        assertDoesNotThrow( () -> repo.bulkUpdateStatus( List.of( 1, 2 ), "approved", "admin", null ) );
    }

    @Test
    void isRejectedReturnsFalseOnFailure() {
        assertFalse( repo.isRejected( "TechHub", "Page" ) );
    }

    @Test
    void existsReturnsFalseOnFailure() {
        assertFalse( repo.exists( "TechHub", "Page" ) );
    }

    @Test
    void countByStatusReturnsZeroOnFailure() {
        assertEquals( 0, repo.countByStatus( "pending" ) );
    }

    @Test
    void saveCentroidSwallowsFailure() {
        assertDoesNotThrow( () -> repo.saveCentroid( "TechHub", new float[] { 1f, 2f }, 1, 5 ) );
    }

    @Test
    void loadCentroidReturnsNullOnFailure() {
        assertNull( repo.loadCentroid( "TechHub" ) );
    }
}

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

import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives every generic {@code catch (SQLException e) { LOG.warn(...); throw/return ... }}
 * path in {@link KgNodeRepository} with a {@link DataSource} that always fails
 * {@code getConnection()}.
 */
class KgNodeRepositoryErrorPathTest {

    private KgNodeRepository nodes;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        nodes = new KgNodeRepository( broken );
    }

    @Test
    void upsertNodeWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> nodes.upsertNode( "X", "concept", "P", Provenance.HUMAN_AUTHORED, Map.of() ) );
        assertTrue( ex.getMessage().contains( "Failed to upsert node" ) );
    }

    @Test
    void getNodeWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.getNode( UUID.randomUUID() ) );
    }

    @Test
    void getNodeByNameWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.getNodeByName( "X" ) );
    }

    @Test
    void deleteNodeWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> nodes.deleteNode( UUID.randomUUID() ) );
        assertTrue( ex.getMessage().contains( "Failed to delete node" ) );
    }

    @Test
    void queryNodesWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.queryNodes( null, null, 10, 0 ) );
    }

    @Test
    void searchNodesWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.searchNodes( "q", null, 10, false ) );
    }

    @Test
    void searchNodesByTierWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> nodes.searchNodes( "q", null, 10, Tier.HUMAN ) );
        assertTrue( ex.getCause() instanceof SQLException, "must wrap the underlying SQLException" );
    }

    @Test
    void getAllNodesWrapsFailure() {
        assertThrows( RuntimeException.class, nodes::getAllNodes );
    }

    @Test
    void getAllNodesByTierWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.getAllNodes( Tier.MACHINE ) );
    }

    @Test
    void upsertNodeWithProvenanceWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> nodes.upsertNodeWithProvenance( "X", "concept", "P", Provenance.AI_INFERRED,
                        Map.of(), "machine", null ) );
        assertTrue( ex.getMessage().contains( "Failed to upsertNodeWithProvenance" ) );
    }

    @Test
    void findNodeIdsByProvenanceWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.findNodeIdsByProvenance( UUID.randomUUID() ) );
    }

    @Test
    void countNodesWithFilterWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.countNodesWithFilter( null, null ) );
    }

    @Test
    void listOrphanedNodesWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.listOrphanedNodes( null, 10, 0 ) );
    }

    @Test
    void countOrphanedNodesWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.countOrphanedNodes( null ) );
    }

    @Test
    void getNodeNamesSwallowsFailureAndReturnsPartialResult() {
        // getNodeNames uniquely logs-and-returns rather than rethrowing.
        assertEquals( Map.of(), nodes.getNodeNames( List.of( UUID.randomUUID() ) ) );
    }

    // ------------------------------------------------------------------ shared KgJdbcSupport helpers

    @Test
    void getDistinctNodeTypesWrapsFailure() {
        assertThrows( RuntimeException.class, nodes::getDistinctNodeTypes );
    }

    @Test
    void countNodesWrapsFailure() {
        assertThrows( RuntimeException.class, nodes::countNodes );
    }

    @Test
    void countStubNodesWrapsFailure() {
        assertThrows( RuntimeException.class, nodes::countStubNodes );
    }

    @Test
    void deleteNodesByProvenanceWrapsFailure() {
        assertThrows( RuntimeException.class, () -> nodes.deleteNodesByProvenance( UUID.randomUUID() ) );
    }
}

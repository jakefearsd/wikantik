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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KgNodeRepository#ensureNodeWithProvenance} is insert-if-absent: it creates a missing
 * node and leaves an existing one completely unchanged, even when two callers race to create
 * the same missing node.
 */
@RequiresPostgres
class KgNodeRepositoryEnsureNodeTest {

    private static final int RACERS = 8;

    private DataSource ds;
    private KgNodeRepository nodes;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "kg_edges", "kg_nodes", "kg_excluded_pages" );
        ds = PostgresTestDb.createDataSource();
        nodes = new KgNodeRepository( ds );
    }

    @Test
    void createsAMissingNodeWithTheSuppliedAttributes() {
        final UUID proposalId = UUID.randomUUID();

        final KgNode created = nodes.ensureNodeWithProvenance( "Fresh", "concept", "SomePage",
            Provenance.AI_INFERRED, Map.of( "k", "v" ), "machine", proposalId );

        assertNotNull( created );
        assertEquals( "Fresh", created.name() );
        assertEquals( "concept", created.nodeType() );
        assertEquals( "SomePage", created.sourcePage() );
        assertEquals( Provenance.AI_INFERRED, created.provenance() );
        assertEquals( Map.of( "k", "v" ), created.properties() );
        assertEquals( "machine", created.tier() );
        assertEquals( proposalId, created.provenanceProposalId() );
    }

    @Test
    void leavesAnExistingNodeCompletelyUnchangedAndReturnsIt() {
        nodes.upsertNode( "Existing", "technology", "OriginalPage", Provenance.HUMAN_CURATED,
            Map.of( "aliases", "ex" ) );
        final KgNode before = nodes.getNodeByName( "Existing", true );

        final KgNode returned = nodes.ensureNodeWithProvenance( "Existing", "concept", "OtherPage",
            Provenance.AI_INFERRED, Map.of(), "machine", UUID.randomUUID() );
        final KgNode after = nodes.getNodeByName( "Existing", true );

        assertEquals( before, after, "every column, including modified, must be untouched" );
        assertEquals( before, returned, "the pre-existing row is what comes back" );
        assertEquals( 1L, nodes.countNodes() );
    }

    @Test
    void returnsNullWhenTheInclusionFilterHidesTheRow() throws Exception {
        nodes.upsertNode( "Hidden", "technology", "ExcludedPage", Provenance.HUMAN_AUTHORED, Map.of() );
        try ( var conn = ds.getConnection(); var st = conn.createStatement() ) {
            st.execute( "INSERT INTO kg_excluded_pages ( page_name, reason ) VALUES ( 'ExcludedPage', 'cluster_policy' )" );
        }

        assertNull( nodes.ensureNodeWithProvenance( "Hidden", "concept", "P", Provenance.AI_INFERRED,
            Map.of(), "machine", null ), "null-on-filtered: the row is written but hidden by the KG inclusion policy" );
        assertEquals( "technology", nodes.getNodeByName( "Hidden", true ).nodeType() );
    }

    @Test
    void concurrentCreatorsOfTheSameMissingNodeAllSucceedAndShareOneRow() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool( RACERS );
        try {
            final CountDownLatch start = new CountDownLatch( 1 );
            final List< Future< KgNode > > futures = new ArrayList<>();
            for ( int i = 0; i < RACERS; i++ ) {
                final UUID proposalId = UUID.randomUUID();
                final String type = "type" + i;
                futures.add( pool.submit( () -> {
                    start.await();
                    return nodes.ensureNodeWithProvenance( "Contested", type, "P",
                        Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
                } ) );
            }
            start.countDown();

            final List< KgNode > results = new ArrayList<>();
            for ( final Future< KgNode > f : futures ) {
                results.add( f.get( 30, TimeUnit.SECONDS ) ); // rethrows any racer's failure
            }

            assertEquals( 1L, nodes.countNodes(), "exactly one row, no duplicate-key failure" );
            final KgNode winner = nodes.getNodeByName( "Contested", true );
            for ( final KgNode seen : results ) {
                assertNotNull( seen );
                assertEquals( winner.id(), seen.id(), "every racer is handed the same row" );
            }
            assertTrue( winner.nodeType().startsWith( "type" ) );
            assertEquals( winner, nodes.getNodeByName( "Contested", true ), "and nobody clobbered it afterwards" );
        } finally {
            pool.shutdownNow();
        }
    }
}

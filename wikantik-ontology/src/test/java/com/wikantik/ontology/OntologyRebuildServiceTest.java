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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OntologyRebuildServiceTest {

    private static final UUID N1 = UUID.fromString( "00000000-0000-0000-0000-0000000000d1" );
    private static final UUID N2 = UUID.fromString( "00000000-0000-0000-0000-0000000000d2" );

    @Test
    void fullRebuildMaterializesEntitiesPagesConceptsAndInfersTypes() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();

        final List< KgNode > nodes = List.of(
                new KgNode( N1, "Backpropagation", "technology", "Backprop.md", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ),
                new KgNode( N2, "Gradient", "concept", null, Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< KgEdge > edges = List.of(
                new KgEdge( UUID.randomUUID(), N1, N2, "uses", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< PageRecord > pages = List.of(
                new PageRecord( "01CANON0000000000000000001", "Backprop.md", "Backprop", "article",
                        "ml", List.of( "neural-networks" ), "x", "2026-01-01", "researcher" ) );

        final OntologyRebuildService svc = new OntologyRebuildService();
        final int graphs = svc.rebuild( mgr, nodes, edges, pages );

        // 2 entities + 1 page + 2 concepts (cluster "ml" + tag "neural-networks") = 5 named graphs.
        assertTrue( graphs >= 5, "expected >= 5 named graphs, got " + graphs );

        assertTrue( mgr.namedGraphExists( Iris.entity( N1 ) ) );

        // subClassOf inference: the Technology entity is a schema:Thing.
        final Model inf = mgr.inferenceSnapshot();
        assertTrue( inf.contains(
                ResourceFactory.createResource( Iris.entity( N1 ) ), RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ) );
    }

    @Test
    void rebuildIsIdempotent() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< KgNode > nodes = List.of(
                new KgNode( N1, "X", "concept", null, Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final OntologyRebuildService svc = new OntologyRebuildService();
        final int first  = svc.rebuild( mgr, nodes, List.of(), List.of() );
        final int second = svc.rebuild( mgr, nodes, List.of(), List.of() );
        assertTrue( first == second, "rebuild graph count is stable across runs" );
    }

    private static KgNode node( final UUID id ) {
        return new KgNode( id, "N" + id, "concept", null, Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
    }

    private static List< KgNode > nodes( final int n ) {
        final List< KgNode > out = new ArrayList<>();
        for ( int i = 0; i < n; i++ ) {
            out.add( node( new UUID( 7, i ) ) );
        }
        return out;
    }

    private static List< PageRecord > pages( final int n ) {
        final List< PageRecord > out = new ArrayList<>();
        for ( int i = 0; i < n; i++ ) {
            out.add( new PageRecord( String.format( "01CANON%019d", i ), "Page" + i, "Page " + i, "article",
                    "cluster" + i, List.of( "tag" + i ), "s", "2026-01-01", null ) );
        }
        return out;
    }

    /** Asserts the store holds exactly the graphs a rebuild of {@code nodes}/{@code pages} projects. */
    private static void assertAboxHolds( final OntologyModelManager mgr, final List< KgNode > nodes,
                                         final List< PageRecord > pages, final int graphs ) {
        assertEquals( graphs, mgr.namedGraphCount(), "named graph count after rebuild" );
        for ( final KgNode n : nodes ) {
            assertTrue( mgr.namedGraphExists( Iris.entity( n.id() ) ), "entity graph " + n.id() );
        }
        for ( final PageRecord p : pages ) {
            assertTrue( mgr.namedGraphExists( Iris.page( p.canonicalId() ) ), "page graph " + p.slug() );
        }
    }

    @Test
    void aFullRebuildCommitsOneWriteTransactionNotOnePerGraph() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< KgNode > nodes = nodes( 40 );
        final List< PageRecord > pages = pages( 10 );
        final long before = mgr.committedWriteTransactions();

        final int graphs = new OntologyRebuildService().rebuild( mgr, nodes, List.of(), pages );

        assertEquals( 70, graphs, "40 entities + 10 pages + 10 tag + 10 cluster concepts" );
        assertEquals( 1, mgr.committedWriteTransactions() - before,
                "the clear and every graph write must share ONE write transaction (each commit is a TDB2 sync round)" );
        assertAboxHolds( mgr, nodes, pages, graphs );
    }

    @Test
    void aFullRebuildOnTdb2CommitsOnceAndReplacesTheWholeAbox( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();
            final OntologyRebuildService svc = new OntologyRebuildService();
            svc.rebuild( mgr, nodes( 30 ), List.of(), pages( 5 ) );
            final List< KgNode > next = nodes( 20 );   // 10 entities disappeared
            final long before = mgr.committedWriteTransactions();

            final int graphs = svc.rebuild( mgr, next, List.of(), pages( 5 ) );

            assertEquals( 1, mgr.committedWriteTransactions() - before, "one TDB2 commit for the whole rebuild" );
            assertAboxHolds( mgr, next, pages( 5 ), graphs );
            assertFalse( mgr.namedGraphExists( Iris.entity( new UUID( 7, 25 ) ) ), "vanished entity is pruned" );
        } finally {
            mgr.close();
        }
    }

    @Test
    void rebuildingIdenticalContentCommitsNothing( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();
            final OntologyRebuildService svc = new OntologyRebuildService();
            svc.rebuild( mgr, nodes( 30 ), List.of(), pages( 5 ) );
            final long before = mgr.committedWriteTransactions();

            final int graphs = svc.rebuild( mgr, nodes( 30 ), List.of(), pages( 5 ) );

            assertEquals( 0, mgr.committedWriteTransactions() - before,
                    "an unchanged A-Box must not be rewritten — every rewrite strands copy-on-write blocks" );
            assertAboxHolds( mgr, nodes( 30 ), pages( 5 ), graphs );
        } finally {
            mgr.close();
        }
    }

    @Test
    void readersKeepSeeingThePreviousAboxUntilTheRebuildCommits() throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final UUID old = new UUID( 9, 1 );
        final UUID first = new UUID( 9, 2 );
        final UUID second = new UUID( 9, 3 );
        final OntologyRebuildService svc = new OntologyRebuildService();
        svc.rebuild( mgr, List.of( node( old ) ), List.of(), List.of() );

        // Iterating the second node probes the store from another thread, mid-rebuild.
        final boolean[] seen = new boolean[ 2 ];
        final List< KgNode > probing = new AbstractList<>() {
            @Override public KgNode get( final int i ) {
                if ( i == 1 ) {
                    try {
                        seen[ 0 ] = CompletableFuture.supplyAsync( () -> mgr.namedGraphExists( Iris.entity( old ) ) )
                                .get( 10, TimeUnit.SECONDS );
                        seen[ 1 ] = CompletableFuture.supplyAsync( () -> mgr.namedGraphExists( Iris.entity( first ) ) )
                                .get( 10, TimeUnit.SECONDS );
                    } catch ( final Exception e ) {
                        throw new IllegalStateException( e );
                    }
                }
                return node( i == 0 ? first : second );
            }
            @Override public int size() {
                return 2;
            }
        };

        svc.rebuild( mgr, probing, List.of(), List.of() );

        assertTrue( seen[ 0 ], "mid-rebuild, a reader must still see the previous A-Box (no empty window)" );
        assertFalse( seen[ 1 ], "mid-rebuild, a reader must not see the uncommitted new A-Box" );
        assertFalse( mgr.namedGraphExists( Iris.entity( old ) ), "after commit the old graph is gone" );
        assertTrue( mgr.namedGraphExists( Iris.entity( first ) ) );
        assertTrue( mgr.namedGraphExists( Iris.entity( second ) ) );
    }
}

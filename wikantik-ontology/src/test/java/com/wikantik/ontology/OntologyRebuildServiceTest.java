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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OntologyRebuildServiceTest {

    private static final String CANON = "01CANON0000000000000000001";

    private static List< PageRecord > pages( final int n ) {
        final List< PageRecord > out = new ArrayList<>();
        for ( int i = 0; i < n; i++ ) {
            out.add( new PageRecord( String.format( "01CANON%019d", i ), "Page" + i, "Page " + i, "article",
                    "cluster" + i, List.of( "tag" + i ), "s", "2026-01-01", null ) );
        }
        return out;
    }

    private static PageRecord page( final String canonicalId, final String slug ) {
        return new PageRecord( canonicalId, slug, slug, "article", "ml", List.of( "neural-networks" ),
                "x", "2026-01-01", "researcher" );
    }

    @Test
    void fullRebuildMaterializesPagesAndConceptsAndInfersTypes() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< PageRecord > pages = List.of( page( CANON, "Backprop" ) );

        final int graphs = new OntologyRebuildService().rebuild( mgr, pages );

        // 1 page + 2 concepts (cluster "ml" + tag "neural-networks") = 3 named graphs.
        assertEquals( 3, graphs );
        assertTrue( mgr.namedGraphExists( Iris.page( CANON ) ) );
        final Model inf = mgr.inferenceSnapshot();
        assertTrue( inf.contains( ResourceFactory.createResource( Iris.page( CANON ) ), RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "CreativeWork" ) ) );
    }

    @Test
    void rebuildProjectsNoEntityGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        new OntologyRebuildService().rebuild( mgr, pages( 3 ) );
        final Model union = mgr.unionSnapshot();
        assertFalse( union.listSubjects().filterKeep( r -> r.isURIResource()
                && r.getURI().startsWith( Iris.ID + "entity/" ) ).hasNext(),
                "no /id/entity/* resources are projected" );
    }

    @Test
    void rebuildIsIdempotent() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildService svc = new OntologyRebuildService();
        assertEquals( svc.rebuild( mgr, pages( 4 ) ), svc.rebuild( mgr, pages( 4 ) ) );
    }

    private static void assertAboxHolds( final OntologyModelManager mgr, final List< PageRecord > pages,
                                         final int graphs ) {
        assertEquals( graphs, mgr.namedGraphCount(), "named graph count after rebuild" );
        for ( final PageRecord p : pages ) {
            assertTrue( mgr.namedGraphExists( Iris.page( p.canonicalId() ) ), "page graph " + p.slug() );
        }
    }

    @Test
    void aFullRebuildCommitsOneWriteTransactionNotOnePerGraph() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< PageRecord > pages = pages( 10 );
        final long before = mgr.committedWriteTransactions();

        final int graphs = new OntologyRebuildService().rebuild( mgr, pages );

        assertEquals( 30, graphs, "10 pages + 10 tag + 10 cluster concepts" );
        assertEquals( 1, mgr.committedWriteTransactions() - before,
                "the clear and every graph write must share ONE write transaction (each commit is a TDB2 sync round)" );
        assertAboxHolds( mgr, pages, graphs );
    }

    @Test
    void aFullRebuildOnTdb2CommitsOnceAndReplacesTheWholeAbox( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();
            final OntologyRebuildService svc = new OntologyRebuildService();
            svc.rebuild( mgr, pages( 30 ) );
            final List< PageRecord > next = pages( 20 );   // 10 pages disappeared
            final long before = mgr.committedWriteTransactions();

            final int graphs = svc.rebuild( mgr, next );

            assertEquals( 1, mgr.committedWriteTransactions() - before, "one TDB2 commit for the whole rebuild" );
            assertAboxHolds( mgr, next, graphs );
            assertFalse( mgr.namedGraphExists( Iris.page( pages( 30 ).get( 25 ).canonicalId() ) ),
                    "vanished page is pruned" );
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
            svc.rebuild( mgr, pages( 30 ) );
            final long before = mgr.committedWriteTransactions();

            final int graphs = svc.rebuild( mgr, pages( 30 ) );

            assertEquals( 0, mgr.committedWriteTransactions() - before,
                    "an unchanged A-Box must not be rewritten — every rewrite strands copy-on-write blocks" );
            assertAboxHolds( mgr, pages( 30 ), graphs );
        } finally {
            mgr.close();
        }
    }

    @Test
    void readersKeepSeeingThePreviousAboxUntilTheRebuildCommits() throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final PageRecord old = page( "01OLD00000000000000000000A", "Old" );
        final PageRecord first = page( "01NEW00000000000000000000A", "First" );
        final PageRecord second = page( "01NEW00000000000000000000B", "Second" );
        final OntologyRebuildService svc = new OntologyRebuildService();
        svc.rebuild( mgr, List.of( old ) );

        // Iterating the second page probes the store from another thread, mid-rebuild.
        final boolean[] seen = new boolean[ 2 ];
        final List< PageRecord > probing = new AbstractList<>() {
            @Override public PageRecord get( final int i ) {
                if ( i == 1 ) {
                    try {
                        seen[ 0 ] = CompletableFuture.supplyAsync( () -> mgr.namedGraphExists( Iris.page( old.canonicalId() ) ) )
                                .get( 10, TimeUnit.SECONDS );
                        seen[ 1 ] = CompletableFuture.supplyAsync( () -> mgr.namedGraphExists( Iris.page( first.canonicalId() ) ) )
                                .get( 10, TimeUnit.SECONDS );
                    } catch ( final Exception e ) {
                        throw new IllegalStateException( e );
                    }
                }
                return i == 0 ? first : second;
            }
            @Override public int size() {
                return 2;
            }
        };

        svc.rebuild( mgr, probing );

        assertTrue( seen[ 0 ], "mid-rebuild, a reader must still see the previous A-Box (no empty window)" );
        assertFalse( seen[ 1 ], "mid-rebuild, a reader must not see the uncommitted new A-Box" );
        assertFalse( mgr.namedGraphExists( Iris.page( old.canonicalId() ) ), "after commit the old graph is gone" );
        assertTrue( mgr.namedGraphExists( Iris.page( first.canonicalId() ) ) );
        assertTrue( mgr.namedGraphExists( Iris.page( second.canonicalId() ) ) );
    }
}

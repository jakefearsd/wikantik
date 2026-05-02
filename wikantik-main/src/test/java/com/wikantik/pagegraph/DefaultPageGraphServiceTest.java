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
package com.wikantik.pagegraph;

import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageGraphEdge;
import com.wikantik.api.pagegraph.PageGraphNode;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPageGraphServiceTest {

    private StructuralIndexService structural;
    private ReferenceManager refMgr;
    private PageManager pageMgr;
    private DefaultPageGraphService service;

    @BeforeEach
    void setUp() {
        structural = mock( StructuralIndexService.class );
        refMgr = mock( ReferenceManager.class );
        pageMgr = mock( PageManager.class );
        // Structural index is empty by default — every page falls back to its slug as id.
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        service = new DefaultPageGraphService( structural, refMgr, pageMgr );
    }

    @Test
    void snapshot_emptyWhenReferenceManagerReportsNoPages() {
        when( refMgr.findCreated() ).thenReturn( Set.of() );
        final PageGraphSnapshot snap = service.snapshot( null );
        assertNotNull( snap );
        assertEquals( 0, snap.nodeCount() );
        assertEquals( 0, snap.edgeCount() );
        assertTrue( snap.nodes().isEmpty() );
        assertTrue( snap.edges().isEmpty() );
    }

    @Test
    void snapshot_buildsNodesAndEdgesFromWikilinks() {
        // Topology: A -> B, A -> C, B -> C, D orphan
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A", "B", "C", "D" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of( "B", "C" ) );
        when( refMgr.findRefersTo( "B" ) ).thenReturn( List.of( "C" ) );
        when( refMgr.findRefersTo( "C" ) ).thenReturn( List.of() );
        when( refMgr.findRefersTo( "D" ) ).thenReturn( List.of() );

        final PageGraphSnapshot snap = service.snapshot( null );

        final Map< String, PageGraphNode > byName = new HashMap<>();
        for ( final PageGraphNode n : snap.nodes() ) byName.put( n.name(), n );

        assertAll( "node degrees",
            () -> assertEquals( 0, byName.get( "A" ).degreeIn() ),
            () -> assertEquals( 2, byName.get( "A" ).degreeOut() ),
            () -> assertEquals( 1, byName.get( "B" ).degreeIn() ),
            () -> assertEquals( 1, byName.get( "B" ).degreeOut() ),
            () -> assertEquals( 2, byName.get( "C" ).degreeIn() ),
            () -> assertEquals( 0, byName.get( "C" ).degreeOut() ),
            () -> assertEquals( 0, byName.get( "D" ).degreeIn() ),
            () -> assertEquals( 0, byName.get( "D" ).degreeOut() ) );
        assertEquals( "orphan", byName.get( "D" ).role(), "no edges → orphan" );

        // Three edges, all relationshipType=page-link
        assertEquals( 3, snap.edges().size() );
        final Set< String > pairs = new HashSet<>();
        for ( final PageGraphEdge e : snap.edges() ) {
            pairs.add( e.source() + "->" + e.target() );
            assertEquals( "page-link", e.relationshipType() );
            assertEquals( "HUMAN_AUTHORED", e.provenance() );
        }
        assertTrue( pairs.contains( "A->B" ) );
        assertTrue( pairs.contains( "A->C" ) );
        assertTrue( pairs.contains( "B->C" ) );
    }

    @Test
    void snapshot_skipsEdgesToUnknownPages() {
        // A links to B (exists) and Stub (does NOT exist). Only A->B should appear.
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A", "B" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of( "B", "Stub" ) );
        when( refMgr.findRefersTo( "B" ) ).thenReturn( List.of() );

        final PageGraphSnapshot snap = service.snapshot( null );

        assertEquals( 1, snap.edges().size() );
        final PageGraphEdge edge = snap.edges().get( 0 );
        assertEquals( "A", edge.source() );
        assertEquals( "B", edge.target() );
    }

    @Test
    void snapshot_dedupesParallelEdgesBetweenSamePair() {
        // A may reference B multiple times (e.g., listed twice in the body).
        // The reference manager's collection can carry duplicates; the snapshot should not.
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A", "B" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of( "B", "B" ) );

        final PageGraphSnapshot snap = service.snapshot( null );

        assertEquals( 1, snap.edges().size(), "duplicate refs collapse to one edge" );
    }

    @Test
    void snapshot_dropsSelfLinks() {
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of( "A" ) );

        final PageGraphSnapshot snap = service.snapshot( null );

        assertTrue( snap.edges().isEmpty(), "self-links are not part of the page graph" );
    }

    @Test
    void snapshot_isCachedWithinTtl() {
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of() );

        service.snapshot( null );
        service.snapshot( null );

        verify( refMgr, times( 1 ) ).findCreated();
    }

    @Test
    void invalidateCache_forcesRebuild() {
        when( refMgr.findCreated() ).thenReturn( linkedSet( "A" ) );
        when( refMgr.findRefersTo( "A" ) ).thenReturn( List.of() );

        service.snapshot( null );
        service.invalidateCache();
        service.snapshot( null );

        verify( refMgr, times( 2 ) ).findCreated();
    }

    /**
     * {@link Set#of} returns an immutable set with no defined iteration order.
     * The service iterates known pages to compute degrees and serialise nodes,
     * and tests that assert specific orderings break under HashSet
     * randomisation. Use a {@link LinkedHashSet} to keep tests deterministic.
     */
    private static Set< String > linkedSet( final String... names ) {
        final Set< String > out = new LinkedHashSet<>();
        for ( final String n : names ) out.add( n );
        return out;
    }
}

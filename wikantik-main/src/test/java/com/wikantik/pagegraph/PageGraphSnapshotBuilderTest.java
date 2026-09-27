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

import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageGraphNode;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targeted tests for {@link PageGraphSnapshotBuilder} exception-swallowing branches
 * (a failing {@link ReferenceManager} or {@link StructuralIndexService} must degrade
 * gracefully, not blow up the whole snapshot) and the type-lowercasing path.
 */
class PageGraphSnapshotBuilderTest {

    @Test
    void buildSkipsAPageWhenReferenceManagerThrows() {
        final StructuralIndexService structural = mock( StructuralIndexService.class );
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );

        final ReferenceManager refMgr = mock( ReferenceManager.class );
        when( refMgr.findRefersTo( "PageA" ) ).thenThrow( new RuntimeException( "boom" ) );

        final PageGraphSnapshotBuilder builder = new PageGraphSnapshotBuilder( structural, refMgr );
        final PageGraphSnapshot snapshot = builder.build( Set.of( "PageA" ) );

        assertEquals( 0, snapshot.edgeCount(), "a throwing findRefersTo must yield zero edges, not throw" );
        assertEquals( 1, snapshot.nodeCount(), "the page itself is still listed as a node" );
    }

    @Test
    void buildTreatsAStructuralLookupFailureAsNoDescriptor() {
        final StructuralIndexService structural = mock( StructuralIndexService.class );
        when( structural.resolveCanonicalIdFromSlug( anyString() ) ).thenThrow( new RuntimeException( "boom" ) );

        final ReferenceManager refMgr = mock( ReferenceManager.class );
        when( refMgr.findRefersTo( anyString() ) ).thenReturn( List.of() );

        final PageGraphSnapshotBuilder builder = new PageGraphSnapshotBuilder( structural, refMgr );
        final PageGraphSnapshot snapshot = builder.build( Set.of( "PageA" ) );

        assertEquals( 1, snapshot.nodeCount() );
        final PageGraphNode node = snapshot.nodes().get( 0 );
        assertEquals( "PageA", node.id(), "with no resolvable descriptor, the node id falls back to the page name" );
        assertEquals( null, node.type(), "no descriptor means no type" );
    }

    @Test
    void buildLowercasesTheDescriptorTypeOnTheNode() {
        final PageDescriptor descriptor = new PageDescriptor(
                "abc123", "PageA", "Page A", PageType.ARTICLE, null,
                List.of(), null, null, Optional.empty(), false );

        final StructuralIndexService structural = mock( StructuralIndexService.class );
        when( structural.resolveCanonicalIdFromSlug( "PageA" ) ).thenReturn( Optional.of( "abc123" ) );
        when( structural.getByCanonicalId( "abc123" ) ).thenReturn( Optional.of( descriptor ) );

        final ReferenceManager refMgr = mock( ReferenceManager.class );
        when( refMgr.findRefersTo( anyString() ) ).thenReturn( List.of() );

        final PageGraphSnapshotBuilder builder = new PageGraphSnapshotBuilder( structural, refMgr );
        final PageGraphSnapshot snapshot = builder.build( Set.of( "PageA" ) );

        final PageGraphNode node = snapshot.nodes().get( 0 );
        assertTrue( node.type() != null && node.type().equals( node.type().toLowerCase() ),
                "the descriptor's PageType must be rendered lowercase; got: " + node.type() );
    }
}

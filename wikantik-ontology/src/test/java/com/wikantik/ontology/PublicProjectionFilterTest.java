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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;
import org.junit.jupiter.api.Test;

class PublicProjectionFilterTest {

    private static final UUID PUB = UUID.fromString( "00000000-0000-0000-0000-0000000000f1" );
    private static final UUID RES = UUID.fromString( "00000000-0000-0000-0000-0000000000f2" );
    private static final UUID STUB = UUID.fromString( "00000000-0000-0000-0000-0000000000f3" );

    private final Predicate< String > isPublic = slug -> "PublicPage".equals( slug );

    private KgNode node( final UUID id, final String sourcePage ) {
        return new KgNode( id, "n", "concept", sourcePage, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );
    }

    @Test
    void publicNodesKeepStubsAndPublicSourcedDropRestricted() {
        final List< KgNode > nodes = List.of(
                node( PUB, "PublicPage" ), node( RES, "SecretPage" ), node( STUB, null ) );
        final List< KgNode > pub = PublicProjectionFilter.publicNodes( nodes, isPublic );
        final Set< UUID > ids = PublicProjectionFilter.publicNodeIds( nodes, isPublic );
        assertTrue( ids.contains( PUB ) && ids.contains( STUB ) );
        assertFalse( ids.contains( RES ), "restricted-sourced node excluded" );
        assertEquals( 2, pub.size() );
    }

    @Test
    void publicEdgesRequireBothEndpointsPublic() {
        final Set< UUID > pubIds = Set.of( PUB, STUB );
        final List< KgEdge > edges = List.of(
                new KgEdge( UUID.randomUUID(), PUB, STUB, "uses", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ),
                new KgEdge( UUID.randomUUID(), PUB, RES,  "uses", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        final List< KgEdge > pub = PublicProjectionFilter.publicEdges( edges, pubIds );
        assertEquals( 1, pub.size(), "edge touching a restricted node is dropped" );
        assertEquals( STUB, pub.get( 0 ).targetId() );
    }

    @Test
    void publicPagesKeepOnlyViewable() {
        final PageRecord pubP = new PageRecord( "C1", "PublicPage", "T", "article", "ml", List.of(), null, null, null );
        final PageRecord secP = new PageRecord( "C2", "SecretPage", "T", "article", "ml", List.of(), null, null, null );
        final List< PageRecord > pub = PublicProjectionFilter.publicPages( List.of( pubP, secP ), isPublic );
        assertEquals( 1, pub.size() );
        assertEquals( "PublicPage", pub.get( 0 ).slug() );
    }
}

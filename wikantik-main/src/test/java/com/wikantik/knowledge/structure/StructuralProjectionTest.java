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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.Relation;
import com.wikantik.api.structure.RelationDirection;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.TraversalSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuralProjectionTest {

    private static PageDescriptor page( final String id, final String slug, final PageType type,
                                         final String cluster, final List< String > tags ) {
        return new PageDescriptor( id, slug, slug, type, cluster, tags,
                                    slug + " summary", Instant.parse( "2026-04-01T00:00:00Z" ) );
    }

    @Test
    void build_returns_cluster_summaries() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "HybridRetrieval", PageType.ARTICLE, "wikantik-development",
                                List.of( "retrieval" ) ) )
                .addPage( page( "B", "WikantikDevelopment", PageType.HUB, "wikantik-development",
                                List.of() ) )
                .addPage( page( "C", "IndexFunds",         PageType.ARTICLE, "investing",
                                List.of( "investing" ) ) )
                .build();

        final var clusters = proj.listClusters();
        assertEquals( 2, clusters.size() );
        final var dev = clusters.stream().filter( c -> "wikantik-development".equals( c.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, dev.articleCount() );
        assertEquals( "WikantikDevelopment", dev.hubPage().slug() );
    }

    @Test
    void listTags_excludes_tags_under_min_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, null, List.of( "beta" ) ) )
                .build();
        final var tags2 = proj.listTags( 2 );
        assertEquals( 2, tags2.size() );
        final var tags1 = proj.listTags( 1 );
        assertEquals( 2, tags1.size() );
    }

    @Test
    void listPagesByFilter_by_type_and_cluster() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, "c1", List.of() ) )
                .addPage( page( "B", "Y", PageType.HUB,     "c1", List.of() ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, "c2", List.of() ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), Optional.of( "c1" ), null, null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void listPagesByFilter_by_all_tags_AND() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                null, null, List.of( "alpha", "beta" ), null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void getByCanonicalId_and_resolveSlug_round_trip() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of() ) )
                .build();
        assertEquals( Optional.of( "X" ),  proj.resolveSlugFromCanonicalId( "A" ) );
        assertEquals( Optional.of( "A" ),  proj.resolveCanonicalIdFromSlug( "X" ) );
        assertTrue( proj.getByCanonicalId( "A" ).isPresent() );
        assertTrue( proj.getByCanonicalId( "Z" ).isEmpty() );
    }

    /* ----- Phase 2: relation-graph queries ----- */

    @Test
    void outgoing_relations_filter_by_type() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "A", PageType.ARTICLE, null, List.of() ) )
                .addPage( page( "B", "B", PageType.HUB,     null, List.of() ) )
                .addPage( page( "C", "C", PageType.ARTICLE, null, List.of() ) )
                .addRelation( new Relation( "A", "B", RelationType.PART_OF ) )
                .addRelation( new Relation( "A", "C", RelationType.EXAMPLE_OF ) )
                .build();

        final List< RelationEdge > all = proj.outgoingRelations( "A", Optional.empty() );
        assertEquals( 2, all.size() );

        final List< RelationEdge > parts = proj.outgoingRelations( "A", Optional.of( RelationType.PART_OF ) );
        assertEquals( 1, parts.size() );
        assertEquals( "B", parts.get( 0 ).targetId() );
        assertEquals( "B", parts.get( 0 ).targetSlug() );
        assertEquals( 1,   parts.get( 0 ).depth() );
    }

    @Test
    void incoming_relations_resolved_for_target() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "A", PageType.ARTICLE, null, List.of() ) )
                .addPage( page( "B", "B", PageType.ARTICLE, null, List.of() ) )
                .addPage( page( "H", "H", PageType.HUB,     null, List.of() ) )
                .addRelation( new Relation( "A", "H", RelationType.PART_OF ) )
                .addRelation( new Relation( "B", "H", RelationType.PART_OF ) )
                .build();
        final List< RelationEdge > inbound = proj.incomingRelations( "H", Optional.empty() );
        assertEquals( 2, inbound.size() );
    }

    @Test
    void traverse_BFS_respects_depth_cap_and_visits_each_node_once() {
        // A part-of B, B part-of C, C part-of D
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "A", PageType.ARTICLE, null, List.of() ) )
                .addPage( page( "B", "B", PageType.HUB,     null, List.of() ) )
                .addPage( page( "C", "C", PageType.HUB,     null, List.of() ) )
                .addPage( page( "D", "D", PageType.HUB,     null, List.of() ) )
                .addRelation( new Relation( "A", "B", RelationType.PART_OF ) )
                .addRelation( new Relation( "B", "C", RelationType.PART_OF ) )
                .addRelation( new Relation( "C", "D", RelationType.PART_OF ) )
                .build();

        final List< RelationEdge > depth1 = proj.traverse(
                "A", new TraversalSpec( RelationDirection.OUT, null, 1 ) );
        assertEquals( 1, depth1.size() );
        assertEquals( "B", depth1.get( 0 ).targetId() );

        final List< RelationEdge > depth2 = proj.traverse(
                "A", new TraversalSpec( RelationDirection.OUT, null, 2 ) );
        assertEquals( 2, depth2.size() );

        final List< RelationEdge > depth3 = proj.traverse(
                "A", new TraversalSpec( RelationDirection.OUT, null, 3 ) );
        assertEquals( 3, depth3.size() );
        assertEquals( 1, depth3.get( 0 ).depth() );
        assertEquals( 2, depth3.get( 1 ).depth() );
        assertEquals( 3, depth3.get( 2 ).depth() );
    }

    @Test
    void traverse_dangling_target_yields_edge_with_null_slug() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "A", PageType.ARTICLE, null, List.of() ) )
                // 'GHOST' is referenced but never registered as a page.
                .addRelation( new Relation( "A", "GHOST", RelationType.SUPERSEDES ) )
                .build();
        final List< RelationEdge > out = proj.outgoingRelations( "A", Optional.empty() );
        assertEquals( 1, out.size() );
        assertEquals( "GHOST", out.get( 0 ).targetId() );
        assertNull( out.get( 0 ).targetSlug() );
        assertNull( out.get( 0 ).targetTitle() );
    }
}

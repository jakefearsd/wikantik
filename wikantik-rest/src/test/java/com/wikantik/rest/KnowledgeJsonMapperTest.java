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
package com.wikantik.rest;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.NodeMention;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.HubProposalRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KnowledgeJsonMapper}: pins the exact JSON field contract (key names,
 * value sourcing, null-date handling, label fallbacks) that the admin Knowledge Graph API and
 * its React consumers depend on. Extracted from {@code AdminKnowledgeResource}, so these tests
 * are the characterization that the serialisation shape did not change in the extraction.
 */
class KnowledgeJsonMapperTest {

    private static final Instant CREATED = Instant.parse( "2026-01-02T03:04:05Z" );
    private static final Instant MODIFIED = Instant.parse( "2026-02-03T04:05:06Z" );

    // ---------------------------------------------------------------- nodeToMap

    @Test
    void nodeToMap_mapsEveryField() {
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode( id, "Graph Theory", "Concept", "GraphTheory",
                Provenance.AI_INFERRED, Map.of( "weight", 3 ), CREATED, MODIFIED, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.nodeToMap( node );

        assertEquals( id.toString(), m.get( "id" ) );
        assertEquals( "Graph Theory", m.get( "name" ) );
        assertEquals( "Concept", m.get( "node_type" ) );
        assertEquals( "GraphTheory", m.get( "source_page" ) );
        assertEquals( "ai-inferred", m.get( "provenance" ), "provenance serialises via Provenance.value()" );
        assertEquals( Map.of( "weight", 3 ), m.get( "properties" ) );
        assertEquals( false, m.get( "is_stub" ) );
        assertEquals( "2026-01-02T03:04:05Z", m.get( "created" ) );
        assertEquals( "2026-02-03T04:05:06Z", m.get( "modified" ) );
    }

    @Test
    void nodeToMap_nullSourcePage_isStubTrueAndSourcePageNull() {
        final KgNode stub = new KgNode( UUID.randomUUID(), "Orphan", "Concept", null,
                Provenance.AI_INFERRED, Map.of(), CREATED, MODIFIED, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.nodeToMap( stub );

        assertEquals( true, m.get( "is_stub" ), "is_stub is derived from a null source page" );
        assertNull( m.get( "source_page" ) );
    }

    @Test
    void nodeToMap_nullTimestamps_serialiseAsNull() {
        final KgNode node = new KgNode( UUID.randomUUID(), "N", "Concept", "P",
                Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.nodeToMap( node );

        assertNull( m.get( "created" ) );
        assertNull( m.get( "modified" ) );
    }

    // ---------------------------------------------------------------- edgeToMap

    @Test
    void edgeToMap_mapsEveryField() {
        final UUID id = UUID.randomUUID();
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final KgEdge edge = new KgEdge( id, src, tgt, "related",
                Provenance.HUMAN_AUTHORED, Map.of( "k", "v" ), CREATED, MODIFIED, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.edgeToMap( edge );

        assertEquals( id.toString(), m.get( "id" ) );
        assertEquals( src.toString(), m.get( "source_id" ) );
        assertEquals( tgt.toString(), m.get( "target_id" ) );
        assertEquals( "related", m.get( "relationship_type" ) );
        assertEquals( "human-authored", m.get( "provenance" ) );
        assertEquals( "human", m.get( "tier" ) );
        assertEquals( Map.of( "k", "v" ), m.get( "properties" ) );
        assertEquals( "2026-01-02T03:04:05Z", m.get( "created" ) );
        assertEquals( "2026-02-03T04:05:06Z", m.get( "modified" ) );
    }

    @Test
    void edgeToMap_nullTimestamps_serialiseAsNull() {
        final KgEdge edge = new KgEdge( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "related", Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.edgeToMap( edge );

        assertNull( m.get( "created" ) );
        assertNull( m.get( "modified" ) );
    }

    // -------------------------------------------------------------- enrichEdge

    @Test
    void enrichEdge_addsResolvedSourceAndTargetNames() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final KgEdge edge = new KgEdge( UUID.randomUUID(), src, tgt, "related",
                Provenance.HUMAN_AUTHORED, Map.of(), CREATED, MODIFIED, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.enrichEdge( edge,
                Map.of( src, "Source Node", tgt, "Target Node" ) );

        assertEquals( "Source Node", m.get( "source_name" ) );
        assertEquals( "Target Node", m.get( "target_name" ) );
        // still carries the base edge fields
        assertEquals( "related", m.get( "relationship_type" ) );
    }

    @Test
    void enrichEdge_unresolvedIds_fallBackToUuidString() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final KgEdge edge = new KgEdge( UUID.randomUUID(), src, tgt, "related",
                Provenance.HUMAN_AUTHORED, Map.of(), CREATED, MODIFIED, "human", null );

        final Map< String, Object > m = KnowledgeJsonMapper.enrichEdge( edge, Map.of() );

        assertEquals( src.toString(), m.get( "source_name" ), "missing label falls back to the UUID" );
        assertEquals( tgt.toString(), m.get( "target_name" ) );
    }

    // ------------------------------------------------------------- mentionToMap

    @Test
    void mentionToMap_mapsEveryField() {
        final UUID chunkId = UUID.randomUUID();
        final NodeMention mention = new NodeMention( chunkId, "PageX", 4,
                List.of( "H1", "H2" ), "some text", 0.87, "gemma" );

        final Map< String, Object > m = KnowledgeJsonMapper.mentionToMap( mention );

        assertEquals( chunkId.toString(), m.get( "chunk_id" ) );
        assertEquals( "PageX", m.get( "page_name" ) );
        assertEquals( 4, m.get( "chunk_index" ) );
        assertEquals( List.of( "H1", "H2" ), m.get( "heading_path" ) );
        assertEquals( "some text", m.get( "text" ) );
        assertEquals( 0.87, m.get( "confidence" ) );
        assertEquals( "gemma", m.get( "extractor" ) );
    }

    // ------------------------------------------------------------ proposalToMap

    @Test
    void proposalToMap_mapsEveryField() {
        final UUID id = UUID.randomUUID();
        final KgProposal p = new KgProposal( id, "new-node", "PageX", Map.of( "name", "Foo" ),
                0.75, "because", "pending", "alice", CREATED, MODIFIED, "machine",
                "approved", 0.9, CREATED, "gemma4-graph:12b" );

        final Map< String, Object > m = KnowledgeJsonMapper.proposalToMap( p );

        assertEquals( id.toString(), m.get( "id" ) );
        assertEquals( "new-node", m.get( "proposal_type" ) );
        assertEquals( "PageX", m.get( "source_page" ) );
        assertEquals( Map.of( "name", "Foo" ), m.get( "proposed_data" ) );
        assertEquals( 0.75, m.get( "confidence" ) );
        assertEquals( "because", m.get( "reasoning" ) );
        assertEquals( "pending", m.get( "status" ) );
        assertEquals( "alice", m.get( "reviewed_by" ) );
        assertEquals( "2026-01-02T03:04:05Z", m.get( "created" ) );
        assertEquals( "2026-02-03T04:05:06Z", m.get( "reviewed_at" ) );
        assertEquals( "machine", m.get( "tier" ) );
        assertEquals( "approved", m.get( "machine_status" ) );
        assertEquals( 0.9, m.get( "machine_confidence" ) );
        assertEquals( "2026-01-02T03:04:05Z", m.get( "machine_judged_at" ) );
        assertEquals( "gemma4-graph:12b", m.get( "machine_model" ) );
    }

    @Test
    void proposalToMap_nullTemporalsAndMachineFields_serialiseAsNull() {
        final KgProposal p = new KgProposal( UUID.randomUUID(), "new-edge", "P", Map.of(),
                0.5, null, "pending", null, null, null, "none", null, null, null, null );

        final Map< String, Object > m = KnowledgeJsonMapper.proposalToMap( p );

        assertNull( m.get( "created" ) );
        assertNull( m.get( "reviewed_at" ) );
        assertNull( m.get( "machine_judged_at" ) );
        assertNull( m.get( "machine_status" ) );
        // base mapper never adds the service-only conflict flags
        assertFalse( m.containsKey( "node_exists" ) );
        assertFalse( m.containsKey( "edge_previously_rejected" ) );
    }

    // --------------------------------------------------------- hubProposalToMap

    @Test
    void hubProposalToMap_mapsEveryField() {
        final HubProposalRepository.HubProposal p = new HubProposalRepository.HubProposal(
                7, "DataStructures", "IntervalTrees", 0.81, 0.96, "pending", "high similarity",
                "alice", MODIFIED, CREATED );

        final Map< String, Object > m = KnowledgeJsonMapper.hubProposalToMap( p );

        assertEquals( 7, m.get( "id" ) );
        assertEquals( "DataStructures", m.get( "hub_name" ) );
        assertEquals( "IntervalTrees", m.get( "page_name" ) );
        assertEquals( 0.81, m.get( "raw_similarity" ) );
        assertEquals( 0.96, m.get( "percentile_score" ) );
        assertEquals( "pending", m.get( "status" ) );
        assertEquals( "high similarity", m.get( "reason" ) );
        assertEquals( "alice", m.get( "reviewed_by" ) );
        assertEquals( "2026-02-03T04:05:06Z", m.get( "reviewed_at" ) );
        assertEquals( "2026-01-02T03:04:05Z", m.get( "created" ) );
    }

    @Test
    void hubProposalToMap_nullTimestamps_serialiseAsNull() {
        final HubProposalRepository.HubProposal p = new HubProposalRepository.HubProposal(
                1, "Hub", "Page", 0.5, 0.5, "pending", "r", null, null, null );

        final Map< String, Object > m = KnowledgeJsonMapper.hubProposalToMap( p );

        assertNull( m.get( "reviewed_at" ) );
        assertNull( m.get( "created" ) );
        assertTrue( m.containsKey( "reviewed_at" ), "key present even when value is null" );
    }
}

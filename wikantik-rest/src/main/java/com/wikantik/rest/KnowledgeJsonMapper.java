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
import com.wikantik.knowledge.HubProposalRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure serialisation of Knowledge Graph domain records ({@link KgNode}, {@link KgEdge},
 * {@link KgProposal}, {@link NodeMention}, hub proposals) into the ordered {@code Map}
 * shapes the admin API returns as JSON. Extracted from {@code AdminKnowledgeResource} so the
 * field-mapping contract is testable in isolation and the controller is freed of serialisation
 * detail (single-responsibility).
 *
 * <p>Every method is a pure, side-effect-free function of its argument(s); the
 * {@link java.util.LinkedHashMap} return preserves key order for stable JSON output. The
 * service-dependent conflict-flag enrichment for proposals stays in the controller (it needs a
 * live {@code KnowledgeGraphService}); this class produces only the base proposal shape.</p>
 * <p>
 * {@code nodeToMap}/{@code edgeToMap}/{@code enrichEdge}/{@code mentionToMap} are {@code public}
 * (unlike {@code hubProposalToMap}, which stays package-private) because
 * {@code com.wikantik.rest.knowledge.KgNodeAdminHandlers}/{@code KgEdgeAdminHandlers} —
 * extracted from {@code AdminKnowledgeResource} — call them from a different package.
 */
public final class KnowledgeJsonMapper {

    private KnowledgeJsonMapper() {
    }

    public static Map< String, Object > nodeToMap( final KgNode node ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", node.id().toString() );
        map.put( "name", node.name() );
        map.put( "node_type", node.nodeType() );
        map.put( "source_page", node.sourcePage() );
        map.put( "provenance", node.provenance().value() );
        map.put( "properties", node.properties() );
        map.put( "is_stub", node.isStub() );
        map.put( "created", node.created() != null ? node.created().toString() : null );
        map.put( "modified", node.modified() != null ? node.modified().toString() : null );
        return map;
    }

    public static Map< String, Object > edgeToMap( final KgEdge edge ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", edge.id().toString() );
        map.put( "source_id", edge.sourceId().toString() );
        map.put( "target_id", edge.targetId().toString() );
        map.put( "relationship_type", edge.relationshipType() );
        map.put( "provenance", edge.provenance().value() );
        map.put( "tier", edge.tier() );
        map.put( "properties", edge.properties() );
        map.put( "created", edge.created() != null ? edge.created().toString() : null );
        map.put( "modified", edge.modified() != null ? edge.modified().toString() : null );
        return map;
    }

    /** {@link #edgeToMap(KgEdge)} plus resolved {@code source_name}/{@code target_name} labels. */
    public static Map< String, Object > enrichEdge( final KgEdge edge, final Map< UUID, String > nameMap ) {
        final Map< String, Object > m = edgeToMap( edge );
        m.put( "source_name", nameMap.getOrDefault( edge.sourceId(), edge.sourceId().toString() ) );
        m.put( "target_name", nameMap.getOrDefault( edge.targetId(), edge.targetId().toString() ) );
        return m;
    }

    public static Map< String, Object > mentionToMap( final NodeMention mention ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "chunk_id", mention.chunkId().toString() );
        m.put( "page_name", mention.pageName() );
        m.put( "chunk_index", mention.chunkIndex() );
        m.put( "heading_path", mention.headingPath() );
        m.put( "text", mention.text() );
        m.put( "confidence", mention.confidence() );
        m.put( "extractor", mention.extractor() );
        return m;
    }

    /**
     * Base serialisation of a proposal (no service-dependent conflict flags). The controller adds
     * {@code node_exists}/{@code edge_previously_rejected} flags when it has a live service.
     * <p>
     * Public (unlike its siblings here) because {@code com.wikantik.rest.knowledge.KgProposalAdminHandlers}
     * — extracted from {@code AdminKnowledgeResource} — calls it from a different package.
     */
    public static Map< String, Object > proposalToMap( final KgProposal p ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", p.id().toString() );
        map.put( "proposal_type", p.proposalType() );
        map.put( "source_page", p.sourcePage() );
        map.put( "proposed_data", p.proposedData() );
        map.put( "confidence", p.confidence() );
        map.put( "reasoning", p.reasoning() );
        map.put( "status", p.status() );
        map.put( "reviewed_by", p.reviewedBy() );
        map.put( "created", p.created() != null ? p.created().toString() : null );
        map.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        map.put( "tier", p.tier() );
        map.put( "machine_status", p.machineStatus() );
        map.put( "machine_confidence", p.machineConfidence() );
        map.put( "machine_judged_at", p.machineJudgedAt() != null ? p.machineJudgedAt().toString() : null );
        map.put( "machine_model", p.machineModel() );
        return map;
    }

    static Map< String, Object > hubProposalToMap( final HubProposalRepository.HubProposal p ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "id", p.id() );
        map.put( "hub_name", p.hubName() );
        map.put( "page_name", p.pageName() );
        map.put( "raw_similarity", p.rawSimilarity() );
        map.put( "percentile_score", p.percentileScore() );
        map.put( "status", p.status() );
        map.put( "reason", p.reason() );
        map.put( "reviewed_by", p.reviewedBy() );
        map.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        map.put( "created", p.created() != null ? p.created().toString() : null );
        return map;
    }
}

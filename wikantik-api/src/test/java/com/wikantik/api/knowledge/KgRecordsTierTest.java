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
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class KgRecordsTierTest {
    @Test
    void kgProposal_has_tier_and_machine_fields() {
        final UUID id = UUID.randomUUID();
        final KgProposal p = new KgProposal(
            id, "new-edge", "Page", Map.of(), 0.5, "reason",
            "pending", null, Instant.now(), null,
            "machine", "approved", 0.85, Instant.now(), "gemma4-assist:latest" );
        assertEquals( "machine", p.tier() );
        assertEquals( "approved", p.machineStatus() );
        assertEquals( 0.85, p.machineConfidence() );
        assertEquals( "gemma4-assist:latest", p.machineModel() );
    }

    @Test
    void kgProposal_tier_defaults_to_none_when_null() {
        final KgProposal p = new KgProposal(
            UUID.randomUUID(), "new-edge", "Page", Map.of(), 0.5, "",
            "pending", null, Instant.now(), null,
            null, null, null, null, null );
        assertEquals( "none", p.tier() );
    }

    @Test
    void kgNode_has_tier_and_provenance() {
        final UUID id = UUID.randomUUID();
        final UUID provenance = UUID.randomUUID();
        final KgNode n = new KgNode(
            id, "Foo", "concept", "Foo", Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.now(), Instant.now(), "machine", provenance );
        assertEquals( "machine", n.tier() );
        assertEquals( provenance, n.provenanceProposalId() );
    }

    @Test
    void kgEdge_has_tier_and_provenance() {
        final UUID provenance = UUID.randomUUID();
        final KgEdge e = new KgEdge(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "rel",
            Provenance.AI_INFERRED, Map.of(), Instant.now(), Instant.now(),
            "human", provenance );
        assertEquals( "human", e.tier() );
        assertEquals( provenance, e.provenanceProposalId() );
    }
}

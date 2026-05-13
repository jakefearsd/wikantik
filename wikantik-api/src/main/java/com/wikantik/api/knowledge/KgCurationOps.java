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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade for Knowledge Graph curation operations shared by the REST admin surface
 * and the /wikantik-admin-mcp MCP tools. Each method returns {@code Optional.empty()}
 * on success or {@code Optional.of(errorMessage)} on per-op failure — callers use
 * this to assemble bulk-result envelopes without throwing.
 *
 * <p>Implementations route through {@link KnowledgeGraphService} for the underlying
 * write operation and own any required side effects (e.g. frontmatter write-back on
 * edge-proposal approval). Both REST and MCP MUST call into this facade so the two
 * surfaces cannot drift.
 */
public interface KgCurationOps {

    /**
     * Rich result for proposal approval: carries both an error (absent on success)
     * and a list of non-fatal warnings (e.g. source_page is on the exclusion list).
     */
    record ApproveOutcome( Optional<String> error, List<String> warnings ) {
        public static ApproveOutcome ok() { return new ApproveOutcome( Optional.empty(), List.of() ); }
        public static ApproveOutcome ok( List<String> warnings ) {
            return new ApproveOutcome( Optional.empty(), warnings );
        }
        public static ApproveOutcome fail( String msg ) {
            return new ApproveOutcome( Optional.of( msg ), List.of() );
        }
    }

    /**
     * Approve a proposal and return a rich outcome that includes both error and warnings.
     * Approval still succeeds even when warnings are present.
     */
    ApproveOutcome tryApprove( UUID proposalId, String reviewedBy );

    /**
     * Backward-compatible convenience method that delegates to {@link #tryApprove}
     * and returns only the error portion, silently discarding any warnings.
     */
    default Optional<String> tryApproveProposal( UUID proposalId, String reviewedBy ) {
        return tryApprove( proposalId, reviewedBy ).error();
    }

    Optional<String> tryRejectProposal( UUID proposalId, String reviewedBy, String reason );

    Optional<String> tryJudgeProposal( UUID proposalId, String reviewedBy );

    Optional<String> tryConfirmEdge( UUID edgeId, String actor );

    Optional<String> tryDeleteEdge( UUID edgeId, String actor );

    Optional<String> tryDeleteAndRejectEdge( UUID edgeId, String actor, String reason );

    EdgeResult tryUpsertEdge( UUID sourceId, UUID targetId, String relationshipType,
                              Map<String, Object> properties, String actor );

    record EdgeResult( Optional<UUID> edgeId, Optional<String> error ) {
        public static EdgeResult ok( UUID id ) { return new EdgeResult( Optional.of( id ), Optional.empty() ); }
        public static EdgeResult fail( String msg ) { return new EdgeResult( Optional.empty(), Optional.of( msg ) ); }
    }

    Optional<String> tryDeleteNode( UUID nodeId, String actor );

    Optional<String> tryMergeNodes( UUID sourceId, UUID targetId, String actor );

    NodeResult tryUpsertNode( String name, String nodeType, String sourcePage,
                              Map<String, Object> properties, String actor );

    record NodeResult( Optional<UUID> nodeId, Optional<String> error ) {
        public static NodeResult ok( UUID id ) { return new NodeResult( Optional.of( id ), Optional.empty() ); }
        public static NodeResult fail( String msg ) { return new NodeResult( Optional.empty(), Optional.of( msg ) ); }
    }
}

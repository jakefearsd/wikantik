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

    Optional<String> tryApproveProposal( UUID proposalId, String reviewedBy );

    Optional<String> tryRejectProposal( UUID proposalId, String reviewedBy, String reason );

    Optional<String> tryJudgeProposal( UUID proposalId, String reviewedBy );
}

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
package com.wikantik.persistence.subsystem;

import com.wikantik.core.subsystem.WikiProperties;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.eval.RetrievalQualityDao;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.kgpolicy.KgClusterPolicyRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.pagegraph.spine.PageVerificationDao;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;

import javax.sql.DataSource;

/**
 * Namespace for the Persistence subsystem's input and output contracts.
 *
 * <p>Phase 3 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Persistence owns the {@link DataSource} and every JDBC repository / DAO.
 * Downstream subsystems (Knowledge, Page-graph spine, KG inclusion policy,
 * retrieval quality) consume narrow repository references off
 * {@link Services} instead of building their own.</p>
 *
 * <p>Phase 3 Checkpoint 1 keeps {@link JdbcKnowledgeRepository} as a single
 * field; Checkpoint 3 decomposes it into the four narrow repositories
 * ({@code KgNodeRepository}, {@code KgEdgeRepository},
 * {@code KgProposalRepository}, {@code KgRejectionRepository}) and Checkpoint
 * 5 deletes the facade.</p>
 */
public final class PersistenceSubsystem {

    private PersistenceSubsystem() {}

    /**
     * What the Persistence subsystem requires from upstream.
     *
     * <p>{@code dataSource} is the JDBC handle resolved by
     * {@code WikiEngine.initialize} from JNDI / properties at boot.
     * {@code properties} is the typed {@link WikiProperties} accessor from
     * Core; only repositories with tunable configuration read it (today
     * none of the existing repos do — they're plain JDBC).</p>
     */
    public record Deps(
        DataSource dataSource,
        WikiProperties properties
    ) {}

    /**
     * Every JDBC repository / DAO managed by Persistence. All fields are
     * non-null after a successful {@link PersistenceSubsystemFactory#create}
     * call.
     */
    public record Services(
        // Knowledge graph (Phase 3 Ckpt 1 — single repository; decomposed in Ckpt 3):
        JdbcKnowledgeRepository kgRepository,

        // Knowledge-supporting repositories:
        HubProposalRepository hubProposals,
        HubDiscoveryRepository hubDiscovery,
        ContentChunkRepository contentChunks,
        ChunkEntityMentionRepository chunkEntityMentions,
        KgNodeEmbeddingRepository kgNodeEmbeddings,
        KgJudgeTimeoutRepository judgeTimeouts,
        RetrievalQualityDao retrievalQualityDao,

        // KG inclusion policy:
        KgExcludedPagesRepository kgExcludedPages,
        KgClusterPolicyRepository kgClusterPolicy,

        // Page-graph spine:
        PageCanonicalIdsDao pageCanonicalIds,
        PageVerificationDao pageVerification,
        TrustedAuthorsDao trustedAuthors
    ) {}
}

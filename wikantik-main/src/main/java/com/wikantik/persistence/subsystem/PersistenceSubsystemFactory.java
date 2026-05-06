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

import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.eval.RetrievalQualityDao;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.judge.JdbcKgJudgeTimeoutRepository;
import com.wikantik.kgpolicy.KgClusterPolicyRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.pagegraph.spine.PageVerificationDao;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Builds {@link PersistenceSubsystem.Services} from {@link PersistenceSubsystem.Deps}.
 *
 * <p>Phase 3 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Pure: every repository receives the {@link DataSource} from {@link
 * PersistenceSubsystem.Deps}. Tests build {@link PersistenceSubsystem.Services}
 * against a Testcontainers Postgres without {@code TestEngine}.</p>
 */
public final class PersistenceSubsystemFactory {

    private PersistenceSubsystemFactory() {}

    public static PersistenceSubsystem.Services create( final PersistenceSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        final DataSource ds = Objects.requireNonNull( deps.dataSource(), "dataSource" );
        Objects.requireNonNull( deps.properties(), "properties" );

        return new PersistenceSubsystem.Services(
            new JdbcKnowledgeRepository( ds ),
            new HubProposalRepository( ds ),
            new HubDiscoveryRepository( ds ),
            new ContentChunkRepository( ds ),
            new ChunkEntityMentionRepository( ds ),
            new KgNodeEmbeddingRepository( ds ),
            new JdbcKgJudgeTimeoutRepository( ds ),
            new RetrievalQualityDao( ds ),
            new KgExcludedPagesRepository( ds ),
            new KgClusterPolicyRepository( ds ),
            new PageCanonicalIdsDao( ds ),
            new PageVerificationDao( ds ),
            new TrustedAuthorsDao( ds )
        );
    }
}

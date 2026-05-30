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

import com.wikantik.comments.CommentStore;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionFeedDao;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

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

        final KgNodeRepository kgNodes         = new KgNodeRepository( ds );
        final KgEdgeRepository kgEdges         = new KgEdgeRepository( ds );
        final KgProposalRepository kgProposals = new KgProposalRepository( ds );
        final KgRejectionRepository kgRejections = new KgRejectionRepository( ds );

        // Comments DAOs cross subsystem boundaries (Auth for user existence,
        // Page Graph for canonical_id → page resolution) so they accept
        // closures from Deps. When Deps was built without closures (test
        // helpers / subsystem-isolation harnesses), install conservative
        // defaults: nothing exists and no author is resolvable. PageOwnerService
        // then falls back to its admin owner.
        final Predicate< String > userExists = deps.userExistsLookup() != null
                ? deps.userExistsLookup()
                : login -> false;
        final Function< String, Optional< String > > authorResolver = deps.pageAuthorLookup() != null
                ? deps.pageAuthorLookup()
                : canonicalId -> Optional.empty();

        // Default owner for agent/unresolvable-author pages (e.g. the `agents`
        // service account). Stored at bootstrap only when it is itself a real
        // user, so an unseeded default degrades safely to NULL.
        final String defaultPageOwner = deps.properties().get(
                "wikantik.page_ownership.default_owner", "agents" );

        return new PersistenceSubsystem.Services(
            ds,
            kgNodes,
            kgEdges,
            kgProposals,
            kgRejections,
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
            new TrustedAuthorsDao( ds ),
            new CommentStore( ds ),
            new PageOwnerService( ds, userExists, authorResolver, defaultPageOwner ),
            new MentionService( ds, userExists ),
            new MentionFeedDao( ds )
        );
    }
}

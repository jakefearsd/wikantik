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
import com.wikantik.core.subsystem.WikiProperties;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.eval.RetrievalQualityDao;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.pagegraph.spine.PageVerificationDao;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Namespace for the Persistence subsystem's input and output contracts.
 *
 * <p>Phase 3 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Persistence owns the {@link DataSource} and every JDBC repository / DAO.
 * Downstream subsystems (Knowledge, Page-graph spine, retrieval quality)
 * consume narrow repository references off {@link Services} instead of
 * building their own.</p>
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
     *
     * <p>{@code userExistsLookup} and {@code pageAuthorLookup} are deferred
     * closures consumed by the anchored-comments DAOs ({@link PageOwnerService},
     * {@link MentionService}). They cross subsystem boundaries — the user
     * database lives in Auth and the canonical_id → page resolution lives in
     * Page Graph — so they are passed as closures rather than direct
     * references. Either may be {@code null} (tests that don't exercise the
     * comments DAOs): the factory substitutes a default that returns
     * {@code false} / {@link Optional#empty()}, which causes
     * {@link PageOwnerService} to fall back to the admin owner.</p>
     *
     * <p>{@code loginForUserIdLookup} maps a {@code users.uid} (carried by an
     * {@code @[Display Name](user:<uid>)} mention token) to that member's login; the engine passes
     * {@code PublicIdentity::loginForUserId}. {@code null} makes {@link MentionService} ignore
     * tokens and keep only legacy {@code @login} mentions.</p>
     */
    public record Deps(
        DataSource dataSource,
        WikiProperties properties,
        Predicate< String > userExistsLookup,
        Function< String, Optional< String > > pageAuthorLookup,
        Function< String, Optional< String > > loginForUserIdLookup
    ) {

        /** Four-closure form kept for callers that predate mention tokens: tokens are ignored. */
        public Deps( final DataSource dataSource, final WikiProperties properties,
                     final Predicate< String > userExistsLookup,
                     final Function< String, Optional< String > > pageAuthorLookup ) {
            this( dataSource, properties, userExistsLookup, pageAuthorLookup, null );
        }

        /** Convenience for callers (e.g. subsystem-isolation tests) that don't
         *  exercise comments DAOs. The factory will install no-op closures. */
        public Deps( final DataSource dataSource, final WikiProperties properties ) {
            this( dataSource, properties, null, null, null );
        }
    }

    /**
     * Every JDBC repository / DAO managed by Persistence. All fields are
     * non-null after a successful {@link PersistenceSubsystemFactory#create}
     * call (except {@code dataSource} which is the raw handle, also non-null).
     */
    public record Services(
        // The raw DataSource — exposed so downstream subsystem factories (e.g.
        // SearchSubsystemFactory) that need direct JDBC access can obtain it
        // from the Persistence bundle without requiring a separate seam.
        DataSource dataSource,

        // Knowledge-supporting repositories:
        ContentChunkRepository contentChunks,
        RetrievalQualityDao retrievalQualityDao,

        // Page-graph spine:
        PageCanonicalIdsDao pageCanonicalIds,
        PageVerificationDao pageVerification,
        TrustedAuthorsDao trustedAuthors,

        // Anchored comments:
        CommentStore comments,
        PageOwnerService pageOwners,
        MentionService mentions,
        MentionFeedDao mentionFeed
    ) {}
}

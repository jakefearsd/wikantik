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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.core.subsystem.DefaultWikiProperties;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 subsystem-isolation test for {@link PersistenceSubsystemFactory}.
 *
 * <p>Demonstrates that the Persistence subsystem can be instantiated against
 * a Testcontainers Postgres without {@code WikiEngine} or {@code TestEngine}.
 * Every {@link PersistenceSubsystem.Services} field is non-null after a
 * successful {@code create()}.</p>
 */
@RequiresPostgres
final class PersistenceSubsystemFactoryTest {

    @Test
    void createPopulatesEveryRepositoryField() throws Exception {
        final DataSource dataSource = PostgresTestDb.createDataSource();

        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps(
                dataSource,
                new DefaultWikiProperties( new Properties() ) ) );

        assertNotNull( services.dataSource(),           "dataSource" );
        assertNotNull( services.kgNodes(),              "kgNodes" );
        assertNotNull( services.kgEdges(),              "kgEdges" );
        assertNotNull( services.kgProposals(),          "kgProposals" );
        assertNotNull( services.kgRejections(),         "kgRejections" );
        assertNotNull( services.hubProposals(),         "hubProposals" );
        assertNotNull( services.hubDiscovery(),         "hubDiscovery" );
        assertNotNull( services.contentChunks(),        "contentChunks" );
        assertNotNull( services.chunkEntityMentions(),  "chunkEntityMentions" );
        assertNotNull( services.kgNodeEmbeddings(),     "kgNodeEmbeddings" );
        assertNotNull( services.judgeTimeouts(),        "judgeTimeouts" );
        assertNotNull( services.retrievalQualityDao(),  "retrievalQualityDao" );
        assertNotNull( services.kgExcludedPages(),      "kgExcludedPages" );
        assertNotNull( services.kgClusterPolicy(),      "kgClusterPolicy" );
        assertNotNull( services.pageCanonicalIds(),     "pageCanonicalIds" );
        assertNotNull( services.pageVerification(),     "pageVerification" );
        assertNotNull( services.trustedAuthors(),       "trustedAuthors" );
        assertNotNull( services.comments(),             "comments" );
    }

    @Test
    void mentionServiceResolvesTokenUidsThroughTheSuppliedLookup() throws Exception {
        final DataSource dataSource = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps( dataSource, new DefaultWikiProperties( new Properties() ),
                login -> false, canonicalId -> Optional.empty(),
                uid -> "u-bob".equals( uid ) ? Optional.of( "bob" ) : Optional.empty() ) );
        final UUID commentId = seedComment( dataSource );

        try ( Connection c = dataSource.getConnection() ) {
            services.mentions().recordReply( c, commentId, "alice", "cc @[Bobby](user:u-bob) @[Ghost](user:u-nope)" );
        }

        final var rows = services.mentions().findByComment( commentId );
        assertEquals( 1, rows.size(), "only the resolvable uid is recorded" );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
    }

    @Test
    void mentionServiceIgnoresTokensWhenNoLookupIsSupplied() throws Exception {
        final DataSource dataSource = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps( dataSource, new DefaultWikiProperties( new Properties() ),
                login -> "carol".equals( login ), canonicalId -> Optional.empty() ) );
        final UUID commentId = seedComment( dataSource );

        try ( Connection c = dataSource.getConnection() ) {
            services.mentions().recordReply( c, commentId, "alice", "@[Bobby](user:u-bob) and @carol" );
        }

        final var rows = services.mentions().findByComment( commentId );
        assertEquals( 1, rows.size() );
        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
        assertTrue( rows.stream().noneMatch( m -> "bob".equals( m.mentionedLogin() ) ) );
    }

    /** comment_mentions.comment_id is FK'd to comments(id), which needs a thread. */
    private static UUID seedComment( final DataSource dataSource ) throws Exception {
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        try ( Connection c = dataSource.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comment_threads (id, canonical_id, anchor_exact, status, created_by) "
                    + "VALUES ('" + threadId + "', 'CID-PSF', 'seed', 'open', 'alice')" );
            s.executeUpdate( "INSERT INTO comments (id, thread_id, author, body) VALUES ('" + commentId + "', '"
                    + threadId + "', 'alice', 'seed')" );
        }
        return commentId;
    }

    @Test
    void createRejectsMissingDeps() {
        assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps( null, new DefaultWikiProperties( new Properties() ) ) ) );
    }
}

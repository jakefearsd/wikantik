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

import com.wikantik.PostgresTestContainer;
import com.wikantik.core.subsystem.DefaultWikiProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 3 subsystem-isolation test for {@link PersistenceSubsystemFactory}.
 *
 * <p>Demonstrates that the Persistence subsystem can be instantiated against
 * a Testcontainers Postgres without {@code WikiEngine} or {@code TestEngine}.
 * Every {@link PersistenceSubsystem.Services} field is non-null after a
 * successful {@code create()}.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
final class PersistenceSubsystemFactoryTest {

    @Test
    void createPopulatesEveryRepositoryField() throws Exception {
        final DataSource dataSource = PostgresTestContainer.createDataSource();

        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps(
                dataSource,
                new DefaultWikiProperties( new Properties() ) ) );

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
    }

    @Test
    void createRejectsMissingDeps() {
        assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create( null ) );
        assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create(
            new PersistenceSubsystem.Deps( null, new DefaultWikiProperties( new Properties() ) ) ) );
    }
}

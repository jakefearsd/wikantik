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
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end test of the per-page extraction pipeline against a Testcontainers
 * pgvector instance: five pages all extract IT_ENTITY, which the consolidator
 * must collapse into one pending {@code kg_proposals} row carrying
 * {@code support_count = 5}.
 */
@Testcontainers( disabledWithoutDocker = true )
class PageExtractionEndToEndPgTest extends PageExtractionPgTestBase {

    @BeforeEach
    void setUp() throws Exception {
        seedFiveKafkaPages();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    @Test
    void fivePagesProduceOneConsolidatedKafkaProposal() throws Exception {
        final BootstrapEntityExtractionIndexer indexer = newIndexer();
        runUntilDone( indexer );
        assertFalse( indexer.isRunning() );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 5, s.processedPages() );
        assertEquals( 1, s.consolidatedCandidates(),
            "5 pages all naming Kafka must collapse to 1 consolidated proposal" );
        assertEquals( 1, s.judgeAccepted() );
        assertEquals( 1, s.proposalsInserted() );

        assertEquals( 1, countPendingByName( IT_ENTITY ) );
        assertEquals( 5, supportCountByName( IT_ENTITY ),
            "support_count reflects DISTINCT ON (sourcePage) — one entry per page" );
    }
}

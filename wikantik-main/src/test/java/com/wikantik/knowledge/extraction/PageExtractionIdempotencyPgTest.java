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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-runs the same five-page extraction twice. The second pass must not
 * insert a new pending row, must keep {@code support_count = 5}
 * (DISTINCT ON dedup by source_page), and must move {@code last_seen_at}
 * forward to prove the merge fired.
 */
@Testcontainers( disabledWithoutDocker = true )
class PageExtractionIdempotencyPgTest extends PageExtractionPgTestBase {

    @BeforeEach
    void setUp() throws Exception {
        seedFiveKafkaPages();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    @Test
    void reRunMergesSupportWithoutDuplicates() throws Exception {
        runUntilDone( newIndexer() );
        final Instant lastSeen1 = lastSeenAtByName( IT_ENTITY );
        assertNotNull( lastSeen1, "first run must have produced a pending Kafka proposal" );
        assertEquals( 5, supportCountByName( IT_ENTITY ) );

        // Sleep just past PG's NOW() resolution so last_seen_at is observably
        // newer on the second run. A 1.1s pause is overkill for ms-precision
        // timestamps but stable across machines.
        Thread.sleep( 1_100L );

        runUntilDone( newIndexer() );
        final Instant lastSeen2 = lastSeenAtByName( IT_ENTITY );

        assertEquals( 1, countPendingByName( IT_ENTITY ),
            "second run must merge into the existing row, not insert a new one" );
        assertEquals( 5, supportCountByName( IT_ENTITY ),
            "DISTINCT ON (sourcePage) keeps support_count at 5 — not 10" );
        assertNotNull( lastSeen2 );
        assertTrue( lastSeen2.isAfter( lastSeen1 ),
            "last_seen_at must move forward to prove the merge fired" );
    }
}

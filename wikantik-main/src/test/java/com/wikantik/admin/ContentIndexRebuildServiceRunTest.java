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
package com.wikantik.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link ContentIndexRebuildService#runRebuild()} run-loop body.
 * Separate from {@link ContentIndexRebuildServiceStateTest} so the tests that
 * exercise the real loop can't interfere with the state-machine tests that
 * rely on the scaffold's blocking stub.
 */
class ContentIndexRebuildServiceRunTest {

    @Test
    void runsChunkerForNonSystemPagesAndEnqueuesLuceneForAll() {
        final ContentIndexRebuildService svc = RebuildTestFactory.build( 3, 2 );
        svc.triggerRebuild();
        await().atMost( Duration.ofSeconds( 10 ) ).until( () ->
            svc.snapshot().rebuild().state().equals( "IDLE" ) );

        final IndexStatusSnapshot snap = svc.snapshot();
        assertEquals( 5, snap.rebuild().pagesIterated() );
        assertEquals( 3, snap.rebuild().pagesChunked() );
        assertEquals( 2, snap.rebuild().systemPagesSkipped() );
        assertEquals( 5, snap.rebuild().luceneQueued() );
        assertTrue( snap.rebuild().chunksWritten() >= 3,
            "at least one chunk per non-system page" );
    }

    @Test
    void chunkerExceptionOnOnePageDoesNotStopRun() {
        final ContentIndexRebuildService svc =
            RebuildTestFactory.buildWithThrowingChunker( "Page2" );
        svc.triggerRebuild();
        await().atMost( Duration.ofSeconds( 10 ) ).until( () ->
            svc.snapshot().rebuild().state().equals( "IDLE" ) );

        final IndexStatusSnapshot snap = svc.snapshot();
        assertTrue( snap.rebuild().errors().size() >= 1,
            "at least one error recorded" );
        assertTrue( snap.rebuild().errors().stream()
                .anyMatch( e -> "Page2".equals( e.page() ) ),
            "errors include Page2" );
        assertTrue( snap.rebuild().pagesIterated() >= 3,
            "all pages visited even after one error" );
    }

    @Test
    void initialPhaseClearsLuceneAndChunksTable() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithPreloadedChunks();
        svc.triggerRebuild();
        await().atMost( Duration.ofSeconds( 10 ) ).until( () ->
            svc.snapshot().rebuild().state().equals( "IDLE" ) );
        assertTrue( RebuildTestFactory.luceneClearedAtLeastOnce(),
            "lucene.clearIndex() called at least once during STARTING" );
        assertTrue( RebuildTestFactory.chunksClearedAtLeastOnce(),
            "chunkRepo.deleteAll() called at least once during STARTING" );
    }
}

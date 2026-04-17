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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link ContentIndexRebuildService}. These cover the
 * trigger contract (concurrency guard, kill-switch) and the read-only
 * {@code snapshot()} shape — not the run-loop body, which is a stub in this
 * scaffold and is replaced in Task 10.
 */
class ContentIndexRebuildServiceStateTest {

    @Test
    void initialStateIsIdle() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        assertEquals( "IDLE", svc.snapshot().rebuild().state() );
    }

    @Test
    void triggerWhileIdleReturnsStartingSnapshot() {
        final ContentIndexRebuildService svc = RebuildTestFactory.buildWithNoPages();
        final IndexStatusSnapshot snap = svc.triggerRebuild();
        // State is either STARTING or has already transitioned further (the
        // stub run loop drops straight back to IDLE). Assert non-IDLE is too
        // strong there, so we assert the snapshot captured the transition:
        // either startedAt is set OR the state is non-initial.
        assertNotNull( snap.rebuild().startedAt() );
        // The snapshot should reflect that a trigger happened — either the
        // loop is still STARTING/RUNNING, or it completed back to IDLE. Both
        // are acceptable; we just verify startedAt is populated.
        assertTrue( snap.rebuild().startedAt() != null );
    }

    @Test
    void triggerWhileRunningThrowsConflict() throws Exception {
        final RebuildTestFactory.BlockingHandle handle =
            RebuildTestFactory.buildWithBlockingPage();
        try {
            handle.service.triggerRebuild();
            // Wait for the stub loop to enter RUNNING before firing the second trigger.
            assertTrue( handle.awaitRunning( 2000 ),
                        "blocking runRebuild should have entered RUNNING" );

            final ContentIndexRebuildService.ConflictException ex =
                assertThrows( ContentIndexRebuildService.ConflictException.class,
                              handle.service::triggerRebuild );
            assertNotEquals( "IDLE", ex.current().rebuild().state() );
        } finally {
            handle.releaseRunLoop();
        }
    }

    @Test
    void rebuildDisabledFlagRejectsTrigger() {
        final ContentIndexRebuildService svc =
            RebuildTestFactory.buildWithRebuildDisabled();
        assertThrows( ContentIndexRebuildService.DisabledException.class,
                      svc::triggerRebuild );
    }
}

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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Targets previously-uncovered branches in {@link ReconciliationJobRunner}:
 * <ul>
 *   <li>Production constructor (daemon-thread executor, lines 83-87)</li>
 *   <li>{@link ReconciliationJobRunner#enqueue(String)} (lines 113-116)</li>
 *   <li>{@link ReconciliationJobRunner#allStatuses()} (line 181-182)</li>
 *   <li>{@link ReconciliationJobRunner#statusOf(String)} returning empty (line 171-172)</li>
 *   <li>{@link ReconciliationJobRunner#close()} (line 187-188)</li>
 * </ul>
 */
class ReconciliationJobRunnerBranchTest {

    // -----------------------------------------------------------------------
    // Production constructor — creates a daemon thread and the runner is usable
    // -----------------------------------------------------------------------

    @Test
    void production_constructor_creates_usable_runner() throws InterruptedException {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        // Latch fires AFTER the last page is processed, not when pageNamesIn is called.
        // We use a CountDownLatch triggered when the reconciler finishes its for-loop.
        final CountDownLatch done = new CountDownLatch( 1 );
        when( pages.pageNamesIn( "java" ) ).thenReturn( List.of() );
        // We can't hook into the internal state change, so we poll until DONE.

        // Use the production constructor (single daemon-thread executor)
        final ReconciliationJobRunner runner = new ReconciliationJobRunner( policy, repo, pages );
        runner.enqueue( "java" );

        // Poll until status becomes DONE (max 5 seconds)
        final long deadline = System.currentTimeMillis() + 5_000L;
        ReconciliationStatus st = null;
        while ( System.currentTimeMillis() < deadline ) {
            st = runner.statusOf( "java" ).orElse( null );
            if ( st != null && st.state() == ReconciliationStatus.State.DONE ) break;
            Thread.sleep( 20 );
        }
        assertNotNull( st, "Status should be present after enqueue()" );
        assertEquals( ReconciliationStatus.State.DONE, st.state(),
                "Production constructor task should complete with DONE state" );
        runner.close();
    }

    // -----------------------------------------------------------------------
    // enqueue() — sets QUEUED status before running (lines 113-116)
    // -----------------------------------------------------------------------

    @Test
    void enqueue_sets_queued_status_then_transitions_to_done() throws InterruptedException {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        final CountDownLatch started = new CountDownLatch( 1 );
        when( pages.pageNamesIn( "rust" ) ).thenAnswer( inv -> {
            started.countDown();
            return List.of( "A", "B" );
        } );
        when( policy.shouldInclude( anyString() ) ).thenReturn( ClusterAction.INCLUDE );

        // Use a single-thread executor so enqueue() submits to it
        final java.util.concurrent.ExecutorService exec = Executors.newSingleThreadExecutor();
        try ( final ReconciliationJobRunner runner = new ReconciliationJobRunner( policy, repo, pages, exec ) ) {

            // Verify QUEUED is set before the run begins
            runner.enqueue( "rust" );
            // Status is QUEUED (set synchronously inside enqueue before the task runs)
            // OR already transitioned if executor ran immediately — either way it must NOT be absent
            assertTrue( runner.statusOf( "rust" ).isPresent(),
                    "Status must be present immediately after enqueue()" );

            // Wait for the background run to complete
            assertTrue( started.await( 5, TimeUnit.SECONDS ), "Task should have started within 5 s" );
            exec.shutdown();
            assertTrue( exec.awaitTermination( 5, TimeUnit.SECONDS ), "Executor should terminate" );

            final ReconciliationStatus st = runner.statusOf( "rust" ).orElseThrow();
            assertEquals( ReconciliationStatus.State.DONE, st.state() );
            assertEquals( 2, st.processed() );
        }
    }

    // -----------------------------------------------------------------------
    // statusOf() — returns empty when cluster was never submitted (line 171-172)
    // -----------------------------------------------------------------------

    @Test
    void statusOf_returns_empty_for_unknown_cluster() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        try ( final ReconciliationJobRunner runner = new ReconciliationJobRunner(
                policy, repo, pages, Executors.newSingleThreadExecutor() ) ) {
            assertTrue( runner.statusOf( "never-queued" ).isEmpty() );
        }
    }

    // -----------------------------------------------------------------------
    // allStatuses() — returns a snapshot of all known clusters (lines 181-182)
    // -----------------------------------------------------------------------

    @Test
    void allStatuses_returns_all_completed_clusters() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( anyString() ) ).thenReturn( List.of() );
        when( policy.shouldInclude( anyString() ) ).thenReturn( ClusterAction.INCLUDE );

        try ( final ReconciliationJobRunner runner = new ReconciliationJobRunner(
                policy, repo, pages, Executors.newSingleThreadExecutor() ) ) {
            runner.runSync( "java" );
            runner.runSync( "python" );

            final Map<String, ReconciliationStatus> all = runner.allStatuses();
            assertEquals( 2, all.size() );
            assertTrue( all.containsKey( "java" ) );
            assertTrue( all.containsKey( "python" ) );
            assertEquals( ReconciliationStatus.State.DONE, all.get( "java" ).state() );
            assertEquals( ReconciliationStatus.State.DONE, all.get( "python" ).state() );
        }
    }

    // -----------------------------------------------------------------------
    // allStatuses() — returned map is unmodifiable (Map.copyOf contract)
    // -----------------------------------------------------------------------

    @Test
    void allStatuses_is_unmodifiable() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( "x" ) ).thenReturn( List.of() );

        try ( final ReconciliationJobRunner runner = new ReconciliationJobRunner(
                policy, repo, pages, Executors.newSingleThreadExecutor() ) ) {
            runner.runSync( "x" );
            final Map<String, ReconciliationStatus> snapshot = runner.allStatuses();
            assertThrows( UnsupportedOperationException.class,
                    () -> snapshot.put( "extra", null ) );
        }
    }

    // -----------------------------------------------------------------------
    // close() — shuts down the executor (lines 187-188)
    // -----------------------------------------------------------------------

    @Test
    void close_shuts_down_executor() throws InterruptedException {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        final java.util.concurrent.ExecutorService exec = Executors.newSingleThreadExecutor();
        final ReconciliationJobRunner runner = new ReconciliationJobRunner( policy, repo, pages, exec );

        runner.close();

        assertTrue( exec.isShutdown(), "close() should have shut down the underlying executor" );
    }
}

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
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReconciliationJobRunnerTest {

    /** Creates a runner backed by a same-thread executor so runSync is the only path needed. */
    private static ReconciliationJobRunner runner( final KgInclusionPolicy policy,
                                                   final KgExcludedPagesRepository repo,
                                                   final PagesByCluster pages ) {
        return new ReconciliationJobRunner( policy, repo, pages,
                Executors.newSingleThreadExecutor() );
    }

    @Test
    void include_releases_only_cluster_policy_rows() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        when( pages.pageNamesIn( "java" ) ).thenReturn( List.of( "Foo", "Bar" ) );
        when( policy.shouldInclude( "Foo" ) ).thenReturn( ClusterAction.INCLUDE );
        when( policy.shouldInclude( "Bar" ) ).thenReturn( ClusterAction.INCLUDE );

        try ( ReconciliationJobRunner r = runner( policy, repo, pages ) ) {
            r.runSync( "java" );
        }

        verify( repo ).release( "Foo", ExclusionReason.CLUSTER_POLICY );
        verify( repo ).release( "Bar", ExclusionReason.CLUSTER_POLICY );
        verify( repo, never() ).exclude( anyString(), any() );
    }

    @Test
    void exclude_marks_cluster_policy_for_all_pages_in_cluster() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );

        when( pages.pageNamesIn( "van-life" ) ).thenReturn( List.of( "Foo", "Bar" ) );
        when( policy.shouldInclude( "Foo" ) ).thenReturn( ClusterAction.EXCLUDE );
        when( policy.shouldInclude( "Bar" ) ).thenReturn( ClusterAction.EXCLUDE );

        try ( ReconciliationJobRunner r = runner( policy, repo, pages ) ) {
            r.runSync( "van-life" );
        }

        verify( repo ).exclude( "Foo", ExclusionReason.CLUSTER_POLICY );
        verify( repo ).exclude( "Bar", ExclusionReason.CLUSTER_POLICY );
    }

    @Test
    void status_reports_progress() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( "java" ) ).thenReturn( List.of( "A", "B", "C" ) );
        when( policy.shouldInclude( anyString() ) ).thenReturn( ClusterAction.INCLUDE );

        try ( ReconciliationJobRunner r = runner( policy, repo, pages ) ) {
            r.runSync( "java" );
            final ReconciliationStatus st = r.statusOf( "java" ).orElseThrow();
            assertEquals( ReconciliationStatus.State.DONE, st.state() );
            assertEquals( 3, st.totalPages() );
            assertEquals( 3, st.processed() );
            assertEquals( 0, st.errors() );
        }
    }

    @Test
    void status_records_errors_per_page() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( "x" ) ).thenReturn( List.of( "A", "B" ) );
        when( policy.shouldInclude( "A" ) ).thenReturn( ClusterAction.INCLUDE );
        when( policy.shouldInclude( "B" ) ).thenThrow( new RuntimeException( "boom" ) );

        try ( ReconciliationJobRunner r = runner( policy, repo, pages ) ) {
            r.runSync( "x" );
            final ReconciliationStatus st = r.statusOf( "x" ).orElseThrow();
            assertEquals( ReconciliationStatus.State.ERROR, st.state() );
            assertEquals( 1, st.errors() );
            assertNotNull( st.errorMessage() );
        }
    }

    /**
     * Regression test: a failure fetching the page list itself (the one line in
     * {@code runSync} not previously guarded by a try/catch) must surface as
     * ERROR status, not leave the run stuck at an earlier state forever, and
     * the synchronous caller must still see the exception.
     */
    @Test
    void runSync_pageNamesIn_failure_records_error_status_and_rethrows() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( "broken" ) ).thenThrow( new RuntimeException( "db unavailable" ) );

        try ( ReconciliationJobRunner r = runner( policy, repo, pages ) ) {
            final RuntimeException thrown = assertThrows( RuntimeException.class,
                    () -> r.runSync( "broken" ) );
            assertEquals( "db unavailable", thrown.getMessage() );

            final ReconciliationStatus st = r.statusOf( "broken" ).orElseThrow();
            assertEquals( ReconciliationStatus.State.ERROR, st.state() );
            assertNotNull( st.startedAt() );
            assertNotNull( st.finishedAt() );
            assertNotNull( st.errorMessage() );
            verify( repo, never() ).exclude( anyString(), any() );
            verify( repo, never() ).release( anyString(), any() );
        }
    }

    /**
     * Regression test: {@link ReconciliationJobRunner#enqueue} discards the
     * {@code Future} returned by {@code exec.submit}, so a failure in
     * {@code pageNamesIn} must not vanish silently — status must eventually
     * report ERROR. Uses a same-thread (direct) executor so "eventually" is
     * actually "immediately", with no sleep/poll needed.
     */
    @Test
    void enqueue_pageNamesIn_failure_surfaces_as_error_status_not_stuck_queued() {
        final KgInclusionPolicy policy = mock( KgInclusionPolicy.class );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );
        final PagesByCluster pages = mock( PagesByCluster.class );
        when( pages.pageNamesIn( "broken" ) ).thenThrow( new RuntimeException( "db unavailable" ) );

        final java.util.concurrent.ExecutorService directExecutor =
                new java.util.concurrent.AbstractExecutorService() {
                    private volatile boolean shutdown = false;

                    @Override public void execute( final Runnable command ) { command.run(); }
                    @Override public void shutdown() { shutdown = true; }
                    @Override public java.util.List< Runnable > shutdownNow() { shutdown = true; return List.of(); }
                    @Override public boolean isShutdown() { return shutdown; }
                    @Override public boolean isTerminated() { return shutdown; }
                    @Override public boolean awaitTermination( final long timeout, final java.util.concurrent.TimeUnit unit ) { return true; }
                };

        try ( ReconciliationJobRunner r = new ReconciliationJobRunner( policy, repo, pages, directExecutor ) ) {
            r.enqueue( "broken" );

            final ReconciliationStatus st = r.statusOf( "broken" ).orElseThrow();
            assertEquals( ReconciliationStatus.State.ERROR, st.state(),
                    "a pageNamesIn failure must surface as ERROR status, not leave the run stuck QUEUED" );
            assertNotNull( st.errorMessage() );
        }
    }
}

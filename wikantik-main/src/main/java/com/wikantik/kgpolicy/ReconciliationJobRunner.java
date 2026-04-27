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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Eagerly reconciles the {@code kg_excluded_pages} table against the current
 * cluster policy whenever a cluster's policy changes.
 *
 * <p>Each reconciliation run iterates every page in the cluster, calling
 * {@link KgInclusionPolicy#shouldInclude} and then either
 * {@link KgExcludedPagesRepository#exclude} or
 * {@link KgExcludedPagesRepository#release} with reason
 * {@link ExclusionReason#CLUSTER_POLICY}.  Per-page failures are caught
 * individually so one bad page never aborts the entire cluster run.</p>
 *
 * <h2>Threading model</h2>
 * <p>{@link #enqueue} submits work to a single daemon thread and returns
 * immediately.  {@link #runSync} executes on the calling thread and is also
 * used by tests (via try-with-resources on a direct-executor overload).</p>
 *
 * <h2>Status lifecycle</h2>
 * <pre>
 *   (not present) → QUEUED → RUNNING → DONE
 *                                    → ERROR (if any per-page errors occurred)
 * </pre>
 *
 * <p>A second {@link #enqueue} for the same cluster replaces the previous
 * status entry atomically.</p>
 */
public class ReconciliationJobRunner implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( ReconciliationJobRunner.class );

    private final KgInclusionPolicy policy;
    private final KgExcludedPagesRepository excluded;
    private final PagesByCluster pages;

    private final ConcurrentMap< String, ReconciliationStatus > status = new ConcurrentHashMap<>();
    private final ExecutorService exec;

    /**
     * Production constructor — uses a single named daemon thread.
     *
     * @param policy   inclusion policy (provides per-page decisions)
     * @param excluded repository for the {@code kg_excluded_pages} table
     * @param pages    maps cluster name → list of page slugs
     */
    public ReconciliationJobRunner( final KgInclusionPolicy policy,
                                    final KgExcludedPagesRepository excluded,
                                    final PagesByCluster pages ) {
        this( policy, excluded, pages,
                Executors.newSingleThreadExecutor( r -> {
                    final Thread t = new Thread( r, "kgpolicy-reconcile" );
                    t.setDaemon( true );
                    return t;
                } ) );
    }

    /**
     * Package-private constructor for tests — allows injecting a synchronous or
     * custom {@link ExecutorService}.
     */
    ReconciliationJobRunner( final KgInclusionPolicy policy,
                             final KgExcludedPagesRepository excluded,
                             final PagesByCluster pages,
                             final ExecutorService exec ) {
        this.policy   = policy;
        this.excluded = excluded;
        this.pages    = pages;
        this.exec     = exec;
    }

    /**
     * Asynchronously enqueues a reconciliation run for {@code cluster}.
     * Idempotent — enqueueing a second time replaces the status entry with
     * {@code QUEUED} and submits a new task; the previous run (if still in
     * flight) continues to completion but its final status update will be
     * overwritten by the new run.
     *
     * @param cluster cluster name to reconcile
     */
    public void enqueue( final String cluster ) {
        status.put( cluster, queued( cluster ) );
        exec.submit( () -> runSync( cluster ) );
    }

    /**
     * Synchronously reconciles {@code cluster} on the calling thread.
     * Transitions: {@code RUNNING} → {@code DONE} | {@code ERROR}.
     *
     * <p>Updates the status map after every page so that callers polling
     * {@link #statusOf} can observe progress in real time.</p>
     *
     * @param cluster cluster name to reconcile
     */
    public void runSync( final String cluster ) {
        final List< String > all = pages.pageNamesIn( cluster );
        ReconciliationStatus s = new ReconciliationStatus(
                cluster, ReconciliationStatus.State.RUNNING,
                all.size(), 0, 0, Instant.now(), null, null );
        status.put( cluster, s );

        int processed = 0;
        int errors    = 0;

        for ( final String page : all ) {
            try {
                if ( policy.shouldInclude( page ) == ClusterAction.EXCLUDE ) {
                    excluded.exclude( page, ExclusionReason.CLUSTER_POLICY );
                } else {
                    excluded.release( page, ExclusionReason.CLUSTER_POLICY );
                }
            } catch ( final RuntimeException e ) {
                errors++;
                LOG.warn( "Reconcile failure for page '{}' in cluster '{}': {}",
                        page, cluster, e.getMessage() );
            }
            processed++;
            status.put( cluster, new ReconciliationStatus(
                    cluster, ReconciliationStatus.State.RUNNING,
                    all.size(), processed, errors, s.startedAt(), null, null ) );
        }

        final ReconciliationStatus.State end = errors == 0
                ? ReconciliationStatus.State.DONE
                : ReconciliationStatus.State.ERROR;
        status.put( cluster, new ReconciliationStatus(
                cluster, end, all.size(), processed, errors,
                s.startedAt(), Instant.now(),
                errors == 0 ? null : errors + " errors during reconciliation" ) );
    }

    /**
     * Returns the latest status snapshot for {@code cluster}, or empty if no
     * run has been submitted for that cluster yet.
     *
     * @param cluster cluster name
     * @return status wrapped in Optional
     */
    public Optional< ReconciliationStatus > statusOf( final String cluster ) {
        return Optional.ofNullable( status.get( cluster ) );
    }

    /**
     * Returns an unmodifiable snapshot of all cluster statuses known to this
     * runner.
     *
     * @return map from cluster name to its most recent status
     */
    public Map< String, ReconciliationStatus > allStatuses() {
        return Map.copyOf( status );
    }

    /** Shuts down the background executor immediately. */
    @Override
    public void close() {
        exec.shutdownNow();
    }

    // -------------------------------------------------------------------------

    private static ReconciliationStatus queued( final String cluster ) {
        return new ReconciliationStatus(
                cluster, ReconciliationStatus.State.QUEUED,
                0, 0, 0, null, null, null );
    }
}

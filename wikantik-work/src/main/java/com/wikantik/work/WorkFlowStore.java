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
package com.wikantik.work;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The facts the flow and DORA figures are computed from. Read only: no figure is ever stored. Reached through
 * {@link WorkStore#notifyStores()}; it has no visibility check, the service pins every query to what the caller may see.
 */
public interface WorkFlowStore {

    /**
     * The items {@code pinned} selects that are done now and whose last move to done fell in {@code [from, to)},
     * by that time. {@code pinned} must name its project: the service pins it from the caller's standing.
     */
    List< ClosedItem > closedItems( ItemFilter pinned, Instant from, Instant to ) throws SQLException;

    /** The items {@code pinned} selects that are in progress or in review now (epics excluded), by id. */
    List< OpenItem > openItems( ItemFilter pinned ) throws SQLException;

    /**
     * The production deployments that started or were verified in {@code [from, to)}, for the project (only those
     * carrying one of its items) or for the product, never both null ({@link IllegalArgumentException}).
     */
    List< DeploymentFact > deployments( Long projectId, Long productId, Instant from, Instant to ) throws SQLException;

    /**
     * The production deployments of these products that went live at or after {@code since}: what ended a failure.
     * Product-wide on purpose: a fix-forward filed under another project still ends this one's failure, and only its
     * time and what it fixes leave the store.
     */
    List< RecoveryEnd > recoveryEnds( Set< Long > productIds, Instant since ) throws SQLException;

    /**
     * The merged changes (not superseded) first shipped in {@code [from, to)}: shipped by the earliest verified
     * production deployment of the change's own product that carries its item and went live at or after the merge. A
     * merge more than {@link #LOOKBACK} before {@code from} cannot be a sample and is not read.
     */
    List< ChangeFact > shippedChanges( Long projectId, Long productId, Instant from, Instant to ) throws SQLException;

    /** How the changes merged in {@code [from, to)} (not superseded) were merged and flagged. */
    DoraReport.MergeMix merges( Long projectId, Long productId, Instant from, Instant to ) throws SQLException;

    /** How far before a window a merge may lie and still be a lead-time sample of it. */
    java.time.Duration LOOKBACK = java.time.Duration.ofDays( 366 );

    /**
     * A production deployment as the DORA figures see it. {@code failedAt} and {@code rolledBackAt} are the times of
     * its history events (a rollback replaces {@code closed_at}, so that column is never used); {@code remediated}
     * says a deployment that was not rejected names this one in {@code remediates_id}.
     */
    record DeploymentFact( long id, long productId, DeploymentState state, Instant startedAt, Instant verifiedAt,
                           Instant failedAt, Instant rolledBackAt, Long remediatesId, boolean remediated ) {
    }

    /** A production deployment that went live, for finding what ended a failure: only its time and what it fixes. */
    record RecoveryEnd( long id, long productId, Instant verifiedAt, Long remediatesId ) {
    }

    /** A merged change: the times its lead time is read from; {@code shippedAt} null when nothing shipped it yet. */
    record ChangeFact( long id, Instant firstCommitAt, Instant openedAt, Instant mergedAt, Instant shippedAt ) {
    }

    /** An item that is {@code done} now, with the time of its last move to done and of its first move to in progress. */
    record ClosedItem( long id, String key, Instant createdAt, Instant firstInProgressAt, Instant closedAt,
                       BigDecimal estimate ) {
    }

    /** An item that is in progress or in review now. */
    record OpenItem( String key, String title, WorkStatus status, Instant createdAt, Instant firstInProgressAt ) {
    }
}

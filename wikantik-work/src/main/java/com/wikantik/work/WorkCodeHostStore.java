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

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Code-host integration storage, reached through {@link WorkNotifyStores#codeHost()}: the product's webhook secret,
 * the code-host usernames learned for wiki users, and the change records the code host reports. Like every store it
 * has no visibility check; {@link WorkCodeHostService} is the only caller. How deliveries become change records, in
 * any order and any number of times (one change per pull request, the lifecycle register, precedence, the unreviewed
 * flag, host times, idempotence), is written down once, in {@code JdbcWorkCodeHost}.
 */
public interface WorkCodeHostStore {

    /**
     * A product's webhook secret row. It never leaves the module and its {@code toString} masks the secret, so a log
     * line, an event value or an assertion message cannot carry it.
     */
    record Hook( long productId, String secret, String createdBy, Instant createdAt, Instant lastReceivedAt,
                 Instant disabledAt ) {

        /** Whether deliveries are accepted. */
        public boolean live() {
            return disabledAt == null;
        }

        @Override
        public String toString() {
            return "Hook[productId=" + productId + ", secret=***, createdBy=" + createdBy + ", createdAt=" + createdAt
                + ", lastReceivedAt=" + lastReceivedAt + ", disabledAt=" + disabledAt + "]";
        }
    }

    Optional< Hook > hook( long productId ) throws SQLException;

    /** Every product's hook, by product id. */
    Map< Long, Hook > hooks() throws SQLException;

    /**
     * Installs a new secret (enabling, re-enabling or rotating) and records a product event {@code hook} in the same
     * transaction; the event never carries the secret.
     *
     * @return true when a live hook was replaced (a rotation)
     */
    boolean putHook( Product product, String secret, Instant at, Actor by ) throws SQLException;

    /** Stamps the hook disabled; false when there is none or it is already disabled. */
    boolean disableHook( Product product, Instant at, Actor by ) throws SQLException;

    /** Records that a verified delivery arrived. */
    void received( long productId, Instant at ) throws SQLException;

    /** What a store call did, and the change it touched (null when nothing was written). */
    record HostWrite( CodeHostOutcome outcome, Change change ) {

        /** Nothing written, and why. */
        public static HostWrite none( final CodeHostOutcome outcome ) {
            return new HostWrite( outcome, null );
        }
    }

    /** A branch push as the change store needs it: the branch, its new head, the earliest new commit, the pusher. */
    record BranchFacts( String branch, String baseBranch, String headSha, java.time.Instant firstCommitAt,
                        CodeHostWho opener, java.time.Instant at ) {
    }

    /** A pull request event as the change store needs it; {@code at} is the host's time (never null). */
    record ReviewFacts( String number, String url, String branch, String baseBranch, String headSha,
                        java.time.Instant at, CodeHostWho author ) {
    }

    /** The step of a pull request's life an event reports. */
    enum ReviewStep { OPENED, OPENED_DRAFT, REOPENED, READY, UPDATED, APPROVED, CLOSED }

    /**
     * A branch push naming {@code item}: opens a change, moves its head or fills an earlier first commit time. One
     * transaction under the product's advisory lock; the system actor only.
     */
    HostWrite branchPush( long productId, Item item, BranchFacts facts, Actor by ) throws SQLException;

    /**
     * A pull request step naming {@code item}: on the pull request's one change, opened by this step when it has none
     * (see {@code JdbcWorkCodeHost} for the rules).
     */
    HostWrite review( long productId, Item item, ReviewFacts facts, ReviewStep step, CodeHostWho reviewer, Actor by )
        throws SQLException;

    /**
     * A push to the default branch as the change store needs it: {@code after} is the commit the branch now points
     * at, {@code pushShas} every commit of the push (distinct or not), {@code reviewNumbers} the {@code #n} in its
     * messages; {@code at} is the host's time.
     */
    record DirectFacts( String branch, String after, String headSha, java.time.Instant firstCommitAt,
                        java.util.Set< String > pushShas, java.util.Set< String > reviewNumbers, CodeHostWho author,
                        java.time.Instant at ) {
    }

    /**
     * A pull request's merge: decided on the locked change under the product's advisory lock; flags it when it is
     * outside {@code policy} (judged for every merge this store records, whoever the author is) and marks a direct
     * change recorded from the same push as superseded.
     */
    HostWrite merge( long productId, Item item, ReviewFacts facts, String mergedSha, CodeHostWho merger,
                     EffectivePolicy policy, Actor by ) throws SQLException;

    /**
     * A push to the default branch that names {@code item}: one direct change per item and merge commit, never a
     * second for the same commit, and none when the push is a pull request's merge already on record. {@code flag}
     * is decided by the caller from the policy ({@link CodeHostFlags#forMerge}).
     */
    HostWrite direct( long productId, Item item, DirectFacts facts, String flagOrNull, Actor by ) throws SQLException;

    /**
     * Items with a merge outside policy that no lead has acknowledged and that is not superseded, newest change
     * first, one row per item, within {@code projectIds}.
     */
    java.util.List< Item > flagged( java.util.Set< Long > projectIds ) throws SQLException;

    /**
     * A lead acknowledges the flag of {@code changeId} (a change of {@code item}): one transaction that locks the
     * change, refuses an unflagged change ({@link WorkConflictException#NOT_FLAGGED}) or one already acknowledged
     * ({@link WorkConflictException#ALREADY_ACKNOWLEDGED}), stamps who and when, and writes
     * {@code change_flag_acknowledged}. An acknowledged flag is never cleared; it stays as the record. A person only.
     */
    Change acknowledgeFlag( Item item, long changeId, Actor by ) throws SQLException;

    /** The wiki login a code-host username was matched to on {@code host}, if one was learned. */
    Optional< String > learned( String host, String username ) throws SQLException;

    /** Remembers a match; an existing one for the same host and username is kept. */
    void learn( String host, String username, String login ) throws SQLException;
}

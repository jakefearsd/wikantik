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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Claims on work items: the atomic claim, release, the expiry sweep and the lists around them. Reached through
 * {@link WorkStore#claims()}. Every ending of a claim writes its event in the same transaction as the change.
 */
public interface WorkClaimStore {

    /**
     * Claims the item for the agent in one statement, or renews the agent's own claim. Empty when the agent is
     * retired or is not a {@code member} (not a viewer) of the item's project, or when the item is not claimable.
     * The holder may always renew its own claim, even if the item became blocked or the agent stopped serving the
     * queue. A fresh take needs an unheld ready or review item in a queue the agent serves, not blocked by an open
     * item. Writes {@code claimed} (a ready item moves to in progress, version +1) or, for the holder,
     * {@code claim_renewed} (nothing else changes). {@code expiry} must be positive.
     */
    Optional< Item > claim( Item item, Actor agent, Duration expiry ) throws SQLException;

    /**
     * Releases the claim the row holds, provided it is still held by {@code item.claimedByAgent()}; empty when
     * that claim is gone. An in-progress item returns to ready. Writes {@code released} naming the holder.
     */
    Optional< Item > release( Item item, Actor by, String note ) throws SQLException;

    /** Expires at most {@code max} lapsed claims, safe to run from several nodes at once; returns the items. */
    List< Item > sweepExpiredClaims( int max ) throws SQLException;

    /**
     * Unclaimed ready or review items in the given projects that sit in a queue, are not blocked and, when
     * {@code agentId} is not null, are in a queue that agent serves; {@code queue}, when not null, narrows to one.
     * Order {@code priority, due date (none last), id}.
     */
    List< Item > claimable( Long agentId, Set< Long > projectIds, String queue, int limit ) throws SQLException;

    /** Items the agent currently holds in the given projects. */
    List< Item > claimsHeldBy( long agentId, Set< Long > projectIds ) throws SQLException;

    /** Items holding a claim, instance-wide. */
    long activeClaimCount() throws SQLException;

    /** Sets {@code retired_at} and releases every claim the agent holds (note {@code agent retired}), atomically.
     *
     * @return how many claims that released; retiring an already-retired agent releases none
     */
    int retireAgent( long agentId, Instant at, Actor by ) throws SQLException;
}

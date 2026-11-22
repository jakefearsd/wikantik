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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place a time taken from a code-host delivery is bounded, and the range every first-commit time must fall
 * in. A delivery can carry any instant JSON can spell, and an unchecked one would reach {@code Timestamp.from} (which
 * throws for instants beyond its range) or PostgreSQL (which refuses years before 4713 BC); either would answer the
 * code host with a failure it retries for ever.
 *
 * <p>The range is 1970-01-01T00:00:00Z to the work clock's now. A host time later than now is now (ruling 6: the
 * code host's clock may run ahead), so a forged future time cannot hold the lifecycle register past events that
 * really come later. A host time before 1970 is not a time anyone reported: it is dropped (an event without a usable
 * time is ignored, never stamped with the clock; a commit's time is treated as not given), never stored and never
 * thrown.
 *
 * <p>An old but valid commit time (a cherry-pick, a rebase that kept author dates, imported history) is recorded as
 * given: the plan decides nothing about plausibility, and the wiki has no better time to put in its place. Part 6
 * (metrics) must therefore cap the lead time it derives from {@code first_commit_at}.
 */
final class CodeHostTimes {

    /** The earliest time accepted from a code host or reported as a first commit. */
    static final Instant FLOOR = Instant.EPOCH;

    private CodeHostTimes() {
    }

    /** {@code raw} within [{@link #FLOOR}, {@code now}]: null when absent or before the floor, {@code now} when later. */
    static Instant bounded( final Instant raw, final Instant now ) {
        if ( raw == null || raw.isBefore( FLOOR ) ) {
            return null;
        }
        return raw.isAfter( now ) ? now : raw;
    }

    /** The event with its own time and every commit's time bounded; everything else as it came. */
    static CodeEvent bounded( final CodeEvent e, final Instant now ) {
        final List< CodeEvent.Commit > commits = new ArrayList<>( e.commits().size() );
        for ( final CodeEvent.Commit c : e.commits() ) {
            commits.add( new CodeEvent.Commit( c.sha(), c.message(), bounded( c.at(), now ), c.distinct(), c.author() ) );
        }
        return new CodeEvent( e.kind(), e.repo(), e.defaultBranch(), e.branch(), e.baseBranch(), e.before(), e.after(),
            bounded( e.at(), now ), commits, e.review(), e.actor(), e.mergedSha() );
    }
}

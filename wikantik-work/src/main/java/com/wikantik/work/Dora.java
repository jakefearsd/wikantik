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

import com.wikantik.work.WorkFlowStore.ChangeFact;
import com.wikantik.work.WorkFlowStore.DeploymentFact;
import com.wikantik.work.WorkFlowStore.RecoveryEnd;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The DORA figures as pure functions over deployment and change facts: no database, no clock. */
final class Dora {

    /** A first commit older than this before a change was opened is not believed: see {@link DoraReport#MAX_COMMIT_LEAD}. */
    static final Duration MAX_COMMIT_LEAD = DoraReport.MAX_COMMIT_LEAD;

    private Dora() {
    }

    /** A production deployment that started in the window and finished: verified, failed or rolled back. */
    static boolean ran( final DeploymentFact f, final MetricWindow w ) {
        return ( f.state() == DeploymentState.VERIFIED || f.state() == DeploymentState.FAILED
            || f.state() == DeploymentState.ROLLED_BACK ) && f.startedAt() != null && w.contains( f.startedAt() );
    }

    /** Failed, rolled back (even after verifying), or fixed forward by a deployment that was not rejected. */
    static boolean failed( final DeploymentFact f ) {
        return f.state() == DeploymentState.FAILED || f.state() == DeploymentState.ROLLED_BACK || f.remediated();
    }

    /** Verifications per week, a row for every week; one later rolled back counts, it reached production. */
    static List< DoraReport.WeekCount > frequency( final List< DeploymentFact > facts, final MetricWindow w ) {
        final Map< LocalDate, int[] > weeks = new LinkedHashMap<>();
        w.weekStarts().forEach( week -> weeks.put( week, new int[ 1 ] ) );
        for ( final DeploymentFact f : facts ) {
            if ( f.verifiedAt() != null && w.contains( f.verifiedAt() ) ) {
                final int[] count = weeks.get( w.weekOf( f.verifiedAt() ) );
                if ( count != null ) {
                    count[ 0 ]++;
                }
            }
        }
        final List< DoraReport.WeekCount > out = new ArrayList<>();
        weeks.forEach( ( week, count ) -> out.add( new DoraReport.WeekCount( week, count[ 0 ] ) ) );
        return out;
    }

    /** Deployments that ran and failed, of those that ran. */
    static DoraReport.Rate changeFailureRate( final List< DeploymentFact > facts, final MetricWindow w ) {
        final List< DeploymentFact > ran = facts.stream().filter( f -> ran( f, w ) ).toList();
        return new DoraReport.Rate( ( int ) ran.stream().filter( Dora::failed ).count(), ran.size() );
    }

    /** Deployments that ran and remediate another, of those that ran. */
    static DoraReport.Rate rework( final List< DeploymentFact > facts, final MetricWindow w ) {
        final List< DeploymentFact > ran = facts.stream().filter( f -> ran( f, w ) ).toList();
        return new DoraReport.Rate( ( int ) ran.stream().filter( f -> f.remediatesId() != null ).count(), ran.size() );
    }

    /** How long failures lasted, and how many have not ended. */
    record Recovery( Durations durations, int unrecovered ) {
    }

    /**
     * For each deployment that ran and failed: from when it went live (else when it failed) to the earliest of its
     * rollback, the verification of a deployment that remediates it, and, only for one that never went live, the
     * next verified deployment of its product. One with no end is {@code unrecovered}, never in the durations.
     */
    static Recovery recovery( final List< DeploymentFact > facts, final List< RecoveryEnd > ends,
                              final MetricWindow w ) {
        final List< Duration > lasted = new ArrayList<>();
        int unrecovered = 0;
        for ( final DeploymentFact f : facts ) {
            if ( !ran( f, w ) || !failed( f ) ) {
                continue;
            }
            final Instant start = start( f );
            final Optional< Instant > end = end( f, start, ends );
            if ( end.isPresent() ) {
                lasted.add( Duration.between( start, end.get() ) );
            } else {
                unrecovered++;
            }
        }
        return new Recovery( Percentiles.of( lasted ), unrecovered );
    }

    /** When a failure began: it went live, else it failed, else it was rolled back, else it started. */
    static Instant start( final DeploymentFact f ) {
        for ( final Instant t : new Instant[] { f.verifiedAt(), f.failedAt(), f.rolledBackAt() } ) {
            if ( t != null ) {
                return t;
            }
        }
        return f.startedAt();
    }

    private static Optional< Instant > end( final DeploymentFact f, final Instant start, final List< RecoveryEnd > ends ) {
        final List< Instant > candidates = new ArrayList<>();
        if ( f.rolledBackAt() != null ) {
            candidates.add( f.rolledBackAt() );
        }
        for ( final RecoveryEnd e : ends ) {
            if ( e.remediatesId() != null && e.remediatesId() == f.id() ) {
                candidates.add( e.verifiedAt() );
            } else if ( f.verifiedAt() == null && e.productId() == f.productId() && f.failedAt() != null
                    && e.verifiedAt().isAfter( f.failedAt() ) ) {
                candidates.add( e.verifiedAt() );
            }
        }
        return candidates.stream().filter( t -> !t.isBefore( start ) ).min( Instant::compareTo );
    }

    /**
     * Lead times, how many changes had no first-commit time, how many had one too old to be believed, and how many
     * were measured from opening because the commit time was missing or later than opening. {@code excluded} counts
     * the changes whose lead time came out negative: they are kept out of the percentiles, and counted here.
     */
    record LeadTime( Durations durations, int withoutCommitTime, int clamped, int withoutUsableCommitTime,
                     int excluded ) {
    }

    /**
     * For each change that shipped: from its first commit (else when it was opened) to the verification of the
     * deployment that shipped it. A first commit more than {@link #MAX_COMMIT_LEAD} before opening, or after it, is
     * moved to the nearest believable time; a negative duration is kept out of the figure and counted as excluded.
     */
    static LeadTime leadTime( final List< ChangeFact > changes ) {
        final List< Duration > lead = new ArrayList<>();
        int without = 0;
        int clamped = 0;
        int unusable = 0;
        int excluded = 0;
        for ( final ChangeFact c : changes ) {
            if ( c.shippedAt() == null ) {
                continue;
            }
            final Instant earliest = c.openedAt().minus( MAX_COMMIT_LEAD );
            Instant start = c.openedAt();
            if ( c.firstCommitAt() == null ) {
                without++;
                unusable++;
            } else if ( c.firstCommitAt().isBefore( earliest ) ) {
                clamped++;
                start = earliest;
            } else if ( c.firstCommitAt().isBefore( c.openedAt() ) ) {
                start = c.firstCommitAt();
            } else if ( c.firstCommitAt().isAfter( c.openedAt() ) ) {
                unusable++;
            }
            final Duration d = Duration.between( start, c.shippedAt() );
            if ( d.isNegative() ) {
                excluded++;
            } else {
                lead.add( d );
            }
        }
        return new LeadTime( Percentiles.of( lead ), without, clamped, unusable, excluded );
    }
}

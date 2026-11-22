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

import java.time.LocalDate;
import java.util.List;

/**
 * The DORA figures over a window, computed at read time: deployment frequency, lead time for changes, change failure
 * rate, failed deployment recovery time, and rework rate. {@code productKey} is null when every product is counted.
 * A rate is a numerator and a denominator, never a quotient: an empty window has 0 of 0, not NaN.
 */
public record DoraReport( LocalDate from, LocalDate to, String zone, String productKey,
                          List< WeekCount > deploymentFrequency, Durations leadTimeForChanges,
                          int changesWithoutCommitTime, int changesWithClampedCommitTime,
                          int changesWithoutUsableCommitTime, Rate changeFailureRate,
                          Durations recoveryTime, int unrecovered, Rate reworkRate, MergeMix merges ) {

    /**
     * A first commit older than this before a change was opened is not believed: the lead time of such a change
     * starts this long before it was opened (a rebase or cherry-pick can carry the date of work from long ago).
     */
    public static final java.time.Duration MAX_COMMIT_LEAD = java.time.Duration.ofDays( 30 );

    /** Production deployments verified in the week starting {@code weekStart}. */
    public record WeekCount( LocalDate weekStart, int deployments ) {
    }

    /** {@code numerator} of {@code denominator}. */
    public record Rate( int numerator, int denominator ) {
    }

    /** How the window's merges happened, and how many of them are outside policy ({@code flagged}). */
    public record MergeMix( int reviewed, int unreviewed, int direct, int flagged, int flaggedUnacknowledged ) {
    }
}

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

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Nearest-rank percentiles, in integer arithmetic (a floating-point {@code ceil} can overshoot by one rank). */
final class Percentiles {

    private Percentiles() {
    }

    /** The value at 1-based rank {@code ceil( percent * n / 100 )} of an ascending list, at least 1 and at most n. */
    static Duration nearestRank( final List< Duration > sortedAscending, final int percent ) {
        final int n = sortedAscending.size();
        final long rank = ( percent * ( long ) n + 99 ) / 100;
        return sortedAscending.get( ( int ) Math.max( 1, Math.min( n, rank ) ) - 1 );
    }

    /** The p50 and p85 of the non-negative values; {@link Durations#NONE} when there are none. */
    static Durations of( final Collection< Duration > values ) {
        final List< Duration > sorted = values.stream().filter( Objects::nonNull ).filter( v -> !v.isNegative() )
            .sorted().toList();
        if ( sorted.isEmpty() ) {
            return Durations.NONE;
        }
        return new Durations( sorted.size(), nearestRank( sorted, 50 ), nearestRank( sorted, 85 ) );
    }
}

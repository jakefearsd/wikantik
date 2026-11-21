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
package com.wikantik.search.hybrid;

/**
 * Decides the query-embedding concurrency limit of an {@link EmbedConcurrencyLimiter} (Strategy). The limiter
 * owns the slots and calls this, serialised, once per finished backend call that measured something.
 */
interface ConcurrencyLimitPolicy {

    /** The limit before any sample arrives. */
    int initialLimit();

    /**
     * One finished backend call.
     *
     * @param limit       the limit now in force
     * @param concurrency calls in flight on average over this call's lifetime, itself included
     * @param rttNanos    how long the call took
     * @param timedOut    the call ran out its timeout ({@code rttNanos} is then the timeout)
     * @param saturated   a call was shed recently: demand is above the limit
     * @param nowNanos    the limiter's clock
     * @return the limit to apply from now on
     */
    int onSample( int limit, double concurrency, long rttNanos, boolean timedOut, boolean saturated, long nowNanos );

    /** Name reported on the admin page and in metrics: {@code fixed} or {@code adaptive}. */
    String mode();

    /** A limit that never moves: the operator measured the embedder and set it. */
    static ConcurrencyLimitPolicy fixed( final int limit ) {
        if( limit <= 0 ) {
            throw new IllegalArgumentException( "a fixed concurrency limit must be > 0, got " + limit );
        }
        return new ConcurrencyLimitPolicy() {
            @Override public int initialLimit() {
                return limit;
            }

            @Override public int onSample( final int current, final double concurrency, final long rttNanos,
                                           final boolean timedOut, final boolean saturated, final long nowNanos ) {
                return current;
            }

            @Override public String mode() {
                return "fixed";
            }
        };
    }
}

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

import java.util.Locale;
import java.util.Properties;
import java.util.function.LongSupplier;

/**
 * How many query embeddings may run at once ({@link EmbedConcurrencyLimiter}). Keys sit under
 * {@code wikantik.search.hybrid.embedder.}: {@code concurrency.mode}, {@code max-in-flight} (the fixed cap) and
 * {@code concurrency.initial|min|max|min-gain} (the adaptive policy).
 *
 * @param mode       {@link Mode#ADAPTIVE}: learn the limit from the embedder's response times; {@link Mode#FIXED}: use {@code fixedLimit}
 * @param fixedLimit the cap in fixed mode
 * @param initial    adaptive: the limit before anything is measured
 * @param min        adaptive: the floor
 * @param max        adaptive: the ceiling
 * @param minGain    adaptive: the least extra throughput (0.10 = 10%) one more parallel call must buy to be allowed
 */
public record EmbedConcurrencyConfig( Mode mode, int fixedLimit, int initial, int min, int max, double minGain ) {

    /** Fixed or learned limit. */
    public enum Mode {
        ADAPTIVE, FIXED;

        static Mode parse( final String raw ) {
            try {
                return valueOf( raw.trim().toUpperCase( Locale.ROOT ) );
            } catch( final IllegalArgumentException e ) {
                throw new IllegalArgumentException( "Invalid value for " + QueryEmbedderConfig.PREFIX
                        + "concurrency.mode: " + raw + " (expected adaptive or fixed)", e );
            }
        }
    }

    public static final Mode DEFAULT_MODE = Mode.ADAPTIVE;
    /**
     * The fixed cap. Matches a typical embedder's parallelism (Ollama {@code OLLAMA_NUM_PARALLEL=4}): measured
     * 2026-11-20, a cap of 4 served as many dense queries as 8 with half the p95 wait.
     */
    public static final int DEFAULT_FIXED_LIMIT = 4;
    public static final int DEFAULT_INITIAL = 4;
    public static final int DEFAULT_MIN = 1;
    public static final int DEFAULT_MAX = 32;
    public static final double DEFAULT_MIN_GAIN = 0.10;

    public EmbedConcurrencyConfig {
        if( mode == null ) {
            throw new IllegalArgumentException( "mode must not be null" );
        }
        if( fixedLimit <= 0 ) {
            throw new IllegalArgumentException( "maxInFlight (the fixed cap) must be > 0, got " + fixedLimit );
        }
        if( min < 1 || min > initial || initial > max ) {
            throw new IllegalArgumentException(
                    "need 1 <= concurrency.min <= concurrency.initial <= concurrency.max, got min=" + min
                            + " initial=" + initial + " max=" + max );
        }
        if( !( minGain > 0.0 && minGain <= 1.0 ) ) {
            throw new IllegalArgumentException( "concurrency.min-gain must be in (0, 1], got " + minGain );
        }
    }

    /** All defaults: adaptive. */
    public static EmbedConcurrencyConfig defaults() {
        return new EmbedConcurrencyConfig( DEFAULT_MODE, DEFAULT_FIXED_LIMIT, DEFAULT_INITIAL, DEFAULT_MIN,
                DEFAULT_MAX, DEFAULT_MIN_GAIN );
    }

    /** A fixed cap of {@code limit}. */
    public static EmbedConcurrencyConfig fixed( final int limit ) {
        return new EmbedConcurrencyConfig( Mode.FIXED, limit, DEFAULT_INITIAL, DEFAULT_MIN, DEFAULT_MAX,
                DEFAULT_MIN_GAIN );
    }

    static EmbedConcurrencyConfig fromProperties( final Properties p ) {
        final String rawMode = p.getProperty( QueryEmbedderConfig.PREFIX + "concurrency.mode" );
        return new EmbedConcurrencyConfig(
                rawMode == null || rawMode.isBlank() ? DEFAULT_MODE : Mode.parse( rawMode ),
                (int) QueryEmbedderConfig.readLong( p, "max-in-flight", DEFAULT_FIXED_LIMIT ),
                (int) QueryEmbedderConfig.readLong( p, "concurrency.initial", DEFAULT_INITIAL ),
                (int) QueryEmbedderConfig.readLong( p, "concurrency.min", DEFAULT_MIN ),
                (int) QueryEmbedderConfig.readLong( p, "concurrency.max", DEFAULT_MAX ),
                QueryEmbedderConfig.readDouble( p, "concurrency.min-gain", DEFAULT_MIN_GAIN ) );
    }

    /** A limiter running this configuration on {@code nanoTime}. */
    EmbedConcurrencyLimiter newLimiter( final LongSupplier nanoTime ) {
        final ConcurrencyLimitPolicy policy = mode == Mode.FIXED
                ? ConcurrencyLimitPolicy.fixed( fixedLimit )
                : new ThroughputGradientPolicy( initial, min, max, minGain );
        return new EmbedConcurrencyLimiter( policy, nanoTime );
    }
}

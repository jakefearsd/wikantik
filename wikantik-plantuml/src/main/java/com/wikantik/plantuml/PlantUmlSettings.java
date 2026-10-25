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
package com.wikantik.plantuml;

/**
 * Tunables for {@link PlantUmlService}.
 *
 * @param enabled         master switch; when false every render is refused with {@code plantuml-disabled}
 * @param threads         render pool size
 * @param queueCapacity   pending renders allowed beyond the running ones (0 = none)
 * @param threadStackBytes stack size of each render thread (PlantUML's layout recurses deeply)
 * @param timeoutMs       longest a render may run once a pool thread starts it, and longest a caller waits for a
 *                        render that has not started yet
 * @param maxSvgBytes     largest accepted raw (pre-scrub) SVG variant, in UTF-8 bytes
 * @param slowRetryMs     how long a {@code too-slow} or {@code render-crashed} refusal is remembered before one retry
 * @param cacheEntries    LRU capacity of the outcome cache, in entries
 * @param cacheMaxBytes   LRU capacity of the outcome cache, in approximate bytes of cached SVG and messages
 */
public record PlantUmlSettings( boolean enabled, int threads, int queueCapacity, long threadStackBytes, long timeoutMs,
                                int maxSvgBytes, long slowRetryMs, int cacheEntries, long cacheMaxBytes ) {

    /** Default {@link #cacheMaxBytes}: 64 MiB. */
    public static final long DEFAULT_CACHE_MAX_BYTES = 67_108_864L;

    /** The settings with the default {@link #cacheMaxBytes}. */
    public PlantUmlSettings( final boolean enabled, final int threads, final int queueCapacity, final long threadStackBytes,
                             final long timeoutMs, final int maxSvgBytes, final long slowRetryMs, final int cacheEntries ) {
        this( enabled, threads, queueCapacity, threadStackBytes, timeoutMs, maxSvgBytes, slowRetryMs, cacheEntries,
            DEFAULT_CACHE_MAX_BYTES );
    }

    /** @return the shipped defaults */
    public static PlantUmlSettings defaults() {
        return new PlantUmlSettings( true, 2, 8, 16_777_216L, 5_000L, 524_288, 600_000L, 500, DEFAULT_CACHE_MAX_BYTES );
    }
}

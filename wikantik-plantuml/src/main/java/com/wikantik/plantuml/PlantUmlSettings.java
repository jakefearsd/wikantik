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
 * @param timeoutMs       longest a single wait for a render may last
 * @param maxSvgBytes     largest accepted SVG variant
 * @param slowRetryMs     how long a {@code too-slow} refusal is remembered before one retry is allowed
 * @param cacheEntries    LRU capacity of the outcome cache
 */
public record PlantUmlSettings( boolean enabled, int threads, int queueCapacity, long threadStackBytes, long timeoutMs,
                                int maxSvgBytes, long slowRetryMs, int cacheEntries ) {

    /** @return the shipped defaults */
    public static PlantUmlSettings defaults() {
        return new PlantUmlSettings( true, 2, 8, 16_777_216L, 5_000L, 2_097_152, 600_000L, 500 );
    }
}

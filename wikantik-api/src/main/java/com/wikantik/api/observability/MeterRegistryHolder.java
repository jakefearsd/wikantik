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
package com.wikantik.api.observability;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Process-wide holder for the Micrometer {@link MeterRegistry} that the
 * observability subsystem publishes to at {@code /observability/metrics}.
 *
 * <p>The observability module's {@code EngineLifecycleExtension} creates the
 * registry during {@code onInit} (before {@code Engine#initialize}) and writes
 * it here. Components wired up inside {@code Engine#initialize} — notably the
 * knowledge-graph chunker and the async rebuild service — read the registry
 * via {@link #get()} so their meters flow to the same registry that is later
 * scraped by the {@code MetricsServlet}.</p>
 *
 * <p>Lives in {@code wikantik-api} so both {@code wikantik-main} (the
 * producers) and {@code wikantik-observability} (the owner) can see it
 * without introducing a cross-module dependency cycle.</p>
 *
 * <p>When {@link #get()} returns {@code null}, callers should fall back to a
 * local {@code SimpleMeterRegistry} and log a WARN — their meters will work
 * in-process but won't appear on the scrape endpoint.</p>
 */
public final class MeterRegistryHolder {

    private static volatile MeterRegistry registry;

    private MeterRegistryHolder() {}

    /** Returns the shared registry, or {@code null} if none is installed. */
    @SuppressFBWarnings( value = "MS_EXPOSE_REP",
            justification = "Intentional: the holder exposes the process-wide MeterRegistry as a shared mutable service." )
    public static MeterRegistry get() {
        return registry;
    }

    /** Installs or replaces the shared registry. Called by the observability extension. */
    @SuppressFBWarnings( value = "EI_EXPOSE_STATIC_REP2",
            justification = "Intentional: the observability extension installs the process-wide registry here." )
    public static void set( final MeterRegistry r ) {
        registry = r;
    }

    /** Clears the shared registry. Called on engine shutdown. */
    public static void clear() {
        registry = null;
    }
}

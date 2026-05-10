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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.observability.MeterRegistryHolder;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Prometheus instrumentation for {@link ReadPagesTool}. Publishes
 * {@code wikantik_read_pages_partial_failures_total} labelled by reason
 * ({@code not_found} | {@code internal_error}). When no shared
 * {@link MeterRegistry} is bound (test harnesses without observability),
 * {@link #recordPartialFailure(String)} is a no-op.
 */
public final class ReadPagesMetrics {

    private static final Logger LOG = LogManager.getLogger( ReadPagesMetrics.class );

    private MeterRegistry registry;

    public void bind( final MeterRegistry registry ) {
        this.registry = registry;
    }

    public void recordPartialFailure( final String reason ) {
        if ( registry != null && reason != null && !reason.isBlank() ) {
            registry.counter( "wikantik_read_pages_partial_failures_total", "reason", reason ).increment();
        }
    }

    public static ReadPagesMetrics resolveAndBind() {
        final ReadPagesMetrics m = new ReadPagesMetrics();
        final MeterRegistry registry = MeterRegistryHolder.get();
        if ( registry == null ) {
            LOG.warn( "No shared MeterRegistry — read_pages partial-failures counter will NOT be scraped." );
        } else {
            m.bind( registry );
        }
        return m;
    }
}

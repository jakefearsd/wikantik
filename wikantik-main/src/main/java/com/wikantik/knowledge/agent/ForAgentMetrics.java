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
package com.wikantik.knowledge.agent;

import com.wikantik.api.observability.MeterRegistryHolder;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Prometheus instrumentation for the {@code /for-agent} projection. Publishes
 * one histogram, {@code wikantik_for_agent_response_bytes}, recording the
 * serialised JSON size of each projection so the operator can see whether
 * pages are landing under the 4 KB / 8 KB budget.
 *
 * <p>When no {@link MeterRegistry} is bound (test harnesses without
 * observability), {@link #recordBytes(int)} is a no-op.</p>
 */
public final class ForAgentMetrics {

    private static final Logger LOG = LogManager.getLogger( ForAgentMetrics.class );

    private DistributionSummary responseBytes;

    public void bind( final MeterRegistry registry ) {
        if ( registry == null ) return;
        this.responseBytes = DistributionSummary.builder( "wikantik_for_agent_response_bytes" )
                .description( "Serialised JSON size of /for-agent projections" )
                .baseUnit( "bytes" )
                .publishPercentileHistogram()
                .register( registry );
    }

    public void recordBytes( final int bytes ) {
        if ( responseBytes != null && bytes >= 0 ) {
            responseBytes.record( bytes );
        }
    }

    public static ForAgentMetrics resolveAndBind() {
        final ForAgentMetrics m = new ForAgentMetrics();
        final MeterRegistry registry = MeterRegistryHolder.get();
        if ( registry == null ) {
            LOG.warn( "No shared MeterRegistry — for-agent response-byte histogram will NOT be scraped." );
        } else {
            m.bind( registry );
        }
        return m;
    }
}

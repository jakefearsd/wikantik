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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForAgentMetricsTest {

    @Test
    void hintsDerivationFailuresIncrement() {
        final ForAgentMetrics m = new ForAgentMetrics();
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        m.bind( reg );
        m.incrementHintsDerivationFailures();
        m.incrementHintsDerivationFailures();
        final Counter c = reg.find( "wikantik_agent_hints_derivation_failures_total" ).counter();
        assertNotNull( c );
        assertEquals( 2.0, c.count(), 0.001 );
    }

    @Test
    void hubSummarySynthesisIncrement() {
        final ForAgentMetrics m = new ForAgentMetrics();
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        m.bind( reg );
        m.incrementHubSummarySynthesis();
        final Counter c = reg.find( "wikantik_hub_summary_synthesis_total" ).counter();
        assertNotNull( c );
        assertEquals( 1.0, c.count(), 0.001 );
    }

    @Test
    void incrementsAreSafeWhenNotBound() {
        // No bind() called.
        final ForAgentMetrics m = new ForAgentMetrics();
        m.incrementHintsDerivationFailures();
        m.incrementHubSummarySynthesis();
        // No exception — no-op when no registry bound.
    }
}

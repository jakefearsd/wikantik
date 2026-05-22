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
package com.wikantik.rest.overview;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricReadsTest {
    @Test
    void gaugeReturnsValueWhenPresentElseDefault() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        reg.gauge( "test.g", 42.0 );
        assertEquals( 42.0, MetricReads.gauge( reg, "test.g", -1.0 ) );
        assertEquals( -1.0, MetricReads.gauge( reg, "missing", -1.0 ) );
        assertEquals( -1.0, MetricReads.gauge( null, "test.g", -1.0 ) );
    }

    @Test void taggedCounterSelectsTag() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        reg.counter("c", "result", "success").increment(3);
        reg.counter("c", "result", "failure").increment();
        assertEquals(3.0, MetricReads.counter(reg, "c", "result", "success", -1));
        assertEquals(1.0, MetricReads.counter(reg, "c", "result", "failure", -1));
        assertEquals(-1.0, MetricReads.counter(reg, "missing", "result", "x", -1));
    }

    @Test void summaryCountAndMean() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        final DistributionSummary s = DistributionSummary.builder("s").register(reg);
        s.record(100); s.record(300);
        assertEquals(2L, MetricReads.summaryCount(reg, "s", 0));
        assertEquals(200.0, MetricReads.summaryMean(reg, "s", 0));
        assertEquals(0L, MetricReads.summaryCount(null, "s", 0));
    }
}

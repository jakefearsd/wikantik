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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReadPagesMetricsTest {

    @Test
    void recordsLabelledIncrement() {
        final ReadPagesMetrics m = new ReadPagesMetrics();
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        m.bind( reg );
        m.recordPartialFailure( "not_found" );
        m.recordPartialFailure( "not_found" );
        m.recordPartialFailure( "internal_error" );
        assertEquals( 2.0, reg.find( "wikantik_read_pages_partial_failures_total" )
                .tag( "reason", "not_found" ).counter().count(), 0.001 );
        assertEquals( 1.0, reg.find( "wikantik_read_pages_partial_failures_total" )
                .tag( "reason", "internal_error" ).counter().count(), 0.001 );
    }

    @Test
    void incrementsAreSafeWhenNotBound() {
        new ReadPagesMetrics().recordPartialFailure( "not_found" );  // no-op, no exception
    }
}

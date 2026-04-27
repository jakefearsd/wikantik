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
package com.wikantik.api.kgpolicy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExclusionReasonTest {

    @Test
    void wire_format_lowercase() {
        assertEquals( "system_page",     ExclusionReason.SYSTEM_PAGE.wire() );
        assertEquals( "page_override",   ExclusionReason.PAGE_OVERRIDE.wire() );
        assertEquals( "cluster_policy",  ExclusionReason.CLUSTER_POLICY.wire() );
    }

    @Test
    void from_wire_round_trips() {
        for ( final ExclusionReason r : ExclusionReason.values() ) {
            assertEquals( r, ExclusionReason.fromWire( r.wire() ).orElseThrow() );
        }
    }

    @Test
    void from_wire_returns_empty_on_unknown() {
        assertTrue( ExclusionReason.fromWire( "nope" ).isEmpty() );
        assertTrue( ExclusionReason.fromWire( null   ).isEmpty() );
    }

    @Test
    void strongest_picks_higher_strength() {
        assertEquals( ExclusionReason.SYSTEM_PAGE,
                ExclusionReason.strongest( ExclusionReason.SYSTEM_PAGE, ExclusionReason.CLUSTER_POLICY ) );
        assertEquals( ExclusionReason.PAGE_OVERRIDE,
                ExclusionReason.strongest( ExclusionReason.CLUSTER_POLICY, ExclusionReason.PAGE_OVERRIDE ) );
    }

    @Test
    void strongest_handles_nulls() {
        assertEquals( ExclusionReason.CLUSTER_POLICY,
                ExclusionReason.strongest( null, ExclusionReason.CLUSTER_POLICY ) );
        assertEquals( ExclusionReason.SYSTEM_PAGE,
                ExclusionReason.strongest( ExclusionReason.SYSTEM_PAGE, null ) );
        assertNull( ExclusionReason.strongest( null, null ) );
    }
}

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

class ClusterActionTest {

    @Test
    void wire_format_lowercase() {
        assertEquals( "include", ClusterAction.INCLUDE.wire() );
        assertEquals( "exclude", ClusterAction.EXCLUDE.wire() );
    }

    @Test
    void from_wire_round_trips() {
        for ( final ClusterAction a : ClusterAction.values() ) {
            assertEquals( a, ClusterAction.fromWire( a.wire() ).orElseThrow() );
        }
    }

    @Test
    void from_wire_accepts_uppercase() {
        // fromWire upper-cases internally, so mixed case is fine
        assertEquals( java.util.Optional.of( ClusterAction.INCLUDE ),
                ClusterAction.fromWire( "INCLUDE" ) );
        assertEquals( java.util.Optional.of( ClusterAction.EXCLUDE ),
                ClusterAction.fromWire( "Exclude" ) );
    }

    @Test
    void from_wire_returns_empty_on_unknown_or_null() {
        assertTrue( ClusterAction.fromWire( "nope" ).isEmpty() );
        assertTrue( ClusterAction.fromWire( null ).isEmpty() );
        assertTrue( ClusterAction.fromWire( "" ).isEmpty() );
    }
}

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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireEnumTest {

    @Test
    void tryParseIsNullForUnknownOrNonString() {
        assertEquals( WorkStatus.DONE, WireEnum.tryParse( WorkStatus.class, "done" ) );
        assertNull( WireEnum.tryParse( WorkStatus.class, "nope" ) );
        assertNull( WireEnum.tryParse( WorkStatus.class, null ) );
        assertNull( WireEnum.tryParse( WorkStatus.class, 7 ) );
    }

    @Test
    void parseThrowsTheEnumSpecificMessage() {
        final WorkValidationException e =
            assertThrows( WorkValidationException.class, () -> Autonomy.fromWire( "bogus" ) );
        assertEquals( "Unknown autonomy: bogus", e.getMessage() );
        assertEquals( "Unknown mergeKind: q",
            assertThrows( WorkValidationException.class, () -> MergeKind.fromWire( "q" ) ).getMessage() );
        assertEquals( "Unknown type: epic",
            assertThrows( WorkValidationException.class, () -> WorkType.fromWire( "epic" ) ).getMessage() );
        assertEquals( "Unknown deployGate: null",
            assertThrows( WorkValidationException.class, () -> DeployGate.fromWire( null ) ).getMessage() );
    }
}

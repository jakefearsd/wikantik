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

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkStatusTest {

    @Test
    void epicAllowsOnlyFour() {
        assertEquals( Set.of( WorkStatus.DRAFT, WorkStatus.IN_PROGRESS, WorkStatus.DONE, WorkStatus.CANCELLED ),
                WorkStatus.allowedFor( WorkLevel.EPIC ) );
    }

    @Test
    void storyAndTaskAllowAllSeven() {
        assertEquals( EnumSet.allOf( WorkStatus.class ), WorkStatus.allowedFor( WorkLevel.STORY ) );
        assertEquals( 7, WorkStatus.allowedFor( WorkLevel.TASK ).size() );
    }

    @Test
    void wireRoundTrips() {
        assertEquals( "in_progress", WorkStatus.IN_PROGRESS.wire() );
        for ( final WorkStatus s : WorkStatus.values() ) {
            assertEquals( s, WorkStatus.fromWire( s.wire() ) );
        }
    }

    @Test
    void fromWireRejectsUnknown() {
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> WorkStatus.fromWire( "bogus" ) );
        assertEquals( "status", e.field() );
        assertThrows( WorkValidationException.class, () -> WorkStatus.fromWire( null ) );
    }
}

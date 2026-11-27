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

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What a sprint may be called and when it may run: every refusal names its field. */
class SprintInputsTest {

    private static final LocalDate D1 = LocalDate.of( 2026, 3, 2 );
    private static final LocalDate D2 = LocalDate.of( 2026, 3, 15 );

    private static String refusedField( final SprintDraft d ) {
        return assertThrows( WorkValidationException.class, () -> SprintInputs.normalise( d ) ).field();
    }

    @Test
    void aNameIsRequiredAndAtMostSixtyCharacters() {
        assertEquals( "name", refusedField( null ) );
        assertEquals( "name", refusedField( new SprintDraft( null, "", D1, D2 ) ) );
        assertEquals( "name", refusedField( new SprintDraft( "   ", "", D1, D2 ) ) );
        assertEquals( "name", refusedField( new SprintDraft( "n".repeat( 61 ), "", D1, D2 ) ) );
        assertEquals( "n".repeat( 60 ), SprintInputs.normalise( new SprintDraft( "n".repeat( 60 ), "", D1, D2 ) ).name() );
        assertEquals( "n".repeat( 60 ), SprintInputs.normalise( new SprintDraft( "  " + "n".repeat( 60 ) + "  ", "", D1, D2 ) ).name(),
            "the limit counts the trimmed name" );
    }

    @Test
    void aGoalIsOptionalTrimmedAndAtMostFiveHundredCharacters() {
        assertEquals( "", SprintInputs.normalise( new SprintDraft( "S", null, D1, D2 ) ).goal() );
        assertEquals( "ship it", SprintInputs.normalise( new SprintDraft( "S", "  ship it ", D1, D2 ) ).goal() );
        assertEquals( "g".repeat( 500 ), SprintInputs.normalise( new SprintDraft( "S", "g".repeat( 500 ), D1, D2 ) ).goal() );
        assertEquals( "goal", refusedField( new SprintDraft( "S", "g".repeat( 501 ), D1, D2 ) ) );
    }

    @Test
    void bothDatesAreRequiredAndTheEndMayBeTheStartDay() {
        assertEquals( "startsOn", refusedField( new SprintDraft( "S", "", null, D2 ) ) );
        assertEquals( "startsOn", refusedField( new SprintDraft( "S", "", null, null ) ) );
        assertEquals( "endsOn", refusedField( new SprintDraft( "S", "", D1, null ) ) );
        assertEquals( "endsOn", refusedField( new SprintDraft( "S", "", D2, D1 ) ) );
        final SprintDraft oneDay = SprintInputs.normalise( new SprintDraft( "S", "", D1, D1 ) );
        assertEquals( D1, oneDay.startsOn() );
        assertEquals( D1, oneDay.endsOn() );
    }
}

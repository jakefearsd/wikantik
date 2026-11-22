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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatchValuesSprintTest {

    @Test
    void anEstimateIsStoredAtScaleOne() {
        assertEquals( new BigDecimal( "3.0" ), PatchValues.estimate( 3 ) );
        assertEquals( 1, PatchValues.estimate( 3 ).scale() );
    }

    @Test
    void halvesAreAccepted() {
        assertEquals( new BigDecimal( "0.0" ), PatchValues.estimate( 0 ) );
        assertEquals( new BigDecimal( "0.5" ), PatchValues.estimate( 0.5 ) );
        assertEquals( new BigDecimal( "999.5" ), PatchValues.estimate( 999.5 ) );
    }

    @Test
    void refusesQuarterNegativeTooLargeAndText() {
        for ( final Object bad : new Object[] { 1.25, -0.5, 1000, "3", Double.NaN } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> PatchValues.estimate( bad ), String.valueOf( bad ) );
            assertEquals( "estimate", e.field() );
        }
    }

    @Test
    void nullClearsBoth() {
        assertNull( PatchValues.estimate( null ) );
        assertNull( PatchValues.sprintId( null ) );
    }

    @Test
    void aSprintIdIsAWholePositiveNumber() {
        assertEquals( 7L, PatchValues.sprintId( 7 ) );
        assertEquals( 7L, PatchValues.sprintId( 7.0 ) );
        for ( final Object bad : new Object[] { 2.5, 0, "4" } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> PatchValues.sprintId( bad ), String.valueOf( bad ) );
            assertEquals( "sprint", e.field() );
        }
    }
}

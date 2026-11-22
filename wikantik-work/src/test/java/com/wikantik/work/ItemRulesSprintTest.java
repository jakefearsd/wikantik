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
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemRulesSprintTest {

    private static Item epic( final Long sprintId, final String estimate ) {
        return new Item( 1, 1, "P-1", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE, "Epic", "", WorkStatus.DRAFT, 3, 0,
            null, null, null, null, null, null, 1, null, null, null, null, null, null, null, null, WorkType.FEATURE,
            null, sprintId, estimate == null ? null : new BigDecimal( estimate ) );
    }

    @Test
    void anEpicHasNoEstimateOfItsOwn() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> ItemRules.validate( epic( null, "3" ), null ) );
        assertEquals( "estimate", e.field() );
    }

    @Test
    void anEpicIsNotPlannedIntoASprint() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> ItemRules.validate( epic( 4L, null ), null ) );
        assertEquals( "sprint", e.field() );
    }

    @Test
    void anEpicWithNeitherIsValid() {
        ItemRules.validate( epic( null, null ), null );
    }
}

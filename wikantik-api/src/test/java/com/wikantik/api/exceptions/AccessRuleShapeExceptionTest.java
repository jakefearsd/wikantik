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
package com.wikantik.api.exceptions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A refusal keeps the first {@link AccessRuleShapeException#MAX_VIOLATIONS} violations and counts the rest. */
class AccessRuleShapeExceptionTest {

    private static List< AccessRuleShapeException.Violation > violations( final int n ) {
        return IntStream.rangeClosed( 1, n ).mapToObj( i -> new AccessRuleShapeException.Violation(
                AccessRuleShapeException.CODE, "DENY", i, null, "[{DENY view Bob}]", "violation " + i + "." ) ).toList();
    }

    @Test
    void aFewViolationsAreAllKept() {
        final AccessRuleShapeException e = new AccessRuleShapeException( violations( 3 ) );
        assertEquals( 3, e.violations().size() );
        assertEquals( 3, e.total() );
        assertFalse( e.getMessage().contains( "more" ), e.getMessage() );
    }

    @Test
    void manyViolationsAreCappedAndCounted() {
        final AccessRuleShapeException e = new AccessRuleShapeException( violations( 10_000 ) );
        assertEquals( AccessRuleShapeException.MAX_VIOLATIONS, e.violations().size() );
        assertEquals( 10_000, e.total() );
        assertTrue( e.getMessage().contains( "violation 50." ), e.getMessage() );
        assertFalse( e.getMessage().contains( "violation 51." ), e.getMessage() );
        assertTrue( e.getMessage().endsWith( "and 9950 more" ), e.getMessage() );
    }

    @Test
    void aTotalCountedElsewhereIsKept() {
        final AccessRuleShapeException e = new AccessRuleShapeException( violations( 50 ), 7_000 );
        assertEquals( 50, e.violations().size() );
        assertEquals( 7_000, e.total() );
        assertTrue( e.getMessage().endsWith( "and 6950 more" ), e.getMessage() );
    }
}

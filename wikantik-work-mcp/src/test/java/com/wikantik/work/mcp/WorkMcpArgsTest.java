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
package com.wikantik.work.mcp;

import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** How an agent's arguments are read: absent is null, the wrong type is refused by name, boundaries are exact. */
class WorkMcpArgsTest {

    private static Map< String, Object > with( final String key, final Object value ) {
        final Map< String, Object > m = new HashMap<>();
        m.put( key, value );
        return m;
    }

    private static void refuses( final String field, final String message, final Runnable read ) {
        final WorkValidationException e = assertThrows( WorkValidationException.class, read::run );
        assertEquals( field, e.field() );
        assertEquals( message, e.getMessage() );
    }

    @Test
    void numbersAreReadAsTheyArriveAndAbsentIsNull() {
        assertNull( WorkMcpArgs.intArg( Map.of(), "n" ) );
        assertNull( WorkMcpArgs.intArg( with( "n", null ), "n" ) );
        assertEquals( 3, WorkMcpArgs.intArg( Map.of( "n", 3 ), "n" ) );
        assertEquals( 3, WorkMcpArgs.intArg( Map.of( "n", 3.9 ), "n" ), "a JSON double is truncated to an int" );
        refuses( "n", "must be a number.", () -> WorkMcpArgs.intArg( Map.of( "n", "3" ), "n" ) );
        assertNull( WorkMcpArgs.decimalArg( with( "n", null ), "n" ) );
        assertEquals( 2.5, WorkMcpArgs.decimalArg( Map.of( "n", 2.5 ), "n" ) );
        refuses( "n", "must be a number.", () -> WorkMcpArgs.decimalArg( Map.of( "n", true ), "n" ) );
    }

    @Test
    void textIsReadAsItArrivesAndAnythingElseIsRefused() {
        assertNull( WorkMcpArgs.stringArg( Map.of(), "s" ) );
        assertNull( WorkMcpArgs.stringArg( with( "s", null ), "s" ) );
        assertEquals( " x ", WorkMcpArgs.stringArg( Map.of( "s", " x " ), "s" ), "not trimmed here" );
        refuses( "s", "must be a string.", () -> WorkMcpArgs.stringArg( Map.of( "s", 5 ), "s" ) );
    }

    @Test
    void aLabelListIsNullWhenAbsentEmptyWhenNullAndCopiedWhenGiven() {
        assertNull( WorkMcpArgs.stringListArg( Map.of(), "labels" ), "absent: leave the labels alone" );
        assertEquals( List.of(), WorkMcpArgs.stringListArg( with( "labels", null ), "labels" ), "null: clear them" );
        assertEquals( List.of(), WorkMcpArgs.stringListArg( Map.of( "labels", List.of() ), "labels" ) );
        assertEquals( List.of( "a", "b" ), WorkMcpArgs.stringListArg( Map.of( "labels", List.of( "a", "b" ) ), "labels" ) );
        refuses( "labels", "must be a list of labels.", () -> WorkMcpArgs.stringListArg( Map.of( "labels", "ui" ), "labels" ) );
        refuses( "labels", "must be a list of labels.", () ->
            WorkMcpArgs.stringListArg( Map.of( "labels", Arrays.asList( "a", 2 ) ), "labels" ) );
        refuses( "labels", "must be a list of labels.", () ->
            WorkMcpArgs.stringListArg( Map.of( "labels", Arrays.asList( "a", null ) ), "labels" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", " ", "\t\n" } )
    void aBlankItemKeyIsTheSameAnswerAsAnUnknownItem( final String blank ) {
        assertThrows( WorkNotFoundException.class, () -> WorkMcpArgs.itemKey( Map.of( "key", blank ) ) );
    }

    @Test
    void anAbsentItemKeyIsNotFoundAndAKeyIsTrimmed() {
        assertThrows( WorkNotFoundException.class, () -> WorkMcpArgs.itemKey( Map.of() ) );
        assertThrows( WorkNotFoundException.class, () -> WorkMcpArgs.itemKey( with( "key", null ) ) );
        assertEquals( "PLAT-1", WorkMcpArgs.itemKey( Map.of( "key", "  PLAT-1\n" ) ) );
        refuses( "key", "must be a string.", () -> WorkMcpArgs.itemKey( Map.of( "key", 1 ) ) );
    }

    @Test
    void anIdMustBeAWholeNumberAndIsRequired() {
        assertEquals( 7L, WorkMcpArgs.idArg( Map.of( "id", 7 ), "id" ) );
        assertEquals( 7L, WorkMcpArgs.idArg( Map.of( "id", 7.0 ), "id" ), "a JSON 7.0 is 7" );
        assertEquals( 5_000_000_000L, WorkMcpArgs.idArg( Map.of( "id", 5_000_000_000L ), "id" ) );
        refuses( "id", "is required.", () -> WorkMcpArgs.idArg( Map.of(), "id" ) );
        refuses( "id", "must be a whole number.", () -> WorkMcpArgs.idArg( Map.of( "id", 7.5 ), "id" ) );
        refuses( "id", "must be a whole number.", () -> WorkMcpArgs.idArg( Map.of( "id", "7" ), "id" ) );
    }

    @Test
    void requiredTextAndNumbersAreRefusedWhenMissingBlankOrWrong() {
        assertEquals( "x", WorkMcpArgs.requiredString( Map.of( "s", "x" ), "s" ) );
        refuses( "s", "is required.", () -> WorkMcpArgs.requiredString( Map.of(), "s" ) );
        refuses( "s", "is required.", () -> WorkMcpArgs.requiredString( Map.of( "s", "  " ), "s" ) );
        assertEquals( 4, WorkMcpArgs.requiredInt( Map.of( "n", 4 ), "n" ) );
        refuses( "n", "is required.", () -> WorkMcpArgs.requiredInt( Map.of(), "n" ) );
    }

    @Test
    void anArgumentOutsideTheAllowedSetIsRefusedByNameWithItsOwnReasonWhenItHasOne() {
        WorkMcpArgs.rejectOthers( Map.of( "a", 1 ), Set.of( "a", "b" ), Map.of() );
        WorkMcpArgs.rejectOthers( Map.of(), Set.of(), Map.of() );
        refuses( "zzz", "Unknown argument.", () -> WorkMcpArgs.rejectOthers( Map.of( "a", 1, "zzz", 2 ), Set.of( "a" ), Map.of() ) );
        refuses( "status", "Not here.", () -> WorkMcpArgs.rejectOthers( Map.of( "status", "x" ), Set.of( "a" ),
            Map.of( "status", "Not here." ) ) );
        refuses( "other", "Unknown argument.", () -> WorkMcpArgs.rejectOthers( Map.of( "other", "x" ), Set.of( "a" ),
            Map.of( "status", "Not here." ) ) );
    }

    @Test
    void theSchemaHelpersShapeTheirOutputExactly() {
        assertEquals( Map.of( "type", "integer", "description", "d", "examples", List.of( 3 ) ),
            WorkMcpArgs.prop( "integer", "d", 3 ) );
        final Map< String, Object > labels = WorkMcpArgs.labelsSchema();
        assertEquals( "array", labels.get( "type" ) );
        assertEquals( Map.of( "type", "string" ), labels.get( "items" ) );
        assertEquals( List.of( List.of( "ui", "billing" ) ), labels.get( "examples" ) );
        assertEquals( true, ( ( String ) labels.get( "description" ) ).contains( "at most 10" ) );
        assertEquals( List.of( "type", "items", "description", "examples" ), List.copyOf( labels.keySet() ) );
        assertEquals( List.of( "type", "description", "examples" ), List.copyOf( WorkMcpArgs.prop( "string", "d", "x" ).keySet() ) );
        final var schema = WorkMcpArgs.schema( Map.of( "a", Map.of() ), List.of( "a" ) );
        assertEquals( "object", schema.type() );
        assertEquals( List.of( "a" ), schema.required() );
    }
}

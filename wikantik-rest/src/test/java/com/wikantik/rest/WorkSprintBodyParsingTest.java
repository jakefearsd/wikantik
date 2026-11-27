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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What a sprint request body may hold: a malformed one is a 400 naming the field, never an unhandled exception. */
class WorkSprintBodyParsingTest {

    private static JsonObject o( final String json ) {
        return JsonParser.parseString( json ).getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource( { "0,0", "7,7", "2147483647,2147483647", "-1,-1", "3.0,3", "3e0,3", "-2147483648,-2147483648" } )
    void aVersionWithAnExactIntValueIsRead( final String literal, final int expected ) {
        assertEquals( expected, WorkSprintResource.version( o( "{\"version\":" + literal + "}" ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{}", "{\"version\":null}", "{\"version\":\"3\"}", "{\"version\":true}", "{\"version\":[3]}",
            "{\"version\":{}}" } )
    void aMissingOrNonNumericVersionIs400NamingIt( final String body ) {
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> WorkSprintResource.version( o( body ) ) );
        assertEquals( "version", e.field() );
        assertEquals( "version is required.", e.getMessage() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "1.5", "2147483648", "-2147483649", "1e99", "1e-1" } )
    void aVersionThatIsNotAWholeIntIs400( final String literal ) {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> WorkSprintResource.version( o( "{\"version\":" + literal + "}" ) ) );
        assertEquals( "version", e.field() );
        assertEquals( "version must be a whole number.", e.getMessage() );
    }

    @Test
    void anUnknownFieldIsRefusedByName() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> WorkSprintResource.reject( o( "{\"version\":1,\"projectId\":9}" ), Set.of( "version" ) ) );

        assertEquals( "projectId", e.field() );
        assertEquals( "Unknown field: projectId", e.getMessage() );
        WorkSprintResource.reject( o( "{}" ), Set.of() );
        WorkSprintResource.reject( o( "{\"version\":1}" ), Set.of( "version", "name" ) );
    }

    @Test
    void aSprintDraftReadsItsFourFieldsAndTreatsAbsentOnesAsNull() {
        final var all = WorkSprintResource.draft( o( "{\"name\":\"S1\",\"goal\":\"g\",\"startsOn\":\"2026-12-01\",\"endsOn\":\"2026-12-14\"}" ) );
        assertEquals( "S1", all.name() );
        assertEquals( "g", all.goal() );
        assertEquals( java.time.LocalDate.of( 2026, 12, 1 ), all.startsOn() );
        assertEquals( java.time.LocalDate.of( 2026, 12, 14 ), all.endsOn() );

        final var none = WorkSprintResource.draft( o( "{}" ) );
        assertNull( none.name() );
        assertNull( none.goal() );
        assertNull( none.startsOn() );
        assertNull( none.endsOn() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "\"\"", "\"   \"", "null" } )
    void aBlankOrNullDateIsAbsent( final String literal ) {
        assertNull( WorkSprintResource.draft( o( "{\"startsOn\":" + literal + "}" ) ).startsOn() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "\"tomorrow\"", "\"2026-13-01\"", "\"2026-02-30\"", "\"2026/12/01\"", "\"20261201\"",
            "\"2026-12-01T10:00:00Z\"", "\" 2026-12-01\"" } )
    void aDateThatIsNotAnIsoLocalDateIs400NamingTheField( final String literal ) {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> WorkSprintResource.draft( o( "{\"endsOn\":" + literal + "}" ) ) );
        assertEquals( "endsOn", e.field() );
        assertEquals( "endsOn must be a date such as 2026-12-31.", e.getMessage() );
    }

    @Test
    void aDraftNamesWhichFieldWasUnknownBeforeAnythingElse() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> WorkSprintResource.draft( o( "{\"startsOn\":\"nope\",\"bogus\":1}" ) ) );

        assertEquals( "bogus", e.field() );
        assertEquals( List.of( "bogus" ), List.of( e.field() ) );
    }
}

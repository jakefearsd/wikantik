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

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindParamsTest {

    private static Map< String, String > everything() {
        final Map< String, String > m = new HashMap<>();
        m.put( "projects", "PLAT,OPS" );
        m.put( "status", "ready,review" );
        m.put( "level", "task,story" );
        m.put( "type", "bug" );
        m.put( "label", "ui,api" );
        m.put( "priority", "1,3" );
        m.put( "assignee", "me" );
        m.put( "reporter", "Jo Smith" );
        m.put( "queue", "triage" );
        m.put( "product", "wikantik" );
        m.put( "sprint", "active" );
        m.put( "watching", "true" );
        m.put( "q", "login" );
        m.put( "dueBefore", "2026-12-31" );
        m.put( "updatedSince", "2026-11-01" );
        m.put( "sort", "due" );
        m.put( "limit", "25" );
        m.put( "cursor", "abc" );
        return m;
    }

    private static String refusedField( final String name, final String value ) {
        final Map< String, String > m = new HashMap<>();
        m.put( name, value );
        return assertThrows( WorkValidationException.class, () -> FindParams.parse( m ) ).field();
    }

    @Test
    void parsesEveryParameter() {
        final FindFilter f = FindParams.parse( everything() );
        assertEquals( Set.of( "PLAT", "OPS" ), f.projectKeys() );
        assertEquals( Set.of( WorkStatus.READY, WorkStatus.REVIEW ), f.statuses() );
        assertEquals( Set.of( WorkLevel.TASK, WorkLevel.STORY ), f.levels() );
        assertEquals( WorkType.BUG, f.type() );
        assertEquals( Set.of( "ui", "api" ), f.labels() );
        assertEquals( Set.of( 1, 3 ), f.priorities() );
        assertEquals( "me", f.assignee() );
        assertEquals( "Jo Smith", f.reporter() );
        assertEquals( "triage", f.queue() );
        assertEquals( "wikantik", f.productKey() );
        assertEquals( SprintRef.Kind.ACTIVE, f.sprint().kind() );
        assertTrue( f.watching() );
        assertEquals( "login", f.text() );
        assertEquals( LocalDate.of( 2026, 12, 31 ), f.dueBefore() );
        assertEquals( Instant.parse( "2026-11-01T00:00:00Z" ), f.updatedSince() );
        assertEquals( FindFilter.Sort.DUE, f.sort() );
        assertEquals( 25, f.limit() );
        assertEquals( "abc", f.cursor() );
    }

    @Test
    void listsAreCommaSeparatedAndTrimmed() {
        assertEquals( Set.of( WorkStatus.READY, WorkStatus.REVIEW ),
            FindParams.parse( Map.of( "status", "ready, review" ) ).statuses() );
    }

    @Test
    void projectKeysAreUpperCased() {
        assertEquals( Set.of( "PLAT" ), FindParams.parse( Map.of( "projects", "plat" ) ).projectKeys() );
    }

    @Test
    void blankMeansAbsent() {
        final Map< String, String > m = new HashMap<>();
        for ( final String n : FindParams.NAMES ) {
            m.put( n, "  " );
        }
        m.put( "q", null );
        assertEquals( FindFilter.all(), FindParams.parse( m ) );
    }

    @Test
    void eachBadValueNamesItsParameter() {
        assertEquals( "projects", refusedField( "projects", "plat,1" ) );
        assertEquals( "projects", refusedField( "projects", String.join( ",", java.util.Collections.nCopies( 51, "AB" ) ) ) );
        assertEquals( "status", refusedField( "status", "x" ) );
        assertEquals( "level", refusedField( "level", "x" ) );
        assertEquals( "type", refusedField( "type", "x" ) );
        assertEquals( "label", refusedField( "label", "Bad Label!" ) );
        assertEquals( "priority", refusedField( "priority", "5" ) );
        assertEquals( "priority", refusedField( "priority", "x" ) );
        assertEquals( "assignee", refusedField( "assignee", "a".repeat( 101 ) ) );
        assertEquals( "reporter", refusedField( "reporter", "a".repeat( 101 ) ) );
        assertEquals( "queue", refusedField( "queue", "a".repeat( 65 ) ) );
        assertEquals( "product", refusedField( "product", "a".repeat( 65 ) ) );
        assertEquals( "sprint", refusedField( "sprint", "soon" ) );
        assertEquals( "watching", refusedField( "watching", "yes" ) );
        assertEquals( "q", refusedField( "q", "a".repeat( 201 ) ) );
        assertEquals( "dueBefore", refusedField( "dueBefore", "tomorrow" ) );
        assertEquals( "updatedSince", refusedField( "updatedSince", "yesterday" ) );
        assertEquals( "sort", refusedField( "sort", "x" ) );
        assertEquals( "limit", refusedField( "limit", "-1" ) );
        assertEquals( "limit", refusedField( "limit", "x" ) );
        assertEquals( "cursor", refusedField( "cursor", "a".repeat( 201 ) ) );
    }

    @Test
    void anUnknownParameterIsRefusedByName() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> FindParams.parse( Map.of( "colour", "red" ) ) );
        assertEquals( "colour", e.field() );
        assertEquals( "Unknown filter field.", e.getMessage() );
    }

    @Test
    void updatedSinceTakesADateOrAnInstant() {
        assertEquals( Instant.parse( "2026-11-01T00:00:00Z" ),
            FindParams.parse( Map.of( "updatedSince", "2026-11-01" ) ).updatedSince() );
        assertEquals( Instant.parse( "2026-11-01T09:30:00Z" ),
            FindParams.parse( Map.of( "updatedSince", "2026-11-01T09:30:00Z" ) ).updatedSince() );
    }

    @Test
    void toParamsRoundTripsEveryField() {
        final FindFilter f = FindParams.parse( everything() );
        assertEquals( f.page( null, 0 ), FindParams.parse( FindParams.toParams( f ) ) );
        final Map< String, String > m = FindParams.toParams( f );
        assertEquals( "OPS,PLAT", m.get( "projects" ) );
        assertEquals( "review,ready".length(), m.get( "status" ).length() );
    }

    @Test
    void toParamsLeavesOutEmptiesLimitAndCursor() {
        assertTrue( FindParams.toParams( FindFilter.all() ).isEmpty() );
        final Map< String, String > m = FindParams.toParams( FindParams.parse( everything() ) );
        assertTrue( !m.containsKey( "limit" ) && !m.containsKey( "cursor" ) );
        assertNull( FindParams.toParams( FindFilter.all().page( "c", 5 ) ).get( "cursor" ) );
    }

    @Test
    void theFindOnlyNamesAreKnownNames() {
        assertTrue( FindParams.NAMES.containsAll( FindParams.FIND_ONLY ) );
    }

    @Test
    void everyDateFieldIsBoundedToTheSupportedYears() {
        for ( final String bad : new String[] { "+999999999-12-31", "+10000-01-01", "1969-12-31" } ) {
            assertEquals( "dueBefore", refusedField( "dueBefore", bad ), bad );
        }
        for ( final String bad : new String[] { "+999999999-12-31T00:00:00Z", "+10000-01-01T00:00:00Z",
            "1969-12-31T23:59:59Z", "1969-12-31", "10000-01-01" } ) {
            assertEquals( "updatedSince", refusedField( "updatedSince", bad ), bad );
        }
        assertEquals( LocalDate.of( 9999, 12, 31 ), FindParams.parse( Map.of( "dueBefore", "9999-12-31" ) ).dueBefore() );
        assertEquals( Instant.parse( "9999-12-31T23:59:59Z" ),
            FindParams.parse( Map.of( "updatedSince", "9999-12-31T23:59:59Z" ) ).updatedSince() );
        assertEquals( Instant.EPOCH, FindParams.parse( Map.of( "updatedSince", "1970-01-01" ) ).updatedSince() );
    }
}

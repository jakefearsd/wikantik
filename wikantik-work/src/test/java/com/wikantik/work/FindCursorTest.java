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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindCursorTest {

    private static Item item( final Instant created, final Instant updated, final int priority, final LocalDate due ) {
        return new Item( 42, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.READY,
            priority, 1.0, due, null, "alice", null, null, null, 1, created, updated, null, null, null, null, null,
            null, null, null, null, null );
    }

    private static String b64( final String raw ) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString( raw.getBytes( StandardCharsets.UTF_8 ) );
    }

    private static void refused( final String cursor, final FindFilter.Sort sort ) {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> FindCursor.decode( cursor, sort ) );
        assertEquals( "cursor", e.field() );
    }

    @Test
    void roundTripsForEverySort() {
        final Instant micro = Instant.parse( "2026-11-21T10:00:00.123456Z" );
        final Item i = item( micro, micro, 3, LocalDate.of( 2026, 12, 31 ) );
        for ( final FindFilter.Sort sort : FindFilter.Sort.values() ) {
            final FindCursor c = FindCursor.after( i, sort );
            assertEquals( c, FindCursor.decode( c.encode(), sort ) );
            assertEquals( 42, c.id() );
        }
        assertEquals( java.sql.Timestamp.from( micro ), FindCursor.after( i, FindFilter.Sort.UPDATED ).sqlValue() );
        assertEquals( 3, FindCursor.after( i, FindFilter.Sort.PRIORITY ).sqlValue() );
        assertEquals( java.sql.Date.valueOf( "2026-12-31" ), FindCursor.after( i, FindFilter.Sort.DUE ).sqlValue() );
    }

    @Test
    void aCursorOfAnotherSortIsRefused() {
        final Item i = item( Instant.EPOCH, Instant.EPOCH, 2, null );
        refused( FindCursor.after( i, FindFilter.Sort.UPDATED ).encode(), FindFilter.Sort.PRIORITY );
    }

    @Test
    void garbageIsRefused() {
        refused( "%%%", FindFilter.Sort.UPDATED );
        refused( b64( "updated|x" ), FindFilter.Sort.UPDATED );
        refused( b64( "updated|2026-11-21T10:00:00Z|abc" ), FindFilter.Sort.UPDATED );
        refused( b64( "updated|not-an-instant|5" ), FindFilter.Sort.UPDATED );
        refused( b64( "updated|2026-11-21T10:00:00Z|-5" ), FindFilter.Sort.UPDATED );
        refused( b64( "priority|9|5" ), FindFilter.Sort.PRIORITY );
        refused( b64( "updated|2026-11-21T10:00:00Z|5|extra" ), FindFilter.Sort.UPDATED );
        refused( "", FindFilter.Sort.UPDATED );
    }

    @Test
    void noDueDateSortsLast() {
        final FindCursor c = FindCursor.after( item( Instant.EPOCH, Instant.EPOCH, 2, null ), FindFilter.Sort.DUE );
        assertEquals( "9999-12-31", c.value() );
        assertEquals( FindCursor.NO_DUE, LocalDate.parse( c.value() ) );
    }

    @Test
    void anOverflowingOrOutOfRangeDateIsRefusedNotAServerError() {
        refused( b64( "updated|+999999999-12-31T00:00:00Z|1" ), FindFilter.Sort.UPDATED );
        refused( b64( "created|+999999999-12-31T00:00:00Z|1" ), FindFilter.Sort.CREATED );
        refused( b64( "due|+999999999-12-31|1" ), FindFilter.Sort.DUE );
        refused( b64( "due|+10000-01-01|1" ), FindFilter.Sort.DUE );
        refused( b64( "due|1969-12-31|1" ), FindFilter.Sort.DUE );
        refused( b64( "updated|1969-12-31T23:59:59Z|1" ), FindFilter.Sort.UPDATED );
        refused( b64( "updated|+10000-01-01T00:00:00Z|1" ), FindFilter.Sort.UPDATED );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> FindCursor.decode( b64( "updated|not-an-instant|1" ), FindFilter.Sort.UPDATED ) );
        assertTrue( e.getCause() != null, "a parse failure keeps its cause" );
    }

    @Test
    void aFarFutureDateInRangeStillWorks() {
        assertEquals( java.sql.Timestamp.from( Instant.parse( "9999-12-31T23:59:59Z" ) ),
            FindCursor.decode( b64( "updated|9999-12-31T23:59:59Z|1" ), FindFilter.Sort.UPDATED ).sqlValue() );
        assertEquals( java.sql.Date.valueOf( "9999-12-31" ),
            FindCursor.decode( b64( "due|9999-12-31|1" ), FindFilter.Sort.DUE ).sqlValue() );
        assertEquals( java.sql.Date.valueOf( "1970-01-01" ),
            FindCursor.decode( b64( "due|1970-01-01|1" ), FindFilter.Sort.DUE ).sqlValue() );
    }
}

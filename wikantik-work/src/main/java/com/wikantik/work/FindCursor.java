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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Base64;

/**
 * The marker of the last row of a page of a find: the value the page is sorted on and the item id (the tie-break).
 * It is not signed; it is strictly validated instead. It only ever feeds bind parameters of a query that applies
 * the same visibility rules as the first page, so a forged one can move the page boundary but never reach a row
 * the caller could not otherwise read. A cursor of another sort, or that does not parse, is refused, and so is
 * one whose date lies outside the years {@value FindDates#MIN_YEAR} to {@value FindDates#MAX_YEAR} (all as field
 * {@code cursor}).
 *
 * <p>Rows that change between two pages: {@code updated} and {@code created} sort newest first, so an item updated
 * meanwhile jumps ahead of the boundary; no item is shown twice, but an updated item not yet reached is missed by
 * the pages that follow. {@code priority} and {@code due} sort on mutable values: an item changed to sort after the
 * boundary may be shown a second time, and one changed to sort before it is missed. Items that did not change are
 * never lost or repeated.
 */
record FindCursor( FindFilter.Sort sort, String value, long id ) {

    /** The sort value of an item with no due date: it sorts after every real date. */
    static final LocalDate NO_DUE = LocalDate.of( 9999, 12, 31 );

    private static final String REFUSAL = "This page marker does not belong to this search.";
    private static final int PARTS = 3;

    /** The cursor that continues after {@code last} in the given order. */
    static FindCursor after( final Item last, final FindFilter.Sort sort ) {
        final String value = switch ( sort ) {
            case UPDATED -> last.updatedAt().toString();
            case CREATED -> last.createdAt().toString();
            case PRIORITY -> Integer.toString( last.priority() );
            case DUE -> ( last.dueOn() == null ? NO_DUE : last.dueOn() ).toString();
        };
        return new FindCursor( sort, value, last.id() );
    }

    /** URL-safe Base64 without padding of {@code sort|value|id}. */
    String encode() {
        final String raw = sort.wire() + "|" + value + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString( raw.getBytes( StandardCharsets.UTF_8 ) );
    }

    /**
     * Reads a cursor the caller sent back.
     *
     * @throws WorkValidationException on field {@code cursor} when it is not a cursor of this sort
     */
    static FindCursor decode( final String cursor, final FindFilter.Sort sort ) {
        final String raw = unwrap( cursor );
        final String[] parts = raw.split( "\\|", -1 );
        if ( parts.length != PARTS || !sort.wire().equals( parts[ 0 ] ) ) {
            throw refusal( null );
        }
        final FindCursor c = new FindCursor( sort, parts[ 1 ], parseId( parts[ 2 ] ) );
        c.sqlValue();
        return c;
    }

    /** The bind value for the sorted column: a Timestamp, an Integer or a java.sql.Date. */
    Object sqlValue() {
        try {
            return switch ( sort ) {
                case UPDATED, CREATED -> Timestamp.from( FindDates.instant( "cursor", value, REFUSAL ) );
                case PRIORITY -> priority( value );
                case DUE -> java.sql.Date.valueOf( FindDates.date( "cursor", value, REFUSAL ) );
            };
        } catch ( NumberFormatException e ) {
            throw refusal( e );
        }
    }

    private static Integer priority( final String v ) {
        final int p = Integer.parseInt( v );
        if ( p < 1 || p > 4 ) {
            throw new NumberFormatException( "priority out of range" );
        }
        return p;
    }

    private static String unwrap( final String cursor ) {
        if ( cursor == null || cursor.isEmpty() ) {
            throw refusal( null );
        }
        try {
            return new String( Base64.getUrlDecoder().decode( cursor ), StandardCharsets.UTF_8 );
        } catch ( IllegalArgumentException e ) {
            throw refusal( e );
        }
    }

    private static long parseId( final String v ) {
        try {
            final long id = Long.parseLong( v );
            if ( id < 0 ) {
                throw refusal( null );
            }
            return id;
        } catch ( NumberFormatException e ) {
            throw refusal( e );
        }
    }

    private static WorkValidationException refusal( final Throwable cause ) {
        final WorkValidationException e = new WorkValidationException( "cursor", REFUSAL );
        if ( cause != null ) {
            e.initCause( cause );
        }
        return e;
    }
}

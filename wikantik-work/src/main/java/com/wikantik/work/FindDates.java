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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Parses the dates a find accepts and bounds them to the years {@value #MIN_YEAR} to {@value #MAX_YEAR}, so a value
 * that parses but cannot be a bind parameter (an overflowing instant, a year that wraps in {@code java.sql.Date})
 * is a validation error at parse time, never a 500 when the query runs. Used for {@code dueBefore},
 * {@code updatedSince} and the date inside a cursor.
 */
final class FindDates {

    static final int MIN_YEAR = 1970;
    static final int MAX_YEAR = 9999;

    private static final LocalDate FIRST = LocalDate.of( MIN_YEAR, 1, 1 );
    private static final LocalDate LAST = LocalDate.of( MAX_YEAR, 12, 31 );
    private static final Instant FIRST_INSTANT = FIRST.atStartOfDay( ZoneOffset.UTC ).toInstant();
    private static final Instant END_INSTANT = LAST.plusDays( 1 ).atStartOfDay( ZoneOffset.UTC ).toInstant();

    private FindDates() {
    }

    /** @throws WorkValidationException on {@code field} (cause attached) when {@code v} is not a date in range */
    static LocalDate date( final String field, final String v, final String message ) {
        final LocalDate d;
        try {
            d = LocalDate.parse( v );
        } catch ( DateTimeException | ArithmeticException e ) {
            throw invalid( field, message, e );
        }
        if ( d.isBefore( FIRST ) || d.isAfter( LAST ) ) {
            throw invalid( field, message, null );
        }
        return d;
    }

    /** @throws WorkValidationException on {@code field} (cause attached) when {@code v} is not an instant in range */
    static Instant instant( final String field, final String v, final String message ) {
        final Instant t;
        try {
            t = Instant.parse( v );
        } catch ( DateTimeException | ArithmeticException e ) {
            throw invalid( field, message, e );
        }
        if ( t.isBefore( FIRST_INSTANT ) || !t.isBefore( END_INSTANT ) ) {
            throw invalid( field, message, null );
        }
        return t;
    }

    static WorkValidationException invalid( final String field, final String message, final Throwable cause ) {
        final WorkValidationException e = new WorkValidationException( field, message );
        if ( cause != null ) {
            e.initCause( cause );
        }
        return e;
    }
}

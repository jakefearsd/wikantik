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
package com.wikantik.auth.acl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard has to tell a linear scan from a per-span (quadratic) one, or the tests built on it prove nothing. */
class LinearTimeTest {

    private static volatile int sink;

    /** A linear scan: one pass over the text. */
    private static void linear( final String text ) {
        int count = 0;
        for ( int i = 0; i < text.length(); i++ ) {
            count += text.charAt( i ) == '{' ? 1 : 0;
        }
        sink = count;
    }

    /** A per-span scan: every opener looks to the end of the text for its closer. */
    private static void perSpan( final String text ) {
        int count = 0;
        for ( int at = text.indexOf( "[{" ); at >= 0; at = text.indexOf( "[{", at + 2 ) ) {
            count += text.indexOf( "}]", at ) < 0 ? 0 : text.lastIndexOf( '{' ) - at > 0 ? 1 : 0;
            for ( int i = at; i < text.length(); i++ ) {
                count += text.charAt( i ) == 'x' ? 1 : 0;
            }
        }
        sink = count;
    }

    @Test
    void aLinearScanIsWithinTheBound() {
        assertTrue( LinearTime.ratio( n -> "[{allowa".repeat( n ), 20_000, LinearTimeTest::linear ) < LinearTime.bound() );
        LinearTime.assertLinear( "linear", n -> "[{allowa".repeat( n ), 20_000, LinearTimeTest::linear );
    }

    @Test
    void aPerSpanScanIsOverTheBound() {
        final double ratio = LinearTime.ratio( n -> "[{allowa".repeat( n ) + "}]", 2_000, LinearTimeTest::perSpan );
        assertFalse( ratio < LinearTime.bound(), "the quadratic scan's ratio was " + ratio );
        assertThrows( AssertionError.class,
                () -> LinearTime.assertLinear( "per-span", n -> "[{allowa".repeat( n ) + "}]", 2_000, LinearTimeTest::perSpan ) );
    }
}

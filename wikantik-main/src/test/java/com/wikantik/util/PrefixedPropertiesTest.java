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
package com.wikantik.util;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior of {@link PrefixedProperties}, the prefixed typed-config reader extracted from
 * the previously-duplicated per-config parsing helpers. Locks in the absent/blank/malformed
 * fallback and trimming semantics those helpers had.
 */
class PrefixedPropertiesTest {

    private static final String PREFIX = "app.thing.";

    private static PrefixedProperties reader( final String... kv ) {
        final Properties p = new Properties();
        for( int i = 0; i < kv.length; i += 2 ) {
            p.setProperty( kv[ i ], kv[ i + 1 ] );
        }
        return new PrefixedProperties( p, PREFIX );
    }

    @Test
    void nullPropertiesAlwaysReturnsDefaults() {
        final PrefixedProperties r = new PrefixedProperties( null, PREFIX );
        assertEquals( "d", r.getString( "s", "d" ) );
        assertEquals( 7L, r.getLong( "l", 7L ) );
        assertEquals( 3, r.getInt( "i", 3 ) );
        assertEquals( 1.5, r.getDouble( "dbl", 1.5 ) );
        assertTrue( r.getBoolean( "b", true ) );
    }

    @Test
    void absentOrBlankKeyFallsBackToDefault() {
        final PrefixedProperties r = reader( PREFIX + "blank", "   " );
        assertEquals( "def", r.getString( "missing", "def" ) );
        assertEquals( "def", r.getString( "blank", "def" ) );
        assertEquals( 99L, r.getLong( "missing", 99L ) );
    }

    @Test
    void stringIsTrimmed() {
        assertEquals( "hello", reader( PREFIX + "s", "  hello  " ).getString( "s", "x" ) );
    }

    @Test
    void validNumbersParse() {
        final PrefixedProperties r = reader(
            PREFIX + "l", " 42 ", PREFIX + "i", "5", PREFIX + "dbl", "0.25" );
        assertEquals( 42L, r.getLong( "l", 0L ) );
        assertEquals( 5, r.getInt( "i", 0 ) );
        assertEquals( 0.25, r.getDouble( "dbl", 0.0 ) );
    }

    @Test
    void malformedNumberCoercesToDefaultInsteadOfThrowing() {
        final PrefixedProperties r = reader(
            PREFIX + "l", "notanumber", PREFIX + "dbl", "NaNish" );
        assertEquals( 11L, r.getLong( "l", 11L ) );
        assertEquals( 22, r.getInt( "l", 22 ) );      // getInt delegates through getLong
        assertEquals( 3.3, r.getDouble( "dbl", 3.3 ) );
    }

    @Test
    void booleanAcceptsTrueOneYesAndFalseZeroNo() {
        assertTrue( reader( PREFIX + "b", "TRUE" ).getBoolean( "b", false ) );
        assertTrue( reader( PREFIX + "b", "1" ).getBoolean( "b", false ) );
        assertTrue( reader( PREFIX + "b", "Yes" ).getBoolean( "b", false ) );
        assertFalse( reader( PREFIX + "b", "false" ).getBoolean( "b", true ) );
        assertFalse( reader( PREFIX + "b", "0" ).getBoolean( "b", true ) );
        assertFalse( reader( PREFIX + "b", "no" ).getBoolean( "b", true ) );
    }

    @Test
    void malformedBooleanCoercesToDefault() {
        assertTrue( reader( PREFIX + "b", "maybe" ).getBoolean( "b", true ) );
        assertFalse( reader( PREFIX + "b", "maybe" ).getBoolean( "b", false ) );
    }
}

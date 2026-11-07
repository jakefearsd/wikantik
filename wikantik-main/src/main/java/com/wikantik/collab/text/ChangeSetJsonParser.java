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
package com.wikantik.collab.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/** The validating reader behind {@link ChangeSetJson#fromJson}; see there for what it refuses. */
final class ChangeSetJsonParser {

    private ChangeSetJsonParser() {
    }

    static ChangeSet parse( final JsonArray json ) {
        if ( json == null ) {
            throw new IllegalArgumentException( "Invalid change set: expected a JSON array, got null" );
        }
        final SectionBuilder b = new SectionBuilder();
        long length = 0;
        long newLength = 0;
        for ( int i = 0; i < json.size(); i++ ) {
            final int[] lengths = parsePart( b, json.get( i ), i );
            length += lengths[ 0 ];
            newLength += lengths[ 1 ];
            if ( length > Integer.MAX_VALUE || newLength > Integer.MAX_VALUE ) {
                throw new IllegalArgumentException( "Invalid change set: document too long at element " + i );
            }
        }
        return b.build();
    }

    /** Pushes element {@code index} onto {@code b}; returns its {@code {oldLength, newLength}}. */
    private static int[] parsePart( final SectionBuilder b, final JsonElement part, final int index ) {
        if ( isNumber( part ) ) {
            return parseKept( b, part, index );
        }
        if ( part == null || !part.isJsonArray() ) {
            throw new IllegalArgumentException( "Invalid change set: element " + index
                    + " must be a length or a [deleted, ...lines] array, got " + part );
        }
        return parseChange( b, part.getAsJsonArray(), index );
    }

    private static int[] parseKept( final SectionBuilder b, final JsonElement part, final int index ) {
        final int len = length( part, index );
        if ( len == 0 ) {
            throw zeroLength( index );
        }
        b.push( len, ChangeSet.KEPT, null );
        return new int[] { len, len };
    }

    private static int[] parseChange( final SectionBuilder b, final JsonArray change, final int index ) {
        if ( change.isEmpty() || !isNumber( change.get( 0 ) ) ) {
            throw new IllegalArgumentException( "Invalid change set: element " + index
                    + " must start with the deleted length, got " + change );
        }
        final int len = length( change.get( 0 ), index );
        final String text = change.size() == 1 ? "" : lines( change, index );
        if ( len == 0 && text.isEmpty() ) {
            throw zeroLength( index );
        }
        b.push( len, text.length(), change.size() == 1 ? null : text );
        return new int[] { len, text.length() };
    }

    private static IllegalArgumentException zeroLength( final int index ) {
        return new IllegalArgumentException( "Invalid change set: zero-length section at element " + index
                + " (neither keeps, deletes nor inserts anything)" );
    }

    private static boolean isNumber( final JsonElement e ) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static int length( final JsonElement e, final int index ) {
        final JsonPrimitive p = e.getAsJsonPrimitive();
        final BigDecimal value = p.getAsBigDecimal();
        if ( value.signum() < 0 ) {
            throw new IllegalArgumentException( "Invalid change set: negative length " + p + " at element " + index );
        }
        if ( value.stripTrailingZeros().scale() > 0 ) {
            throw new IllegalArgumentException( "Invalid change set: length " + p + " at element " + index
                    + " is not an integer" );
        }
        if ( value.compareTo( BigDecimal.valueOf( Integer.MAX_VALUE ) ) > 0 ) {
            throw new IllegalArgumentException( "Invalid change set: document too long, length " + p + " at element "
                    + index );
        }
        return value.intValue();
    }

    private static String lines( final JsonArray change, final int index ) {
        final StringBuilder text = new StringBuilder();
        for ( int j = 1; j < change.size(); j++ ) {
            final JsonElement line = change.get( j );
            if ( line == null || !line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString() ) {
                throw new IllegalArgumentException( "Invalid change set: element " + index + " line " + ( j - 1 )
                        + " must be a string, got " + line );
            }
            final String s = line.getAsString();
            if ( s.indexOf( '\n' ) >= 0 || s.indexOf( '\r' ) >= 0 ) {
                throw new IllegalArgumentException( "Invalid change set: element " + index + " line " + ( j - 1 )
                        + " holds a line break" );
            }
            if ( j > 1 ) {
                text.append( '\n' );
            }
            text.append( s );
        }
        return text.toString();
    }
}

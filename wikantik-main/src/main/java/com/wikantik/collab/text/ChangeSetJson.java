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

/**
 * {@link ChangeSet} to and from CodeMirror's JSON form ({@code ChangeSet.toJSON()}): a number is the length of a
 * kept span; an array {@code [deletedLength, ...insertedLines]} is a change, its lines joined with {@code \n}.
 *
 * <p>{@link #fromJson} is stricter than CodeMirror's {@code fromJSON}, because its input arrives from the network:
 * lengths must be non-negative integers, the document lengths must fit a {@code String}, and an inserted line may not
 * hold a line break of its own (the line array is the only line break), which keeps
 * {@code toJson(fromJson(x)).equals(x)} for every accepted {@code x} with non-empty insertions.</p>
 */
public final class ChangeSetJson {

    private ChangeSetJson() {
    }

    /**
     * Parses CodeMirror's JSON form.
     *
     * @throws IllegalArgumentException naming the defect when {@code json} is not a well-formed change set
     */
    public static ChangeSet fromJson( final JsonArray json ) {
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
            final int len = length( part, index );
            b.push( len, ChangeSet.KEPT, null );
            return new int[] { len, len };
        }
        if ( part == null || !part.isJsonArray() ) {
            throw new IllegalArgumentException( "Invalid change set: element " + index
                    + " must be a length or a [deleted, ...lines] array, got " + part );
        }
        final JsonArray change = part.getAsJsonArray();
        if ( change.isEmpty() || !isNumber( change.get( 0 ) ) ) {
            throw new IllegalArgumentException( "Invalid change set: element " + index
                    + " must start with the deleted length, got " + part );
        }
        final int len = length( change.get( 0 ), index );
        if ( change.size() == 1 ) {
            b.push( len, 0, null );
            return new int[] { len, 0 };
        }
        final String text = lines( change, index );
        b.push( len, text.length(), text );
        return new int[] { len, text.length() };
    }

    /** Serialises {@code cs} exactly as CodeMirror's {@code ChangeSet.toJSON()} does. */
    public static JsonArray toJson( final ChangeSet cs ) {
        final JsonArray parts = new JsonArray();
        for ( int i = 0; i < cs.sectionCount(); i++ ) {
            final int len = cs.sectionLength( i );
            final int ins = cs.sectionInsert( i );
            if ( ins < 0 ) {
                parts.add( len );
            } else {
                final JsonArray change = new JsonArray();
                change.add( len );
                if ( ins > 0 ) {
                    for ( final String line : cs.insertedAt( i ).split( "\n", -1 ) ) {
                        change.add( line );
                    }
                }
                parts.add( change );
            }
        }
        return parts;
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

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Accumulates a {@link ChangeSet}'s sections and inserted text with CodeMirror's {@code addSection} and
 * {@code addInsert} merge rules, which is what gives a computed set the same shape as the library's.
 */
final class SectionBuilder {

    private int[] sections = new int[ 8 ];
    private int size;
    private final List< String > inserted = new ArrayList<>();

    /** The number of ints held so far ({@code sections.length} in CodeMirror). */
    int size() {
        return size;
    }

    /** CodeMirror's {@code addSection}. */
    void addSection( final int len, final int ins, final boolean forceJoin ) {
        if ( len == 0 && ins <= 0 ) {
            return;
        }
        final int last = size - 2;
        if ( last >= 0 && ins <= 0 && ins == sections[ last + 1 ] ) {
            sections[ last ] += len;
        } else if ( last >= 0 && len == 0 && sections[ last ] == 0 ) {
            sections[ last + 1 ] += ins;
        } else if ( forceJoin ) {
            sections[ last ] += len;
            sections[ last + 1 ] += ins;
        } else {
            append( len, ins );
        }
    }

    /** CodeMirror's {@code addInsert}: text for the last section, appended to what it already has. */
    void addInsert( final String value ) {
        if ( value.isEmpty() ) {
            return;
        }
        final int index = ( size - 2 ) >> 1;
        if ( index < inserted.size() ) {
            final int lastIndex = inserted.size() - 1;
            inserted.set( lastIndex, inserted.get( lastIndex ) + value );
        } else {
            padTo( index );
            inserted.add( value );
        }
    }

    /**
     * Appends a section exactly as given, without merging: {@code ChangeSet.fromJSON}'s {@code sections.push}.
     * {@code text}, when not null, is that section's inserted text.
     */
    void push( final int len, final int ins, final String text ) {
        final int index = size >> 1;
        append( len, ins );
        if ( text != null ) {
            padTo( index );
            inserted.add( text );
        }
    }

    ChangeSet build() {
        return new ChangeSet( Arrays.copyOf( sections, size ), inserted.toArray( new String[ 0 ] ) );
    }

    private void append( final int len, final int ins ) {
        if ( size + 2 > sections.length ) {
            sections = Arrays.copyOf( sections, sections.length * 2 );
        }
        sections[ size++ ] = len;
        sections[ size++ ] = ins;
    }

    private void padTo( final int index ) {
        while ( inserted.size() < index ) {
            inserted.add( "" );
        }
    }
}

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

/**
 * CodeMirror's {@code SectionIter}: a cursor over a set's sections that can stop part-way through one. Its fields are
 * read directly by {@link MapSet} and {@link ComposeSets}, as CodeMirror's are.
 */
final class SectionIter {

    /** The {@code ins} once the sections are exhausted. */
    static final int DONE = -2;

    private final ChangeSet set;
    /** Index of the int after the current section. */
    int i;
    /** Remaining old-document length of the current section. */
    int len;
    /** {@link ChangeSet#KEPT}, {@link #DONE}, or the remaining inserted length of the current section. */
    int ins;
    /** How far into the current section the cursor is. */
    int off;

    SectionIter( final ChangeSet set ) {
        this.set = set;
        next();
    }

    void next() {
        final int[] sections = set.rawSections();
        if ( i < sections.length ) {
            len = sections[ i++ ];
            ins = sections[ i++ ];
        } else {
            len = 0;
            ins = DONE;
        }
        off = 0;
    }

    boolean done() {
        return ins == DONE;
    }

    int len2() {
        return ins < 0 ? len : ins;
    }

    String text() {
        return set.insertedAt( ( i - 2 ) >> 1 );
    }

    String textBit( final int length ) {
        return length == 0 ? "" : text().substring( off, off + length );
    }

    void forward( final int length ) {
        if ( length == len ) {
            next();
        } else {
            len -= length;
            off += length;
        }
    }

    void forward2( final int length ) {
        if ( ins == ChangeSet.KEPT ) {
            forward( length );
        } else if ( length == ins ) {
            next();
        } else {
            ins -= length;
            off += length;
        }
    }
}

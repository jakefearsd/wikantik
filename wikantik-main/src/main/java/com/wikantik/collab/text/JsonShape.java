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
 * What one element of a change set's JSON form does: keeps a span, purely deletes, or inserts (with or without
 * deleting). CodeMirror's {@code toJSON} merges adjacent kept spans and adjacent pure deletions into one section, so a
 * neighbouring pair of either is not its output; {@link ChangeSetJsonParser} refuses it (it would also break the JSON
 * round trip).
 */
enum JsonShape {
    KEPT, DELETION, CHANGE;

    static JsonShape of( final boolean keptSpan, final int insertedLength ) {
        if ( keptSpan ) {
            return KEPT;
        }
        return insertedLength == 0 ? DELETION : CHANGE;
    }

    /** @throws IllegalArgumentException naming element {@code index} when it may not follow {@code previous} */
    void requireCanonicalAfter( final JsonShape previous, final int index ) {
        if ( this == CHANGE || this != previous ) {
            return;
        }
        final String what = this == KEPT ? "kept span" : "pure deletion";
        throw new IllegalArgumentException( "Invalid change set: element " + index + " is a " + what + " adjacent to the "
                + what + " at element " + ( index - 1 ) + " (not canonical: CodeMirror merges them)" );
    }
}

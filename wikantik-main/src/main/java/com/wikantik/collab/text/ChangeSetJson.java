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

/**
 * {@link ChangeSet} to and from CodeMirror's JSON form ({@code ChangeSet.toJSON()}): a number is the length of a
 * kept span; an array {@code [deletedLength, ...insertedLines]} is a change, its lines joined with {@code \n}.
 *
 * <p>{@link #fromJson} is stricter than CodeMirror's {@code fromJSON}, because its input arrives from the network:
 * lengths must be non-negative integers, the document lengths must fit a {@code String}, no section may be
 * zero-length (a {@code 0} kept span, or a change that deletes nothing and inserts nothing — CodeMirror's
 * {@code toJSON} never emits one, and its compose/map mishandle them), and an inserted line may not hold a line break
 * of its own (the line array is the only line break), which keeps {@code toJson(fromJson(x)).equals(x)} for every
 * accepted {@code x} with non-empty insertions.</p>
 */
public final class ChangeSetJson {

    private ChangeSetJson() {
    }

    /**
     * Parses CodeMirror's JSON form, as produced by an editor with CodeMirror's default line separator. A custom
     * CodeMirror {@code lineSeparator} is unsupported: {@code \n} or {@code \r} inside an inserted line is refused,
     * since the collaboration layer's text is LF-only and the line array is the only line break.
     *
     * @throws IllegalArgumentException naming the defect when {@code json} is not a well-formed change set
     */
    public static ChangeSet fromJson( final JsonArray json ) {
        return ChangeSetJsonParser.parse( json );
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
}

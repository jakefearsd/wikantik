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
package com.wikantik.collab.model;

/**
 * One author's contribution to a draft since the last publish (Phase 3 attribution): the range of collab versions of
 * their accepted text updates and the range of meta sequence numbers of their accepted meta ops. Versions and sequence
 * numbers are 1-based, so {@link #NONE} (0) in both bounds of a kind means "no contribution of this kind"; at least one
 * kind is set.
 */
public record Contribution( int firstVersion, int lastVersion, long firstMetaSeq, long lastMetaSeq ) {

    /** Both bounds of a kind at {@code NONE}: no contribution of that kind. */
    public static final int NONE = 0;

    public Contribution {
        ModelChecks.nonNegative( firstVersion, "firstVersion" );
        ModelChecks.nonNegative( lastVersion, "lastVersion" );
        ModelChecks.nonNegative( firstMetaSeq, "firstMetaSeq" );
        ModelChecks.nonNegative( lastMetaSeq, "lastMetaSeq" );
        final boolean text = range( firstVersion, lastVersion, "version" );
        final boolean meta = range( firstMetaSeq, lastMetaSeq, "meta seq" );
        if( !text && !meta ) {
            throw new IllegalArgumentException( "a contribution needs a version range or a meta seq range" );
        }
    }

    /** True when the kind is set; rejects a half-set kind and {@code first > last}. */
    private static boolean range( final long first, final long last, final String kind ) {
        if( ( first == NONE ) != ( last == NONE ) ) {
            throw new IllegalArgumentException( "both " + kind + " bounds must be set or both unset: [" + first + ", "
                + last + "]" );
        }
        if( first > last ) {
            throw new IllegalArgumentException( "first " + kind + " " + first + " is after the last " + last );
        }
        return first != NONE;
    }
}

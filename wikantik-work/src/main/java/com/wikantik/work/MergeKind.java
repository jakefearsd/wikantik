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

/** How a change was merged: after an approval of the merged commit, by a person without one, or opened and merged in one step. */
public enum MergeKind {
    REVIEWED("reviewed"),
    UNREVIEWED("unreviewed"),
    DIRECT("direct");

    private final String wire;

    MergeKind( final String wire ) {
        this.wire = wire;
    }

    /** The lowercase value as stored in the database. */
    public String wire() {
        return wire;
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static MergeKind fromWire( final String value ) {
        for ( final MergeKind candidate : values() ) {
            if ( candidate.wire.equals( value ) ) {
                return candidate;
            }
        }
        throw new WorkValidationException( "mergeKind", "Unknown mergeKind: " + value );
    }
}

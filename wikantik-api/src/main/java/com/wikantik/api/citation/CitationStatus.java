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
package com.wikantik.api.citation;

/** Graded staleness of a citation. Wire/db value is {@link #wire()}. */
public enum CitationStatus {
    CURRENT( "current" ),
    STALE( "stale" ),
    TARGET_MISSING( "target_missing" );

    private final String wire;

    CitationStatus( final String wire ) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static CitationStatus fromWire( final String s ) {
        for ( final CitationStatus v : values() ) {
            if ( v.wire.equals( s ) ) {
                return v;
            }
        }
        throw new IllegalArgumentException( "unknown citation status: " + s );
    }
}

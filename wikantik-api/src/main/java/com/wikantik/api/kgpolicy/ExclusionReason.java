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
package com.wikantik.api.kgpolicy;

import java.util.Locale;
import java.util.Optional;

/**
 * Why a page is in {@code kg_excluded_pages}. Strongest first — when more
 * than one applies, the strongest is recorded.
 */
public enum ExclusionReason {
    SYSTEM_PAGE( 30 ),
    PAGE_OVERRIDE( 20 ),
    CLUSTER_POLICY( 10 );

    private final int strength;
    ExclusionReason( final int strength ) { this.strength = strength; }

    public int strength() { return strength; }
    public String wire() { return name().toLowerCase( Locale.ROOT ); }

    public static Optional< ExclusionReason > fromWire( final String s ) {
        if ( s == null ) return Optional.empty();
        try { return Optional.of( ExclusionReason.valueOf( s.toUpperCase( Locale.ROOT ) ) ); }
        catch ( final IllegalArgumentException e ) { return Optional.empty(); }
    }

    /** Returns the strongest of two reasons (used by reason-precedence logic). */
    public static ExclusionReason strongest( final ExclusionReason a, final ExclusionReason b ) {
        if ( a == null ) return b;
        if ( b == null ) return a;
        return a.strength() >= b.strength() ? a : b;
    }
}

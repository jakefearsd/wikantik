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
package com.wikantik.api.structure;

import java.util.Locale;
import java.util.Optional;

/**
 * How much an agent should trust a wiki page. Computed by the
 * {@code ConfidenceComputer} from {@code verified_at}, the verifying author,
 * and the trusted-authors registry — but authors can also pin an explicit
 * value in frontmatter ({@code confidence: stale} is a useful early warning).
 */
public enum Confidence {

    AUTHORITATIVE ( "authoritative" ),
    PROVISIONAL   ( "provisional" ),
    STALE         ( "stale" );

    private final String wireName;

    Confidence( final String wireName ) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional< Confidence > fromWire( final Object raw ) {
        if ( raw == null ) {
            return Optional.empty();
        }
        final String value = raw.toString().trim().toLowerCase( Locale.ROOT );
        for ( final Confidence c : values() ) {
            if ( c.wireName.equals( value ) ) {
                return Optional.of( c );
            }
        }
        return Optional.empty();
    }
}

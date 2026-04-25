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
 * Closed vocabulary of authored relations between wiki pages. Adding a member
 * here requires a corresponding update to the {@code page_relations}
 * {@code CHECK} constraint and any agent-facing documentation.
 *
 * <p>Each member's frontmatter form is the kebab-case spelling — that is what
 * authors write in YAML and what is stored in the {@code relation_type} column.</p>
 */
public enum RelationType {

    PART_OF          ( "part-of" ),
    EXAMPLE_OF       ( "example-of" ),
    PREREQUISITE_FOR ( "prerequisite-for" ),
    SUPERSEDES       ( "supersedes" ),
    CONTRADICTS      ( "contradicts" ),
    IMPLEMENTS       ( "implements" ),
    DERIVED_FROM     ( "derived-from" );

    private final String wireName;

    RelationType( final String wireName ) {
        this.wireName = wireName;
    }

    /** The kebab-case form used in YAML frontmatter and the page_relations table. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parse a frontmatter or DB-stored relation type. Accepts the wire form
     * ({@code "part-of"}) and is forgiving on case + surrounding whitespace.
     * Returns {@link Optional#empty()} when the value isn't part of the closed
     * vocabulary — callers decide whether that is a warning or a rejection.
     */
    public static Optional< RelationType > fromWire( final Object raw ) {
        if ( raw == null ) {
            return Optional.empty();
        }
        final String value = raw.toString().trim().toLowerCase( Locale.ROOT );
        if ( value.isEmpty() ) {
            return Optional.empty();
        }
        for ( final RelationType t : values() ) {
            if ( t.wireName.equals( value ) ) {
                return Optional.of( t );
            }
        }
        return Optional.empty();
    }
}

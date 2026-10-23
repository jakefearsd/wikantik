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
package com.wikantik.pagegraph.spine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Frontmatter value coercions shared by the structural index: SnakeYAML hands back strings, lists,
 * booleans and dates depending on how an author wrote a value, and the index reads each field the
 * same way on a full rebuild, an incremental save and a delete-time sharer check.
 */
final class SpineFrontmatter {

    private static final Logger LOG = LogManager.getLogger( SpineFrontmatter.class );

    private SpineFrontmatter() { }

    static Instant parseInstant( final Object raw ) {
        if ( raw == null ) {
            return null;
        }
        if ( raw instanceof java.util.Date d ) {
            // SnakeYAML parses ISO-8601 timestamps directly into java.util.Date.
            return d.toInstant();
        }
        if ( raw instanceof Instant i ) {
            return i;
        }
        final String s = raw.toString().trim();
        if ( s.isEmpty() ) {
            return null;
        }
        try {
            return Instant.parse( s );
        } catch ( final java.time.format.DateTimeParseException ex ) {
            // Fall back to ISO_LOCAL_DATE_TIME or ISO_DATE — authors sometimes omit the trailing Z.
            try {
                return java.time.LocalDateTime.parse( s ).atZone( java.time.ZoneOffset.UTC ).toInstant();
            } catch ( final java.time.format.DateTimeParseException ignored ) {
                LOG.warn( "verified_at could not be parsed: '{}'", s );
                return null;
            }
        }
    }

    static String asString( final Object o ) {
        if ( o == null ) return null;
        final String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String firstNonBlank( final String a, final String b ) {
        return ( a == null || a.isBlank() ) ? b : a;
    }

    @SuppressWarnings( "unchecked" )
    static List< String > stringList( final Object o ) {
        if ( o == null ) return List.of();
        if ( o instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object x : list ) {
                if ( x != null ) out.add( x.toString() );
            }
            return List.copyOf( out );
        }
        return List.of( o.toString() );
    }

    /**
     * Parses the {@code kg_include} frontmatter field. SnakeYAML auto-types
     * {@code true}/{@code false} literals to {@link Boolean}, but authors may
     * also write the value as a string. Any unrecognised value yields
     * {@link Optional#empty()}.
     */
    static Optional< Boolean > parseKgInclude( final Object raw ) {
        if ( raw == null ) {
            return Optional.empty();
        }
        if ( raw instanceof Boolean b ) {
            return Optional.of( b );
        }
        final String s = raw.toString().trim().toLowerCase( java.util.Locale.ROOT );
        if ( "true".equals( s ) )  return Optional.of( true );
        if ( "false".equals( s ) ) return Optional.of( false );
        return Optional.empty();
    }
}

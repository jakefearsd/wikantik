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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The URL (and saved-filter) form of a {@link FindFilter}: parameter name to text, in both directions. */
public final class FindParams {

    /** Every parameter a find accepts. */
    public static final Set< String > NAMES = Set.of( "projects", "status", "level", "type", "label", "priority",
        "assignee", "reporter", "queue", "product", "sprint", "watching", "q", "dueBefore", "updatedSince", "sort",
        "limit", "cursor" );

    /** The parameters only a find reads (the plain item list does not take them). */
    public static final Set< String > FIND_ONLY = Set.of( "projects", "priority", "reporter", "watching",
        "updatedSince", "sort", "cursor" );

    private static final Pattern KEY = Pattern.compile( "[A-Za-z][A-Za-z0-9]{1,9}" );
    private static final int MAX_KEYS = 50;
    private static final int MAX_PERSON = 100;
    private static final int MAX_NAME = 64;
    private static final int MAX_TEXT = 200;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 4;

    private FindParams() {
    }

    /**
     * Reads the parameters. A blank value counts as absent.
     *
     * @throws WorkValidationException naming the parameter that is unknown or malformed
     */
    public static FindFilter parse( final Map< String, String > params ) {
        for ( final String name : params.keySet() ) {
            if ( !NAMES.contains( name ) ) {
                throw new WorkValidationException( name, "Unknown filter field." );
            }
        }
        return new FindFilter( projects( params.get( "projects" ) ), statuses( params.get( "status" ) ),
            levels( params.get( "level" ) ), type( params.get( "type" ) ), labels( params.get( "label" ) ),
            priorities( params.get( "priority" ) ), text( "assignee", params.get( "assignee" ), MAX_PERSON ),
            text( "queue", params.get( "queue" ), MAX_NAME ), text( "reporter", params.get( "reporter" ), MAX_PERSON ),
            text( "product", params.get( "product" ), MAX_NAME ), SprintRef.parse( params.get( "sprint" ) ),
            watching( params.get( "watching" ) ), text( "q", params.get( "q" ), MAX_TEXT ),
            dueBefore( params.get( "dueBefore" ) ), updatedSince( params.get( "updatedSince" ) ),
            sort( params.get( "sort" ) ), limit( params.get( "limit" ) ), cursor( params.get( "cursor" ) ) );
    }

    /** The canonical parameters of {@code f}: empty fields, limit and cursor left out; lists sorted and comma-joined. */
    public static SortedMap< String, String > toParams( final FindFilter f ) {
        final SortedMap< String, String > out = new TreeMap<>();
        put( out, "projects", joined( f.projectKeys(), s -> s ) );
        put( out, "status", joined( f.statuses(), WorkStatus::wire ) );
        put( out, "level", joined( f.levels(), WorkLevel::wire ) );
        put( out, "type", f.type() == null ? null : f.type().wire() );
        put( out, "label", joined( f.labels(), s -> s ) );
        put( out, "priority", joined( f.priorities(), String::valueOf ) );
        put( out, "assignee", f.assignee() );
        put( out, "reporter", f.reporter() );
        put( out, "queue", f.queue() );
        put( out, "product", f.productKey() );
        put( out, "sprint", sprintWire( f.sprint() ) );
        put( out, "watching", f.watching() ? "true" : null );
        put( out, "q", f.text() );
        put( out, "dueBefore", f.dueBefore() == null ? null : f.dueBefore().toString() );
        put( out, "updatedSince", f.updatedSince() == null ? null : f.updatedSince().toString() );
        put( out, "sort", f.sort() == FindFilter.Sort.UPDATED ? null : f.sort().wire() );
        return out;
    }

    private static void put( final Map< String, String > out, final String name, final String value ) {
        if ( value != null && !value.isEmpty() ) {
            out.put( name, value );
        }
    }

    private static < T > String joined( final Collection< T > values, final Function< T, String > wire ) {
        return values.stream().map( wire ).sorted().collect( Collectors.joining( "," ) );
    }

    private static String sprintWire( final SprintRef s ) {
        if ( s == null ) {
            return null;
        }
        return switch ( s.kind() ) {
            case ACTIVE -> "active";
            case NONE -> "none";
            case ID -> String.valueOf( s.sprintId() );
        };
    }

    private static String blankToNull( final String v ) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static List< String > items( final String v ) {
        final List< String > out = new ArrayList<>();
        for ( final String part : Arrays.asList( v.split( "," ) ) ) {
            if ( !part.isBlank() ) {
                out.add( part.trim() );
            }
        }
        return out;
    }

    private static Set< String > projects( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return null;
        }
        final List< String > keys = items( v );
        final Set< String > out = new LinkedHashSet<>();
        for ( final String k : keys ) {
            if ( !KEY.matcher( k ).matches() ) {
                throw badProjects();
            }
            out.add( k.toUpperCase( java.util.Locale.ROOT ) );
        }
        if ( keys.size() > MAX_KEYS || out.isEmpty() ) {
            throw badProjects();
        }
        return out;
    }

    private static WorkValidationException badProjects() {
        return new WorkValidationException( "projects", "projects must be project keys such as PLAT,OPS." );
    }

    private static Set< WorkStatus > statuses( final String raw ) {
        final String v = blankToNull( raw );
        return v == null ? null : items( v ).stream().map( WorkStatus::fromWire ).collect( Collectors.toSet() );
    }

    private static Set< WorkLevel > levels( final String raw ) {
        final String v = blankToNull( raw );
        return v == null ? null : items( v ).stream().map( WorkLevel::fromWire ).collect( Collectors.toSet() );
    }

    private static WorkType type( final String raw ) {
        final String v = blankToNull( raw );
        return v == null ? null : WorkType.fromWire( v );
    }

    private static Set< String > labels( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return null;
        }
        try {
            return new TreeSet<>( Labels.normalise( items( v ) ) );
        } catch ( WorkValidationException e ) {
            final WorkValidationException out = new WorkValidationException( "label", e.getMessage() );
            out.initCause( e );
            throw out;
        }
    }

    private static Set< Integer > priorities( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return null;
        }
        final Set< Integer > out = new TreeSet<>();
        for ( final String p : items( v ) ) {
            if ( !p.matches( "[1-4]" ) ) {
                throw new WorkValidationException( "priority", "priority must be " + MIN_PRIORITY + " to " + MAX_PRIORITY + "." );
            }
            out.add( Integer.valueOf( p ) );
        }
        return out;
    }

    private static String text( final String name, final String raw, final int max ) {
        final String v = blankToNull( raw );
        if ( v != null && v.length() > max ) {
            throw new WorkValidationException( name, name + " is at most " + max + " characters." );
        }
        return v;
    }

    private static boolean watching( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return false;
        }
        if ( !"true".equals( v ) && !"false".equals( v ) ) {
            throw new WorkValidationException( "watching", "watching must be true or false." );
        }
        return "true".equals( v );
    }

    private static LocalDate dueBefore( final String raw ) {
        final String v = blankToNull( raw );
        return v == null ? null : FindDates.date( "dueBefore", v, "dueBefore must be a date such as 2026-12-31." );
    }

    private static Instant updatedSince( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return null;
        }
        final String message = "updatedSince must be a date such as 2026-11-01 or an instant.";
        return v.length() <= "2026-11-01".length()
            ? FindDates.date( "updatedSince", v, message ).atStartOfDay( java.time.ZoneOffset.UTC ).toInstant()
            : FindDates.instant( "updatedSince", v, message );
    }

    private static FindFilter.Sort sort( final String raw ) {
        final String v = blankToNull( raw );
        return v == null ? null : FindFilter.Sort.fromWire( v );
    }

    private static int limit( final String raw ) {
        final String v = blankToNull( raw );
        if ( v == null ) {
            return 0;
        }
        if ( !v.matches( "\\d{1,9}" ) ) {
            throw new WorkValidationException( "limit", "limit must be a whole number." );
        }
        return Integer.parseInt( v );
    }

    private static String cursor( final String raw ) {
        return text( "cursor", raw, MAX_TEXT );
    }
}

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
package com.wikantik.extractcli.configref;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Description rendering: summarise a key's raw comment-block description for the table, keep the
 * full text (with {@code Example:} lines rendered as code rather than run-on prose) for a
 * per-section definition list. Split out of {@link GenerateConfigReferenceCli} (2026-09,
 * complexity burn-down) — a pure text-transformation concern with no dependency on the rest of
 * the generator.
 */
final class DescriptionRenderer {

    /** A key's Description cell is a summary; anything cut from it is shown in full below the
     *  table, so this only needs to keep GFM table rows scannable, not to fit everything. */
    private static final int SUMMARY_MAX_CHARS = 155;

    private static final Pattern EXAMPLE_LINE = Pattern.compile( "^Example:.*$" );
    private static final Pattern SENTENCE_END = Pattern.compile( "[.!?](?=\\s|$)" );

    private record Segment( boolean example, String text ) {}

    record DescriptionRender( String summary, boolean truncated, String full ) {}

    private DescriptionRenderer() {}

    /**
     * Splits {@code lines} into prose runs and standalone {@code Example:} lines, preserving
     * order — a raw comment block interleaves worked examples with prose (see
     * {@code wikantik.pageNameComparator.class}), and joining everything with a bare space (the
     * old behaviour) reads as run-on prose that swallows the example text's own meaning.
     */
    private static List<Segment> toSegments( final List<String> lines ) {
        final List<Segment> segments = new ArrayList<>();
        final List<String> prose = new ArrayList<>();
        for ( final String line : lines ) {
            if ( EXAMPLE_LINE.matcher( line ).matches() ) {
                if ( !prose.isEmpty() ) {
                    segments.add( new Segment( false, String.join( " ", prose ) ) );
                    prose.clear();
                }
                segments.add( new Segment( true, line ) );
            } else {
                prose.add( line );
            }
        }
        if ( !prose.isEmpty() ) {
            segments.add( new Segment( false, String.join( " ", prose ) ) );
        }
        return segments;
    }

    /** Joins {@code lines} into the entry's full text: prose flows normally, each
     *  {@code Example:} line becomes its own inline code span. Used by {@link HoistScanner} to
     *  render a hoisted section preamble. */
    static String renderFull( final List<String> lines ) {
        return toSegments( lines ).stream()
                .map( s -> s.example() ? "`" + s.text() + "`" : s.text() )
                .collect( Collectors.joining( " " ) );
    }

    /**
     * Produces the table-cell summary and, when that summary drops content, the full text to
     * list below the table. A description that already fits (no {@code Example:} lines, full
     * text at or under {@link #SUMMARY_MAX_CHARS}) is returned unchanged in both fields and
     * {@code truncated} is false — the common case for the ~80% of keys with a one-sentence
     * description, which render exactly as before.
     */
    static DescriptionRender renderDescription( final List<String> ownDescriptionLines ) {
        final List<Segment> segments = toSegments( ownDescriptionLines );
        final boolean hasExamples = segments.stream().anyMatch( Segment::example );
        final String full = segments.stream()
                .map( s -> s.example() ? "`" + s.text() + "`" : s.text() )
                .collect( Collectors.joining( " " ) );

        if ( !hasExamples && full.length() <= SUMMARY_MAX_CHARS ) {
            return new DescriptionRender( full, false, full );
        }

        final String proseOnly = segments.stream()
                .filter( s -> !s.example() )
                .map( Segment::text )
                .collect( Collectors.joining( " " ) );

        final String base;
        if ( proseOnly.isBlank() ) {
            base = "(see full description below)";
        } else {
            final Matcher m = SENTENCE_END.matcher( proseOnly );
            base = ( m.find() && m.end() <= SUMMARY_MAX_CHARS ) ? proseOnly.substring( 0, m.end() )
                                                                 : truncateAtWordBoundary( proseOnly, SUMMARY_MAX_CHARS );
        }
        return new DescriptionRender( base + " …", true, full );
    }

    static String truncateAtWordBoundary( final String text, final int max ) {
        if ( text.length() <= max ) {
            return text;
        }
        final int cut = text.lastIndexOf( ' ', max );
        return ( cut > 0 ? text.substring( 0, cut ) : text.substring( 0, max ) ).stripTrailing();
    }
}

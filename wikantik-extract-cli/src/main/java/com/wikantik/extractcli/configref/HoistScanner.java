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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Section-preamble recovery.
 *
 * <p>{@code ConfigReference.Entry#description()} flattens every paragraph in a key's comment
 * block into one list — it has no notion of "this paragraph is generic section prose, that one is
 * the key's own description". Immediately after a {@code # [Section]} marker, that flattening
 * lets the section's own preamble bleed into the first key that follows it (separated only by a
 * "#"-only comment line, not a truly blank one) — see {@code wikantik.applicationName} and
 * {@code wikantik.loginModule.class}. This re-scans the raw file once per source to recover the
 * paragraph grouping {@code ConfigReference} already discards, scoped deliberately to just the
 * first key after each section marker: multi-paragraph comment blocks occur elsewhere too (a key
 * musing across a few remarks about itself, e.g. {@code wikantik.cache.enable}), and outside that
 * "right after the heading" position there's no section-level home to hoist them to.
 *
 * <p>Split out of {@link GenerateConfigReferenceCli} (2026-09, complexity burn-down).
 */
final class HoistScanner {

    private static final Pattern HOIST_SECTION = Pattern.compile( "^#\\s*\\[(.+?)\\]\\s*$" );
    private static final Pattern HOIST_KEY_VALUE = Pattern.compile( "^([A-Za-z0-9_.\\-]+)\\s*[=:]\\s*(.*)$" );
    private static final Pattern HOIST_DIRECTIVE = Pattern.compile( "^(Type|Blank means|Source):\\s*(.*)$" );

    record Hoist( List<String> preambleParagraphs, List<String> ownDescription ) {}

    private HoistScanner() {}

    /**
     * @return key line number (1-based, matches {@code ConfigReference.Entry#line()}) → the
     *         section preamble to hoist and that key's own trimmed description. Absent for every
     *         key whose comment block didn't need splitting — which is most of them.
     */
    static Map<Integer, Hoist> scanHoists( final List<String> lines ) {
        final Map<Integer, Hoist> result = new LinkedHashMap<>();
        final List<String> block = new ArrayList<>();
        boolean justEnteredSection = false;

        for ( int i = 0; i < lines.size(); i++ ) {
            final String line = lines.get( i ).strip();
            if ( line.isEmpty() ) {
                block.clear();
                continue;
            }
            if ( HOIST_SECTION.matcher( line ).matches() ) {
                block.clear();
                justEnteredSection = true;
                continue;
            }
            if ( line.startsWith( "#" ) || line.startsWith( "!" ) ) {
                block.add( line.substring( 1 ).strip() );
                continue;
            }
            if ( !HOIST_KEY_VALUE.matcher( line ).matches() ) {
                block.clear();
                continue;
            }

            if ( justEnteredSection ) {
                recordHoistIfPresent( block, i + 1, result );
            }
            justEnteredSection = false;
            block.clear();
        }
        return result;
    }

    /**
     * Splits {@code block} into paragraph groups; when there is more than one group AND the last
     * (the key's own) group still has prose of its own once its directives are stripped, records
     * a hoist for {@code keyLineNumber}. Otherwise a no-op — e.g. {@code wikantik.pageProvider},
     * whose last group is bare {@code Type: class}, would leave the row with nothing to hoist.
     */
    private static void recordHoistIfPresent( final List<String> block, final int keyLineNumber,
                                                final Map<Integer, Hoist> result ) {
        final List<List<String>> groups = splitIntoGroups( block );
        if ( groups.size() <= 1 ) {
            return;
        }
        final List<String> lastGroup = groups.get( groups.size() - 1 );
        final int ownStart = firstDirectiveIndex( lastGroup );
        if ( ownStart <= 0 ) {
            return;
        }
        final List<String> preamble = new ArrayList<>();
        for ( int g = 0; g < groups.size() - 1; g++ ) {
            preamble.add( DescriptionRenderer.renderFull( groups.get( g ) ) );
        }
        result.put( keyLineNumber, new Hoist( List.copyOf( preamble ),
                List.copyOf( lastGroup.subList( 0, ownStart ) ) ) );
    }

    /** Splits a raw comment block into paragraph groups, separated by "#"-only lines (which
     *  {@code block} carries as empty strings — see the caller). A leading separator with no
     *  content before it produces no empty leading group. */
    private static List<List<String>> splitIntoGroups( final List<String> block ) {
        final List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for ( final String c : block ) {
            if ( c.isEmpty() ) {
                if ( !current.isEmpty() ) {
                    groups.add( current );
                    current = new ArrayList<>();
                }
            } else {
                current.add( c );
            }
        }
        if ( !current.isEmpty() ) {
            groups.add( current );
        }
        return groups;
    }

    /** Index of the first directive-matching line in {@code group}, or {@code group.size()} if
     *  it has none (a pure-prose paragraph with no {@code Type:}/{@code Blank means:}/{@code
     *  Source:} of its own). */
    private static int firstDirectiveIndex( final List<String> group ) {
        for ( int i = 0; i < group.size(); i++ ) {
            if ( HOIST_DIRECTIVE.matcher( group.get( i ) ).matches() ) {
                return i;
            }
        }
        return group.size();
    }
}

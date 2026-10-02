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
package com.wikantik.importer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts Obsidian inline {@code #tags} from the prose of a note body. */
public final class InlineTags {

    private static final Pattern WIKILINK = Pattern.compile( "\\[\\[[^\\]\\n]*\\]\\]" );
    private static final Pattern LINK_DEST = Pattern.compile( "\\]\\([^)\\n]*\\)" );
    private static final Pattern URL = Pattern.compile( "\\S+://\\S+" );
    private static final Pattern HEADING = Pattern.compile( "^\\s{0,3}#{1,6}\\s.*" );
    private static final Pattern TAG = Pattern.compile( "(?<![^\\s])#([\\p{L}\\p{N}_/-]+)" );
    private static final Pattern NON_DIGIT = Pattern.compile( "[^\\p{N}]" );

    private InlineTags() {
    }

    /** Normalised inline tags in first-seen order. Code is skipped; a tag must contain a non-digit. */
    public static Set< String > scan( final String body ) {
        final Set< String > tags = new LinkedHashSet<>();
        CodeSegments.forEachProse( body, segment -> scanSegment( segment, tags ) );
        return tags;
    }

    private static void scanSegment( final String segment, final Set< String > tags ) {
        String s = WIKILINK.matcher( segment ).replaceAll( " " );
        s = LINK_DEST.matcher( s ).replaceAll( " " );
        s = URL.matcher( s ).replaceAll( " " );
        for ( final String line : s.split( "\n", -1 ) ) {
            if ( !HEADING.matcher( line ).matches() ) {
                collect( line, tags );
            }
        }
    }

    private static void collect( final String line, final Set< String > tags ) {
        final Matcher m = TAG.matcher( line );
        while ( m.find() ) {
            if ( NON_DIGIT.matcher( m.group( 1 ) ).find() ) {
                tags.add( VaultFrontmatterMapper.normaliseTag( m.group( 1 ) ) );
            }
        }
    }
}

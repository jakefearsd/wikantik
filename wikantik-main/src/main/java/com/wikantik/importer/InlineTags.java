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

import com.wikantik.api.parser.CodeMask;

/** Extracts Obsidian inline {@code #tags} from the prose of a note body. */
public final class InlineTags {

    private static final Pattern WIKILINK = Pattern.compile( "\\[\\[[^\\]\\n]*\\]\\]" );
    private static final Pattern LINK_DEST = Pattern.compile( "\\]\\([^)\\n]*\\)" );
    private static final Pattern URL = Pattern.compile( "\\S+://\\S+" );
    private static final Pattern HEADING = Pattern.compile( "^\\s{0,3}#{1,6}\\s.*" );
    private static final Pattern TAG = Pattern.compile( "(?<![^\\s])#([\\p{L}\\p{N}_/-]+)" );
    private static final Pattern NON_DIGIT = Pattern.compile( "[\\p{L}_-]" );
    private static final char CODE_PLACEHOLDER = 'X';

    private InlineTags() {
    }

    /**
     * Normalised inline tags in first-seen order. Code (fenced, indented, inline) is replaced by a non-space
     * placeholder of equal length, so line context survives and text glued to a code span is never a tag.
     */
    public static Set< String > scan( final String body ) {
        final Set< String > tags = new LinkedHashSet<>();
        String s = blankCode( body );
        s = WIKILINK.matcher( s ).replaceAll( " " );
        s = LINK_DEST.matcher( s ).replaceAll( " " );
        s = URL.matcher( s ).replaceAll( " " );
        for ( final String line : s.split( "\n", -1 ) ) {
            if ( !HEADING.matcher( line ).matches() ) {
                collect( line, tags );
            }
        }
        return tags;
    }

    private static String blankCode( final String body ) {
        final boolean[] code = CodeMask.of( body );
        final StringBuilder sb = new StringBuilder( body );
        for ( int i = 0; i < sb.length(); i++ ) {
            if ( code[ i ] && sb.charAt( i ) != '\n' && sb.charAt( i ) != '\r' ) {
                sb.setCharAt( i, CODE_PLACEHOLDER );
            }
        }
        return sb.toString();
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

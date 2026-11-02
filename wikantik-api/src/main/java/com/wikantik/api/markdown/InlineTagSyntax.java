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
package com.wikantik.api.markdown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obsidian inline {@code #tags}: the one place that decides what is a tag in prose. Shared by the renderer's inline
 * parser (which applies the same boundary rule), the indexers, the importer and the editor (which mirrors it through the
 * {@code inline-tags.json} fixture).
 *
 * <ul>
 *   <li>{@code #} preceded by start-of-line or whitespace;</li>
 *   <li>tag characters {@code [\p{L}\p{N}_/-]}, at least one of them not a digit (so {@code #123} is not a tag, and
 *       {@code #fff} is);</li>
 *   <li>never in code (fenced, indented, inline), in inline or block math, in a URL, a wikilink or a link
 *       destination, nor on an ATX heading line.</li>
 * </ul>
 *
 * <p>A tag's identity is {@link #normalise(String)}; the text keeps what the author wrote. {@link #scan} reports offsets
 * in the text it is given, and callers that index must hide {@code %%comments%%} first, as {@link #normalisedTags} does.
 * Linear in the text: every variable-length scan stops where the next match could start.
 */
public final class InlineTagSyntax {

    /** One tag occurrence: {@code [start, end)} spans the {@code #} and the tag; {@code written} is the tag without the {@code #}. */
    public record Tag( int start, int end, String written, String normalised ) {
    }

    private static final Pattern WIKILINK = Pattern.compile( "\\[\\[[^\\[\\]\\n]*\\]\\]" );
    private static final Pattern LINK_DEST = Pattern.compile( "\\]\\([^)\\]\\n]*\\)" );
    /** Anchored at the start of a non-space run: same matches as {@code \S+://\S+}, one attempt per run. */
    private static final Pattern URL = Pattern.compile( "(?<!\\S)\\S+://\\S+" );
    private static final Pattern HEADING = Pattern.compile( "^\\s{0,3}#{1,6}\\s.*" );
    private static final Pattern TAG = Pattern.compile( "(?<![^\\s])#([\\p{L}\\p{N}_/-]+)" );
    private static final Pattern NON_DIGIT = Pattern.compile( "[\\p{L}_-]" );
    private static final char MASKED = 'X';

    private InlineTagSyntax() {
    }

    /** Strip leading {@code #}s, turn {@code /} into {@code -}, lowercase: a tag's identity wherever tags are matched or listed. */
    public static String normalise( final String raw ) {
        String t = raw.strip();
        while ( t.startsWith( "#" ) ) {
            t = t.substring( 1 );
        }
        return t.replace( '/', '-' ).toLowerCase( Locale.ROOT );
    }

    /** Whether {@code candidate} (the characters after a {@code #}) can be a tag: it holds a letter, underscore or dash. */
    public static boolean isTagText( final String candidate ) {
        return NON_DIGIT.matcher( candidate ).find();
    }

    /** Whether {@code c} may appear in a tag. */
    public static boolean isTagChar( final char c ) {
        return Character.isLetterOrDigit( c ) || c == '_' || c == '/' || c == '-';
    }

    /**
     * Normalised tags of a page body in first-seen order, duplicates removed, comments hidden first.
     *
     * @param body page body; may be {@code null}
     */
    public static Set< String > normalisedTags( final String body ) {
        final Set< String > tags = new LinkedHashSet<>();
        if ( body == null || body.indexOf( '#' ) < 0 ) {
            return tags;
        }
        for ( final Tag t : scan( ObsidianComments.strip( body ) ) ) {
            tags.add( t.normalised() );
        }
        return tags;
    }

    /** Every tag in {@code body}, in order, with offsets into {@code body}. */
    public static List< Tag > scan( final String body ) {
        final List< Tag > tags = new ArrayList<>();
        if ( body == null || body.indexOf( '#' ) < 0 ) {
            return tags;
        }
        final String s = blank( body );
        int offset = 0;
        for ( final String line : s.split( "\n", -1 ) ) {
            if ( !HEADING.matcher( line ).matches() ) {
                collect( line, offset, tags );
            }
            offset += line.length() + 1;
        }
        return tags;
    }

    /** Same-length copy with code and math masked by a non-space placeholder and links/URLs blanked, so offsets survive. */
    private static String blank( final String body ) {
        final boolean[] masked = ObsidianComments.mask( body );
        final StringBuilder sb = new StringBuilder( body );
        for ( int i = 0; i < sb.length(); i++ ) {
            if ( masked[ i ] && sb.charAt( i ) != '\n' && sb.charAt( i ) != '\r' ) {
                sb.setCharAt( i, MASKED );
            }
        }
        blankMatches( sb, WIKILINK );
        blankMatches( sb, LINK_DEST );
        blankMatches( sb, URL );
        return sb.toString();
    }

    private static void blankMatches( final StringBuilder sb, final Pattern p ) {
        final Matcher m = p.matcher( sb );
        while ( m.find() ) {
            for ( int i = m.start(); i < m.end(); i++ ) {
                sb.setCharAt( i, ' ' );
            }
        }
    }

    private static void collect( final String line, final int lineOffset, final List< Tag > tags ) {
        final Matcher m = TAG.matcher( line );
        while ( m.find() ) {
            if ( isTagText( m.group( 1 ) ) ) {
                tags.add( new Tag( lineOffset + m.start(), lineOffset + m.end(), m.group( 1 ), normalise( m.group( 1 ) ) ) );
            }
        }
    }
}

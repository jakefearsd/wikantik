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
package com.wikantik.parser.markdown;

import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockIds.Kind;
import com.wikantik.parser.markdown.BlockModel.Entry;
import com.wikantik.parser.markdown.BlockModel.Shown;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Text edits for {@link BlockIds}: adding a marker (worked out on the shown text, made in the raw one), cutting markers
 * out, and taking a nested block's source out of its container. Package-private; {@link BlockIds} is the API.
 */
final class BlockEdits {

    private BlockEdits() {
    }


    /**
     * One edit of the shown text: {@code [from, to)} replaced by {@code insert}.
     *
     * @param from   start, in the shown text
     * @param to     end, in the shown text
     * @param insert what goes there
     */
    record Edit( int from, int to, String insert ) {

        /**
         * The same edit made in the raw text: only the raw characters the shown {@code [from, to)} came from are cut
         * (never what the strip had already cut — an access rule), and {@code insert} goes where the first of them was,
         * or, for a pure insertion, right after the raw character the shown one before {@code from} came from.
         */
        String applyTo( final Shown shown ) {
            final String raw = shown.raw();
            final List< int[] > cuts = shown.tracked().sourceSpans( from, to );
            final int at = cuts.isEmpty() ? shown.tracked().toSourceEnd( from ) : cuts.get( 0 )[ 0 ];
            return without( raw.substring( 0, at ) + insert + raw.substring( at ), shifted( cuts, at, insert.length() ) );
        }

        private static List< int[] > shifted( final List< int[] > cuts, final int at, final int by ) {
            final List< int[] > out = new ArrayList<>( cuts.size() );
            for ( final int[] c : cuts ) {
                out.add( c[ 0 ] >= at ? new int[]{ c[ 0 ] + by, c[ 1 ] + by } : c );
            }
            return out;
        }
    }


    /** {@code text} without the disjoint ranges {@code cuts} (in any order). */
    static String without( final String text, final List< int[] > cuts ) {
        cuts.sort( Comparator.comparingInt( c -> c[ 0 ] ) );
        final StringBuilder out = new StringBuilder( text.length() );
        int copied = 0;
        for ( final int[] cut : cuts ) {
            out.append( text, copied, cut[ 0 ] );
            copied = cut[ 1 ];
        }
        return out.append( text, copied, text.length() ).toString();
    }

    /**
     * The edits of the shown {@code text} that could tag {@code b} (whose numbered entry is {@code entry}) with
     * {@code id}, best first: a duplicate's own marker renamed; an inline marker; and, for a paragraph whose last line
     * cannot carry one (a {@code "1."} that a trailing {@code " ^id"} would turn into a list item), a standalone
     * marker after it; a standalone marker for a block that takes no inline one.
     */
    static List< Edit > candidates( final String text, final Block b, final Entry entry, final String id ) {
        if ( entry.marker() != null ) {   // a duplicate: its marker carries no id, so its id is replaced
            final int idStart = entry.marker().caret() + 1;
            return List.of( new Edit( idStart, idStart + entry.marker().id().length(), id ) );
        }
        if ( entry.inlineAt() >= 0 ) {
            final Edit inline = new Edit( entry.inlineAt(), entry.inlineAt(), " ^" + id );
            return b.kind() == Kind.PARAGRAPH ? List.of( inline, withStandaloneMarker( text, entry, id ) ) : List.of( inline );
        }
        if ( b.kind() == Kind.LIST_ITEM ) {
            throw new IllegalArgumentException( "List item " + b.index() + " has no text line to carry an id" );
        }
        return List.of( withStandaloneMarker( text, entry, id ) );
    }

    /** Standalone insertion of {@code ^id} after a block that cannot take an inline marker. */
    private static Edit withStandaloneMarker( final String text, final Entry entry, final String id ) {
        final String prefix = continuationPrefix( text.substring( lineStart( text, entry.start() ), entry.start() ) );
        final String blank = prefix.stripTrailing();
        final int nl = text.indexOf( '\n', entry.end() );
        if ( nl < 0 ) {
            final String eol = text.contains( "\r\n" ) ? "\r\n" : "\n";
            return new Edit( text.length(), text.length(), eol + blank + eol + prefix + "^" + id );
        }
        final String eol = nl > 0 && text.charAt( nl - 1 ) == '\r' ? "\r\n" : "\n";
        final int at = nl + 1;
        final StringBuilder insert = new StringBuilder().append( blank ).append( eol ).append( prefix ).append( '^' ).append( id ).append( eol );
        if ( !isBlankLine( text, at ) ) {
            insert.append( blank ).append( eol );   // keep the marker its own paragraph, apart from what follows
        }
        return new Edit( at, at, insert.toString() );
    }

    static int lineStart( final String text, final int pos ) {
        return text.lastIndexOf( '\n', pos - 1 ) + 1;
    }

    /**
     * {@code source} with {@code prefix} taken off every line after the first. A line that carries only part of it
     * keeps the rest when what is missing is whitespace ({@code ">"} for {@code "> "}); a line that lacks one of its
     * {@code >} (a lazy continuation, or a blank line) is left as it is.
     */
    static String withoutContainerPrefix( final String source, final String prefix ) {
        if ( prefix.isEmpty() || source.indexOf( '\n' ) < 0 ) {
            return source;
        }
        final StringBuilder out = new StringBuilder( source.length() );
        final int firstBreak = source.indexOf( '\n' ) + 1;
        out.append( source, 0, firstBreak );
        int lineStart = firstBreak;
        while ( lineStart < source.length() ) {
            final int nl = source.indexOf( '\n', lineStart );
            final int lineEnd = nl < 0 ? source.length() : nl + 1;
            int k = 0;
            while ( k < prefix.length() && lineStart + k < lineEnd && source.charAt( lineStart + k ) == prefix.charAt( k ) ) {
                k++;
            }
            out.append( source, prefix.substring( k ).isBlank() ? lineStart + k : lineStart, lineEnd );
            lineStart = lineEnd;
        }
        return out.toString();
    }

    /** A line prefix turned into the prefix of a continuation line: block-quote {@code >} and tabs kept, the rest spaces. */
    static String continuationPrefix( final String prefix ) {
        final StringBuilder out = new StringBuilder( prefix.length() );
        for ( int i = 0; i < prefix.length(); i++ ) {
            final char c = prefix.charAt( i );
            out.append( c == '>' || c == '\t' ? c : ' ' );
        }
        return out.toString();
    }

    /** Whether the line at {@code at} holds nothing but whitespace and block-quote markers (or there is no line). */
    private static boolean isBlankLine( final String text, final int at ) {
        for ( int i = at; i < text.length() && text.charAt( i ) != '\n'; i++ ) {
            final char c = text.charAt( i );
            if ( c != '>' && !Character.isWhitespace( c ) ) {
                return false;
            }
        }
        return true;
    }
}

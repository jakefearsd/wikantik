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
package com.wikantik.markdown.extensions.math;

import com.wikantik.markdown.extensions.math.DisplayMathBlocks.Located;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-processes Markdown source text to convert {@code $$...$$} display math blocks
 * into {@code ```math} fenced code blocks that the Flexmark GitLab extension can render.
 *
 * <p>{@link DisplayMathBlocks} finds the blocks with the reader's block structure (#128). Each becomes a fence longer
 * than any backtick run alone on one of its content lines, so a {@code ```} inside the math never ends it; a block its
 * container ended gets no closing fence.</p>
 * <p>Inline occurrences within a paragraph are left untouched.</p>
 */
public final class DisplayMathPreProcessor {

    /** What the opening {@code $$} line becomes after its indent and fence: the info string and the line break. */
    private static final String MATH_INFO = "math\n";
    private static final int MIN_FENCE = 3;

    private DisplayMathPreProcessor() {
        // utility class
    }

    /**
     * Transforms {@code $$...$$} display math blocks into {@code ```math} fenced code blocks.
     *
     * @param source the raw Markdown source text; may be {@code null}
     * @return the transformed text, or the original value when {@code null}/empty
     */
    public static String transform( final String source ) {
        if ( source == null || source.isEmpty() ) {
            return source;
        }
        return transformTracked( source ).text();
    }

    /**
     * One rewritten {@code $$...$$} block: {@code source[sourceStart, sourceEnd)} became
     * {@code text[targetStart, targetEnd)}. The math content is copied verbatim:
     * {@code source[sourceContent, sourceContent + contentLength)} is {@code text[targetContent, targetContent +
     * contentLength)}; everything else in the edit is generated fence markup. Text outside the edits is copied unchanged.
     * A block its container ended has no closing fence: its edit ends with its content.
     *
     * @param sourceStart   start of the block in the source
     * @param sourceEnd     end of the block in the source
     * @param targetStart   start of its replacement in the transformed text
     * @param targetEnd     end of its replacement in the transformed text
     * @param sourceContent where the copied content starts in the source
     * @param targetContent where it starts in the transformed text
     * @param contentLength its length
     */
    public record Edit( int sourceStart, int sourceEnd, int targetStart, int targetEnd,
                        int sourceContent, int targetContent, int contentLength ) {
    }

    /**
     * The transformed text and where it differs from the source, so a position in it can be taken back.
     *
     * @param text  what {@link #transform(String)} returns
     * @param edits the rewritten blocks, in order
     */
    public record Tracked( String text, List< Edit > edits ) {
    }

    /**
     * {@link #transform(String)}, also reporting each rewritten block.
     *
     * @param source the raw Markdown source text; must not be {@code null}
     * @return the transformed text with its edits (none, and the very same text, when there is no display math)
     */
    public static Tracked transformTracked( final String source ) {
        // Most pages carry no math at all, and this runs on every parse: no "$$", no parse.
        if ( !source.contains( "$$" ) ) {
            return new Tracked( source, List.of() );
        }
        final List< Located > blocks = DisplayMathBlocks.find( source );
        if ( blocks.isEmpty() ) {
            return new Tracked( source, List.of() );
        }
        final StringBuilder result = new StringBuilder( source.length() + 16 * blocks.size() );
        final List< Edit > edits = new ArrayList<>( blocks.size() );
        int copied = 0;
        for ( final Located block : blocks ) {
            result.append( source, copied, block.lineStart );
            final int targetStart = result.length();
            final String indent = source.substring( block.lineStart, block.indentEnd );
            final String content = block.contentEnd < 0 ? "" : source.substring( block.contentStart, block.contentEnd );
            final String fence = "`".repeat( fenceLength( content ) );
            result.append( indent ).append( fence );
            final int sourceEnd;
            final int targetContent;
            if ( block.closerEnd >= 0 ) {
                result.append( MATH_INFO );
                targetContent = result.length();
                if ( !content.isEmpty() ) {
                    result.append( content ).append( '\n' );
                }
                result.append( indent ).append( fence );
                sourceEnd = block.closerEnd;
            } else if ( content.isEmpty() ) {
                result.append( "math" );
                targetContent = result.length();
                sourceEnd = block.openerEnd;
            } else {
                result.append( MATH_INFO );
                targetContent = result.length();
                result.append( content );
                sourceEnd = block.contentEnd;
            }
            edits.add( new Edit( block.lineStart, sourceEnd, targetStart, result.length(),
                    block.closerEnd < 0 && block.contentEnd < 0 ? sourceEnd : block.contentStart, targetContent, content.length() ) );
            copied = sourceEnd;
        }
        result.append( source, copied, source.length() );
        return new Tracked( result.toString(), List.copyOf( edits ) );
    }

    /** One more than the longest run of backticks alone on a line of {@code content}, and at least three. */
    private static int fenceLength( final String content ) {
        int longest = 0;
        int lineStart = 0;
        while ( lineStart <= content.length() ) {
            final int nl = content.indexOf( '\n', lineStart );
            final int lineEnd = nl < 0 ? content.length() : nl;
            longest = Math.max( longest, backtickRun( content, lineStart, lineEnd ) );
            if ( nl < 0 ) {
                break;
            }
            lineStart = nl + 1;
        }
        return Math.max( MIN_FENCE, longest + 1 );
    }

    /** The length of the backtick run that is alone (spaces and tabs around it) on {@code s[from, to)}, else 0. */
    private static int backtickRun( final CharSequence s, final int from, final int to ) {
        int i = from;
        while ( i < to && isBlank( s.charAt( i ) ) ) {
            i++;
        }
        final int runStart = i;
        while ( i < to && s.charAt( i ) == '`' ) {
            i++;
        }
        final int run = i - runStart;
        while ( i < to && ( isBlank( s.charAt( i ) ) || s.charAt( i ) == '\r' ) ) {
            i++;
        }
        return i == to ? run : 0;
    }

    private static boolean isBlank( final char c ) {
        return DisplayMathBlocks.isBlank( c );
    }
}

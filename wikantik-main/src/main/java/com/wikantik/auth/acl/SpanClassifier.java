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
package com.wikantik.auth.acl;

import com.wikantik.parser.markdown.ReaderComments;
import com.wikantik.auth.acl.AccessRules.Shape;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.OffsetRanges;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How {@link AccessRules} classifies one candidate span of a parsed text: by its region, then its spelling, then whether
 * the render parse sees it as one label. Built once per text; ask about candidates in ascending order of start.
 */
final class SpanClassifier {

    private final String text;
    private final int bodyStart;
    private final OffsetRanges code;
    private final OffsetRanges math;
    private final OffsetRanges html;
    private final OffsetRanges links;
    private final DirectiveSpelling.LineEnds lineEnds;
    /** Where the text scan sees code: a frontmatter {@code DENY} there is literal (the body's code is the parse's). */
    private final EnforcedAclDirectives.CodeRegions frontmatterCode;
    /** The render path's rules, by start. */
    private final Map< Integer, RenderPathAccessRules.Rule > rules = new HashMap<>();
    /** Every rule-shaped label the parse sees — the render path's rules and the labels it does not read — by start. */
    private final Map< Integer, RenderPathAccessRules.Rule > labels = new HashMap<>();

    /** The body's comments and what the render, which parses the body without them, sees; {@code null} when it has none. */
    private final Comments comments;

    /**
     * The body's Obsidian comments, and the structure of the body without them: what the render parses. Built only for a
     * body that holds a comment, at the cost of one more parse.
     */
    static final class Comments {
        /** Comment ranges, in full-text offsets, ascending. */
        private final int[] starts;
        private final int[] ends;
        /** Characters the strip removes before the end of each range, in total. */
        private final int[] removedThrough;
        private final OffsetRanges strippedCode;
        private final java.util.Set< Integer > strippedRules = new java.util.HashSet<>();

        private Comments( final List< int[] > ranges, final String text, final int bodyStart,
                          final RenderPathAccessRules.Structure stripped ) {
            starts = new int[ ranges.size() ];
            ends = new int[ ranges.size() ];
            removedThrough = new int[ ranges.size() ];
            int removed = 0;
            for ( int i = 0; i < ranges.size(); i++ ) {
                starts[ i ] = bodyStart + ranges.get( i )[ 0 ];
                ends[ i ] = bodyStart + ranges.get( i )[ 1 ];
                for ( int p = starts[ i ]; p < ends[ i ]; p++ ) {
                    if ( text.charAt( p ) != '\n' ) {
                        removed++;
                    }
                }
                removedThrough[ i ] = removed;
            }
            strippedCode = OffsetRanges.of( stripped.code() );
            for ( final RenderPathAccessRules.Rule rule : stripped.rules() ) {
                strippedRules.add( rule.start() );
            }
        }

        /**
         * @param text      raw page text
         * @param bodyStart where its body starts
         * @param structure {@link RenderPathAccessRules#structure} of that same text: the body is already parsed, so its
         *                  comments are read off it rather than off a second parse
         * @return the body's comments, or {@code null} when it has none
         * @throws RenderPathAccessRules.Unreadable when the body without its comments cannot be parsed
         */
        static Comments of( final String text, final int bodyStart, final RenderPathAccessRules.Structure structure ) {
            final List< int[] > ranges = ReaderComments.ranges( text.substring( bodyStart ), structure, bodyStart );
            return ranges.isEmpty() ? null
                    : new Comments( ranges, text, bodyStart, RenderPathAccessRules.commentStrippedStructure( text, ranges ) );
        }

        /** Index of the last range starting at or before {@code offset}, or -1. */
        private int floor( final int offset ) {
            int low = 0;
            int high = starts.length - 1;
            int found = -1;
            while ( low <= high ) {
                final int mid = ( low + high ) >>> 1;
                if ( starts[ mid ] <= offset ) {
                    found = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return found;
        }

        boolean contains( final int offset ) {
            final int i = floor( offset );
            return i >= 0 && offset < ends[ i ];
        }

        /** Where a raw offset outside every comment is in the stripped text. */
        private int stripped( final int offset ) {
            final int i = floor( offset );
            return i < 0 ? offset : offset - removedThrough[ i ];
        }

        /**
         * Whether removing the comments moves a span at {@code offset} into or out of code, or makes the render read a
         * rule there: then the render and the reader, on the stored text, disagree about it.
         */
        boolean renderDisagrees( final int offset, final boolean rawCode ) {
            final int at = stripped( offset );
            return strippedCode.contains( at ) != rawCode;
        }

        boolean renderReadsARule( final int offset ) {
            return strippedRules.contains( stripped( offset ) );
        }
    }

    SpanClassifier( final String text, final int bodyStart, final RenderPathAccessRules.Structure structure,
                    final Comments comments ) {
        this.comments = comments;
        this.text = text;
        this.bodyStart = bodyStart;
        this.code = OffsetRanges.of( structure.code() );
        this.math = OffsetRanges.of( structure.math() );
        this.html = OffsetRanges.of( structure.html() );
        this.links = OffsetRanges.of( structure.links() );
        this.lineEnds = new DirectiveSpelling.LineEnds( text );
        this.frontmatterCode = new EnforcedAclDirectives.CodeRegions( text );
        for ( final RenderPathAccessRules.Rule rule : structure.rules() ) {
            rules.put( rule.start(), rule );
            labels.put( rule.start(), rule );
        }
        for ( final RenderPathAccessRules.Rule label : structure.labels() ) {
            labels.putIfAbsent( label.start(), label );
        }
    }

    /**
     * @param start      offset of the candidate's {@code [}
     * @param end        offset just past it
     * @param scannedEnd where the old text scan's rule starting here ends; {@code null} when it read none
     * @return the candidate's shape, or {@code null} when it is literal text that neither old reading took for a rule
     */
    Shape classify( final int start, final int end, final Integer scannedEnd ) {
        final Shape shape = shapeOf( start, end, scannedEnd );
        if ( shape != null && shape != Shape.ESCAPED && comments != null && start >= bodyStart && comments.contains( start ) ) {
            // A comment is not reader text, but the reader reads it: what would be a rule (or a lock) without the comment
            // markers locks, so "commented out" never silently grants, nor silently drops a rule.
            return Shape.IN_COMMENT;
        }
        return shape;
    }

    /** {@link #classify}, as if no comment held the span. */
    private Shape shapeOf( final int start, final int end, final Integer scannedEnd ) {
        final boolean readByEither = rules.containsKey( start ) || scannedEnd != null;
        final Shape region = region( start );
        if ( region == Shape.ESCAPED ) {
            return region;
        }
        if ( commentStripDisagrees( start, end, readByEither ) ) {
            return Shape.UNREADABLE;
        }
        final boolean inBodyCode = region == Shape.UNREADABLE;
        if ( !inBodyCode && DirectiveSpelling.isDeny( text, start, end )
                && ( region != Shape.IN_FRONTMATTER || !frontmatterCode.contains( start ) ) ) {
            // A DENY in any case or spacing locks wherever it is outside code: an ALLOW so spelled is enforced, so a
            // DENY so spelled may not be ignored (the ACL model is allow-only, so no reading of it is faithful).
            return Shape.DENY;
        }
        if ( region != null ) {
            return readByEither ? region : null;
        }
        final Shape spelling = DirectiveSpelling.of( text, start, end, lineEnds );
        if ( spelling == Shape.DENY || spelling == Shape.ALLOW_PREFIXED_WORD ) {
            return readByEither ? spelling : null;     // a longer first word, [{Denylist}]: a plugin name, or prose
        }
        return spelling == Shape.BAD_PERMISSION || spelling == Shape.UNREADABLE ? spelling : ruleShape( start, end, scannedEnd, spelling );
    }

    /** A candidate in body prose spelled as a rule ({@code spelling} {@code null} when canonically). */
    private Shape ruleShape( final int start, final int end, final Integer scannedEnd, final Shape spelling ) {
        final RenderPathAccessRules.Rule label = labels.get( start );
        if ( label == null || label.end() != end ) {
            return Shape.UNREADABLE;     // a rule shape the render parse does not see as one label over this span
        }
        if ( spelling == Shape.WRAPPED && DirectiveSpelling.splitsAName( label.ruleLine() ) ) {
            return Shape.WRAPPED_NAME;
        }
        if ( spelling != null ) {
            return spelling;
        }
        // Spelled canonically: a rule only where both old readings read it over the same span.
        return rules.containsKey( start ) && scannedEnd != null && scannedEnd == end ? Shape.CANONICAL : Shape.UNREADABLE;
    }

    /**
     * Whether removing the body's comments, as the render does, changes the block structure at a span either reading takes
     * for a rule (a comment-only line becomes blank, so an indented rule after it is code to the render, which then shows
     * it): the two readings disagree, so the page locks.
     */
    private boolean commentStripDisagrees( final int start, final int end, final boolean readByEither ) {
        if ( comments == null || start < bodyStart || comments.contains( start )
                || !comments.renderDisagrees( start, code.contains( start ) ) ) {
            return false;
        }
        return readByEither || comments.renderReadsARule( start ) || DirectiveSpelling.isDeny( text, start, end );
    }

    /**
     * @param start the start of a span {@link #classify} read as a rule
     * @return its rule line in canonical form, from its label as the parse reads it
     */
    String ruleLine( final int start ) {
        return DirectiveSpelling.canonical( labels.get( start ).ruleLine() );
    }

    /**
     * The shape a candidate's region gives it, or {@code null} in body prose. Code is {@link Shape#UNREADABLE}: the render
     * path calls it code, so only a text scan that read a rule there makes it a span (a {@code DENY} there never does).
     */
    private Shape region( final int start ) {
        if ( start < bodyStart ) {
            return Shape.IN_FRONTMATTER;
        }
        if ( code.contains( start ) ) {
            return Shape.UNREADABLE;
        }
        if ( math.contains( start ) ) {
            return Shape.IN_MATH;
        }
        if ( html.contains( start ) ) {
            return Shape.IN_HTML_BLOCK;
        }
        if ( links.contains( start ) ) {
            return Shape.LINK_WITH_TARGET;
        }
        return DirectiveSpelling.escaped( text, start ) ? Shape.ESCAPED : null;
    }
}

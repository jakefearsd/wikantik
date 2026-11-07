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

import com.wikantik.markdown.extensions.math.DisplayMathPreProcessor;

import java.util.Arrays;
import java.util.List;

/**
 * The text {@link MarkdownParser} hands flexmark for a page body, and the way back from a position in it to the body.
 * One place, so the parser and {@link RenderPathAccessRules} (which must see exactly what the parser sees) can never
 * disagree.
 *
 * <p>Two rewrites, in this order:
 * <ol>
 *   <li><b>Plugin syntax.</b> Every <code>[{…}]</code> not already followed by {@code (} gets {@code ()} appended, so
 *       flexmark reads {@code [{Plugin}]}, {@code [{ALLOW view Admin}]}, {@code [{$var}]}, {@code [{SET k=v}]} as inline
 *       links rather than link references (which conflict with the Attributes extension). The rule is the expression
 *       {@code (\[\{[^}]+\}\])(?!\()} with {@code replaceAll( "$1()" )}, done by hand in linear time: the expression is
 *       quadratic on many openers before a closer, and this now also runs on every read of a page that holds an access
 *       rule. A differential test pins the two together.</li>
 *   <li><b>Display math.</b> {@link DisplayMathPreProcessor} turns {@code $$…$$} blocks into {@code ```math} fences.</li>
 * </ol>
 */
final class MarkdownBodySource {

    private static final String OPENER = "[{";
    private static final String INSERTED = "()";

    private MarkdownBodySource() {
    }

    /**
     * The prepared text and the edits that produced it.
     *
     * @param text       what flexmark parses
     * @param insertions body offsets at which {@code ()} was inserted, ascending (each right after a <code>}]</code>)
     * @param mathEdits  the display-math rewrites, in the coordinates of the plugin-normalised text
     */
    record Prepared( String text, int[] insertions, List< DisplayMathPreProcessor.Edit > mathEdits ) {

        /**
         * The body offset that {@code pos} (a position in {@link #text}) came from: inserted {@code ()} maps to its
         * insertion point and display-math content to its own character; a position inside generated math-fence markup
         * has no source and maps to {@code -1}.
         */
        int toBody( final int pos ) {
            final int normalized = fromMath( pos );
            return normalized < 0 ? -1 : fromNormalized( normalized );
        }

        /**
         * Where each display-math ({@code $$}) block the rewrite turned into a fence stands in the body, as
         * {@code {start, end}}: the whole block, its delimiters included.
         */
        List< int[] > displayMathInBody() {
            final List< int[] > blocks = new java.util.ArrayList<>( mathEdits.size() );
            for ( final DisplayMathPreProcessor.Edit edit : mathEdits ) {
                if ( edit.sourceEnd() > edit.sourceStart() ) {
                    blocks.add( new int[]{ fromNormalized( edit.sourceStart() ), fromNormalized( edit.sourceEnd() - 1 ) + 1 } );
                }
            }
            return blocks;
        }

        /**
         * The body span of a parsed fenced block that one display-math rewrite generated, or {@code null} when none did.
         * It starts where the rewritten block starts (its opener line, indent included) and ends where the parse ended the
         * fence: the block's closing delimiter, or where its container (a list item) ended. The rewrite finds its blocks with
         * the same block structure (#128), so the two agree; the span is read off the parse all the same.
         *
         * @param nodeStart start of the fenced block in {@link #text}
         * @param nodeEnd   its exclusive end in {@link #text}
         */
        int[] displayMathNodeInBody( final int nodeStart, final int nodeEnd ) {
            final DisplayMathPreProcessor.Edit edit = editAt( nodeStart );
            if ( edit == null || nodeStart >= edit.targetContent() || edit.sourceEnd() <= edit.sourceStart() ) {   // not at a generated opener
                return null;
            }
            final int start = fromNormalized( edit.sourceStart() );
            final int whole = fromNormalized( edit.sourceEnd() - 1 ) + 1;
            if ( nodeEnd >= edit.targetEnd() ) {
                return new int[]{ start, whole };
            }
            final int end = toBodyEnd( nodeEnd );
            if ( end > start ) {
                return new int[]{ start, end };
            }
            return nodeEnd >= edit.targetContent() + edit.contentLength() ? new int[]{ start, whole } : null;
        }

        /** The edit whose generated text holds {@code pos} (a position in {@link #text}), or {@code null}. */
        private DisplayMathPreProcessor.Edit editAt( final int pos ) {
            int lo = 0;
            int hi = mathEdits.size() - 1;
            while ( lo <= hi ) {
                final int mid = ( lo + hi ) >>> 1;
                final DisplayMathPreProcessor.Edit edit = mathEdits.get( mid );
                if ( pos < edit.targetStart() ) {
                    hi = mid - 1;
                } else if ( pos >= edit.targetEnd() ) {
                    lo = mid + 1;
                } else {
                    return edit;
                }
            }
            return null;
        }

        /** Binary search: the last edit that starts at or before {@code pos} decides; generated markup gives -1. */
        private int fromMath( final int pos ) {
            int lo = 0;
            int hi = mathEdits.size() - 1;
            int last = -1;
            while ( lo <= hi ) {
                final int mid = ( lo + hi ) >>> 1;
                if ( mathEdits.get( mid ).targetStart() <= pos ) {
                    last = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if ( last < 0 ) {
                return pos;
            }
            final DisplayMathPreProcessor.Edit edit = mathEdits.get( last );
            if ( pos >= edit.targetEnd() ) {
                return pos - ( edit.targetEnd() - edit.sourceEnd() );
            }
            final int inContent = pos - edit.targetContent();
            return inContent >= 0 && inContent <= edit.contentLength() ? edit.sourceContent() + inContent : -1;
        }

        /**
         * The body offset of the exclusive end {@code pos} (a position in {@link #text}): just past the body character of
         * the character before it — or, when that character is an inserted {@code ()}, the insertion point itself, which
         * has nothing of the body after it to step past (a body ending in <code>[{…}]</code> must not map one past its
         * end). {@code -1} when the character before is generated math-fence markup.
         */
        int toBodyEnd( final int pos ) {
            if ( pos <= 0 ) {
                return 0;
            }
            final int normalized = fromMath( pos - 1 );
            if ( normalized < 0 ) {
                return -1;
            }
            final int last = lastInsertionAtOrBefore( normalized );
            return isInserted( last, normalized ) ? insertions[ last ] : fromNormalized( normalized ) + 1;
        }

        /** Binary search: insertion k sits at {@code insertions[k] + 2k} in the normalised text. */
        private int fromNormalized( final int pos ) {
            final int last = lastInsertionAtOrBefore( pos );
            if ( isInserted( last, pos ) ) {
                return insertions[ last ];
            }
            return pos - INSERTED.length() * ( last + 1 );
        }

        /** The last insertion that starts at or before normalised position {@code pos}, or -1. */
        private int lastInsertionAtOrBefore( final int pos ) {
            int lo = 0;
            int hi = insertions.length - 1;
            int last = -1;
            while ( lo <= hi ) {
                final int mid = ( lo + hi ) >>> 1;
                if ( normalizedStart( mid ) <= pos ) {
                    last = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return last;
        }

        /** Whether normalised position {@code pos} is a character of insertion {@code k}'s {@code ()}. */
        private boolean isInserted( final int k, final int pos ) {
            return k >= 0 && pos < normalizedStart( k ) + INSERTED.length();
        }

        private int normalizedStart( final int k ) {
            return insertions[ k ] + INSERTED.length() * k;
        }
    }

    /** The body as flexmark should parse it. */
    static Prepared prepare( final String body ) {
        final int[] insertions = insertionPoints( body );
        final String normalized = insertAt( body, insertions );
        final DisplayMathPreProcessor.Tracked math = normalized.isEmpty()
                ? new DisplayMathPreProcessor.Tracked( normalized, List.of() )
                : DisplayMathPreProcessor.transformTracked( normalized );
        return new Prepared( math.text(), insertions, math.edits() );
    }

    /**
     * Where {@code (\[\{[^}]+\}\])(?!\()}'s {@code replaceAll( "$1()" )} inserts {@code ()}: after each match, the
     * matches found left to right without overlapping. A match at an opener runs to the first {@code }} after it (the
     * class refuses {@code }}, so backtracking cannot find another), needs at least one character before it, a
     * {@code ]} right after it and no {@code (} after that; a failed opener is retried one character on.
     */
    static int[] insertionPoints( final String body ) {
        int[] points = new int[ 4 ];
        int count = 0;
        int closeBrace = -1;
        int from = 0;
        while ( true ) {
            final int opener = body.indexOf( OPENER, from );
            if ( opener < 0 ) {
                break;
            }
            if ( closeBrace < opener + OPENER.length() ) {
                closeBrace = body.indexOf( '}', opener + OPENER.length() );   // monotone: each region is scanned once
            }
            if ( closeBrace < 0 ) {
                break;
            }
            final int end = closeBrace + 2;
            if ( closeBrace > opener + OPENER.length() && end <= body.length() && body.charAt( closeBrace + 1 ) == ']'
                    && !( end < body.length() && body.charAt( end ) == '(' ) ) {
                if ( count == points.length ) {
                    points = Arrays.copyOf( points, count * 2 );
                }
                points[ count++ ] = end;
                from = end;
            } else {
                from = opener + 1;
            }
        }
        return Arrays.copyOf( points, count );
    }

    private static String insertAt( final String body, final int[] insertions ) {
        if ( insertions.length == 0 ) {
            return body;
        }
        final StringBuilder out = new StringBuilder( body.length() + INSERTED.length() * insertions.length );
        int copied = 0;
        for ( final int at : insertions ) {
            out.append( body, copied, at ).append( INSERTED );
            copied = at;
        }
        return out.append( body, copied, body.length() ).toString();
    }
}

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Obsidian {@code %%comments%%} read off the render's own parse: where a comment opens and closes is decided with the
 * code and math {@link ReaderStructure} finds in the page text, not with a hand-written scan. A {@code %%} with either
 * of its characters in code or math opens and closes nothing; otherwise the closing {@code %%} is the next free one
 * after the opener, and an unterminated opener stays literal.
 *
 * <p>The opaque spans are the structure's {@code code()} and {@code renderedMath()}: display math as the render draws it
 * (a list item can end the block early) plus inline math. They are not {@code math()}, the wider reading kept for locking
 * access rules.
 *
 * <p>When the parse is cut off or fails, comments are still hidden, read without code or math: hiding a comment that
 * should have stayed is the safe side. That is logged once per call.
 */
public final class ReaderComments {

    private static final Logger LOG = LogManager.getLogger( ReaderComments.class );
    private static final String MARKER = "%%";

    private ReaderComments() {
    }

    /**
     * @param body page body; may be {@code null}
     * @return each comment's {@code [from, to)} in body coordinates, delimiters included, in order; no parse is paid for
     *         when the body holds no {@code %%}
     */
    public static List< int[] > ranges( final String body ) {
        return pairs( body, () -> ReaderStructure.of( body, 0 ), 0 );
    }

    /**
     * As {@link #ranges(String)} for a caller that already holds the structure of that same body, so the body is not
     * parsed a second time.
     *
     * @param body      page body; may be {@code null}
     * @param structure {@link ReaderStructure#of} of exactly this body, parsed with {@code bodyStart}
     * @param bodyStart the body's offset in the page text, which {@code structure}'s ranges include (those are subtracted)
     * @return each comment's {@code [from, to)} in body coordinates, as {@link #ranges(String)} returns them
     */
    public static List< int[] > ranges( final String body, final RenderPathAccessRules.Structure structure, final int bodyStart ) {
        return pairs( body, () -> structure, bodyStart );
    }

    /**
     * @param body page body; may be {@code null}
     * @return the body without its comments, each replaced by the newlines it held; the same instance when none was removed
     */
    public static String strip( final String body ) {
        return strip( body, ranges( body ) );
    }

    /**
     * @param body   page body
     * @param ranges {@link #ranges(String)} of that same body, already computed
     * @return the body without those ranges, each replaced by the newlines it held; the same instance when {@code ranges} is empty
     */
    public static String strip( final String body, final List< int[] > ranges ) {
        if ( ranges.isEmpty() ) {
            return body;
        }
        final StringBuilder out = new StringBuilder( body.length() );
        int copied = 0;
        for ( final int[] r : ranges ) {
            out.append( body, copied, r[ 0 ] );
            for ( int p = r[ 0 ]; p < r[ 1 ]; p++ ) {
                if ( body.charAt( p ) == '\n' ) {
                    out.append( '\n' );
                }
            }
            copied = r[ 1 ];
        }
        return out.append( body, copied, body.length() ).toString();
    }

    /** As {@link #ranges(String)} with an explicit parse budget (a test seam). */
    static List< int[] > ranges( final String body, final long budget ) {
        return pairs( body, () -> ReaderStructure.of( body, 0, budget ), 0 );
    }

    private static List< int[] > pairs( final String body, final java.util.function.Supplier< RenderPathAccessRules.Structure > parse,
                                              final int bodyStart ) {
        if ( body == null || body.indexOf( MARKER ) < 0 ) {
            return List.of();
        }
        List< int[] > opaque;
        try {
            final RenderPathAccessRules.Structure structure = parse.get();
            opaque = merged( structure.code(), structure.renderedMath(), bodyStart );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            LOG.warn( "comment boundaries read without code/math: the parse of a {}-character body failed: {}", body.length(), e.getMessage() );
            opaque = List.of();
        }
        final List< int[] > out = new ArrayList<>();
        int at = next( body, opaque, 0 );
        while ( at >= 0 ) {
            final int close = next( body, opaque, at + MARKER.length() );
            if ( close < 0 ) {
                break;
            }
            out.add( new int[]{ at, close + MARKER.length() } );
            at = next( body, opaque, close + MARKER.length() );
        }
        return out;
    }

    /**
     * Both lists' spans in start order, overlapping ones joined, so a binary search by position is sound; each moved
     * {@code bodyStart} characters back, into body coordinates.
     */
    private static List< int[] > merged( final List< int[] > code, final List< int[] > math, final int bodyStart ) {
        final List< int[] > all = new ArrayList<>( code.size() + math.size() );
        for ( final List< int[] > spans : List.of( code, math ) ) {
            for ( final int[] span : spans ) {
                all.add( new int[]{ span[ 0 ] - bodyStart, span[ 1 ] - bodyStart } );
            }
        }
        all.sort( Comparator.comparingInt( r -> r[ 0 ] ) );
        final List< int[] > out = new ArrayList<>( all.size() );
        for ( final int[] span : all ) {
            final int[] last = out.isEmpty() ? null : out.get( out.size() - 1 );
            if ( last != null && span[ 0 ] <= last[ 1 ] ) {
                last[ 1 ] = Math.max( last[ 1 ], span[ 1 ] );
            } else {
                out.add( new int[]{ span[ 0 ], span[ 1 ] } );
            }
        }
        return out;
    }

    /** The next {@code %%} at or after {@code from} with neither character in an opaque span, or -1. */
    private static int next( final String body, final List< int[] > opaque, final int from ) {
        int at = body.indexOf( MARKER, from );
        while ( at >= 0 && ( inside( opaque, at ) || inside( opaque, at + 1 ) ) ) {
            at = body.indexOf( MARKER, at + 1 );
        }
        return at;
    }

    /** Binary search over spans sorted by start: whether {@code pos} lies in one (spans of one kind do not overlap). */
    private static boolean inside( final List< int[] > opaque, final int pos ) {
        int lo = 0;
        int hi = opaque.size() - 1;
        while ( lo <= hi ) {
            final int mid = ( lo + hi ) >>> 1;
            final int[] span = opaque.get( mid );
            if ( pos < span[ 0 ] ) {
                hi = mid - 1;
            } else if ( pos >= span[ 1 ] ) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }
}

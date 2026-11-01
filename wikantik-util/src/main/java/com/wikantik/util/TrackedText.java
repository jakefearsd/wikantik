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
package com.wikantik.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A text made from a <em>source</em> text by copying parts of it, in order, and putting literals between them — and the
 * way back from every position in it to the source. The map is built by the edit itself ({@link Builder}), never by
 * comparing the two strings, and an edit of a tracked text is tracked back to the first source: a chain of edits
 * composes.
 *
 * <p>A copied character maps to its own source position; an inserted one has no source of its own and maps to its
 * <em>anchor</em>, the source position right after what was copied before it. {@link #toSource} answers for a position
 * where something starts (the character there), {@link #toSourceEnd} for an exclusive end (just past the character
 * before it), so a range {@code [from, to)} of this text maps to {@code [toSource(from), toSourceEnd(to))} and the parts
 * of the source cut out right before or after it stay outside.
 *
 * <p>Immutable; positions are checked, and a position out of range is an {@link IndexOutOfBoundsException}.
 */
public final class TrackedText {

    private final String text;
    private final int sourceLength;
    /** Where this text begins in the source: what {@code toSourceEnd( 0 )} gives. */
    private final int sourceStart;
    /** Segment {@code i} is {@code text[ textStarts[i], textStarts[i+1] )} (the last one runs to the end). */
    private final int[] textStarts;
    /** A copied segment's first source position; an inserted segment's anchor. */
    private final int[] sources;
    private final boolean[] copied;

    private TrackedText( final String text, final int sourceLength, final int sourceStart, final int[] textStarts,
                         final int[] sources, final boolean[] copied ) {
        this.text = text;
        this.sourceLength = sourceLength;
        this.sourceStart = sourceStart;
        this.textStarts = textStarts;
        this.sources = sources;
        this.copied = copied;
    }

    /** The source itself, unedited: every position maps to itself. {@link #text()} is {@code source}, the same instance. */
    public static TrackedText of( final String source ) {
        Objects.requireNonNull( source, "source" );
        return source.isEmpty()
                ? new TrackedText( source, 0, 0, new int[ 0 ], new int[ 0 ], new boolean[ 0 ] )
                : new TrackedText( source, source.length(), 0, new int[]{ 0 }, new int[]{ 0 }, new boolean[]{ true } );
    }

    /**
     * A text that is only {@code literal}, put at source position {@code anchor} of {@code sameSource}'s source (to be
     * {@link #concat concatenated} with parts of it).
     */
    public static TrackedText literal( final String literal, final TrackedText sameSource, final int anchor ) {
        Objects.checkIndex( anchor, sameSource.sourceLength + 1 );
        if ( literal.isEmpty() ) {
            return new TrackedText( literal, sameSource.sourceLength, anchor, new int[ 0 ], new int[ 0 ], new boolean[ 0 ] );
        }
        return new TrackedText( literal, sameSource.sourceLength, anchor, new int[]{ 0 }, new int[]{ anchor }, new boolean[]{ false } );
    }

    /**
     * The parts one after another; each keeps its own map. They must all be tracked back to the same source (checked by
     * its length).
     */
    public static TrackedText concat( final TrackedText... parts ) {
        if ( parts.length == 0 ) {
            throw new IllegalArgumentException( "nothing to concatenate" );
        }
        final Builder out = new Builder( parts[ 0 ].sourceLength, parts[ 0 ].sourceStart, null );
        for ( final TrackedText part : parts ) {
            if ( part.sourceLength != out.sourceLength ) {
                throw new IllegalArgumentException( "parts of different sources (" + part.sourceLength + " and "
                        + out.sourceLength + " characters)" );
            }
            out.append( part, 0, part.length() );
        }
        return out.build();
    }

    /** The text. */
    public String text() {
        return text;
    }

    /** The text's length. */
    public int length() {
        return text.length();
    }

    /** The length of the source this text is tracked back to. */
    public int sourceLength() {
        return sourceLength;
    }

    /**
     * Where the character at {@code pos} came from: its source position when it was copied, its anchor when it was
     * inserted. {@code pos == length()} is the end of the text: {@link #toSourceEnd toSourceEnd( length() )}.
     */
    public int toSource( final int pos ) {
        Objects.checkIndex( pos, text.length() + 1 );
        if ( pos == text.length() ) {
            return toSourceEnd( pos );
        }
        final int i = segmentAt( pos );
        return copied[ i ] ? sources[ i ] + ( pos - textStarts[ i ] ) : sources[ i ];
    }

    /**
     * The source position of the exclusive end {@code end}: just past the source character of the character before it
     * (its anchor, when that one was inserted); for {@code 0}, where this text begins in the source.
     */
    public int toSourceEnd( final int end ) {
        Objects.checkIndex( end, text.length() + 1 );
        if ( end == 0 ) {
            return sourceStart;
        }
        final int i = segmentAt( end - 1 );
        return copied[ i ] ? sources[ i ] + ( end - 1 - textStarts[ i ] ) + 1 : sources[ i ];
    }

    /** Whether the character at {@code pos} was copied from the source (rather than inserted). */
    public boolean isCopied( final int pos ) {
        Objects.checkIndex( pos, text.length() );
        return copied[ segmentAt( pos ) ];
    }

    /** Whether some of the source was cut out right before position {@code pos} (an insertion is no cut). */
    public boolean cutBefore( final int pos ) {
        return toSourceEnd( pos ) < toSource( pos );
    }

    /**
     * The source ranges {@code [start, end)} the copied characters of {@code text[from, to)} came from, in order, each
     * as long as it can be. What was cut out of the source between them is not in any of them, and inserted characters
     * have none.
     */
    public List< int[] > sourceSpans( final int from, final int to ) {
        Objects.checkFromToIndex( from, to, text.length() );
        final List< int[] > out = new ArrayList<>();
        if ( from == to ) {
            return out;
        }
        for ( int i = segmentAt( from ); i < textStarts.length && textStarts[ i ] < to; i++ ) {
            if ( !copied[ i ] ) {
                continue;
            }
            final int a = Math.max( from, textStarts[ i ] );
            final int b = Math.min( to, segmentEnd( i ) );
            final int start = sources[ i ] + ( a - textStarts[ i ] );
            final int end = start + ( b - a );
            final int[] last = out.isEmpty() ? null : out.get( out.size() - 1 );
            if ( last != null && last[ 1 ] == start ) {
                last[ 1 ] = end;
            } else {
                out.add( new int[]{ start, end } );
            }
        }
        return out;
    }

    /** A builder of a new text from this one, beginning at its start. */
    public Builder builder() {
        return builder( 0 );
    }

    /** A builder of a new text from this one whose first literal, before any copy, is anchored at position {@code from}. */
    public Builder builder( final int from ) {
        return new Builder( sourceLength, toSourceEnd( from ), this );
    }

    private int segmentEnd( final int i ) {
        return i + 1 < textStarts.length ? textStarts[ i + 1 ] : text.length();
    }

    /** The segment holding {@code pos} ({@code 0 <= pos < length()}): the last one starting at or before it. */
    private int segmentAt( final int pos ) {
        int lo = 0;
        int hi = textStarts.length - 1;
        while ( lo < hi ) {
            final int mid = ( lo + hi + 1 ) >>> 1;
            if ( textStarts[ mid ] <= pos ) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public String toString() {
        return "TrackedText[" + text.length() + " of " + sourceLength + " chars, " + textStarts.length + " segments]";
    }

    /**
     * Builds a text from parts of a base text ({@link #copy}) and literals ({@link #insert}), in order. Every part keeps
     * the base's map back to the source, so the result is tracked to the base's source.
     */
    public static final class Builder {
        private final int sourceLength;
        private final int sourceStart;
        private final TrackedText base;
        private final StringBuilder text = new StringBuilder();
        private int[] textStarts = new int[ 4 ];
        private int[] sources = new int[ 4 ];
        private boolean[] copied = new boolean[ 4 ];
        private int count;
        /** Where a literal inserted now is anchored: just past the source of what was copied last. */
        private int cursor;

        private Builder( final int sourceLength, final int sourceStart, final TrackedText base ) {
            this.sourceLength = sourceLength;
            this.sourceStart = sourceStart;
            this.base = base;
            this.cursor = sourceStart;
        }

        /** Appends {@code base.text()[from, to)}, mapped as the base maps it. */
        public Builder copy( final int from, final int to ) {
            Objects.checkFromToIndex( from, to, base.length() );
            append( base, from, to );
            return this;
        }

        /** Appends {@code literal}, anchored just past what was copied last. */
        public Builder insert( final String literal ) {
            if ( !literal.isEmpty() ) {
                add( text.length(), cursor, false );
                text.append( literal );
            }
            return this;
        }

        /** The text built so far. */
        public TrackedText build() {
            return new TrackedText( text.toString(), sourceLength, sourceStart, Arrays.copyOf( textStarts, count ),
                    Arrays.copyOf( sources, count ), Arrays.copyOf( copied, count ) );
        }

        private void append( final TrackedText from, final int start, final int end ) {
            if ( start == end ) {
                return;
            }
            for ( int i = from.segmentAt( start ); i < from.textStarts.length && from.textStarts[ i ] < end; i++ ) {
                final int a = Math.max( start, from.textStarts[ i ] );
                final int b = Math.min( end, from.segmentEnd( i ) );
                add( text.length(), from.copied[ i ] ? from.sources[ i ] + ( a - from.textStarts[ i ] ) : from.sources[ i ],
                        from.copied[ i ] );
                text.append( from.text, a, b );
            }
            cursor = from.toSourceEnd( end );
        }

        /** Starts a segment at text position {@code at}, or extends the last one when it continues it. */
        private void add( final int at, final int source, final boolean isCopied ) {
            if ( count > 0 && copied[ count - 1 ] == isCopied ) {
                final int length = at - textStarts[ count - 1 ];
                if ( isCopied ? sources[ count - 1 ] + length == source : sources[ count - 1 ] == source ) {
                    return;
                }
            }
            if ( count == textStarts.length ) {
                textStarts = Arrays.copyOf( textStarts, count * 2 );
                sources = Arrays.copyOf( sources, count * 2 );
                copied = Arrays.copyOf( copied, count * 2 );
            }
            textStarts[ count ] = at;
            sources[ count ] = source;
            copied[ count ] = isCopied;
            count++;
        }
    }
}

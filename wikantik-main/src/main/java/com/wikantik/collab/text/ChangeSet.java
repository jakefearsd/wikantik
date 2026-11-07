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
package com.wikantik.collab.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An immutable port of {@code @codemirror/state}'s {@code ChangeSet}, held to the library by the shared corpus
 * {@code wikantik-frontend/src/collab/__fixtures__/changesets.json} (replayed by {@code ChangeSetConformanceTest}).
 * The corpus records the {@code @codemirror/state} version it was generated with (its {@code library} field);
 * {@code package.json} allows any compatible {@code ^6.7.x}, so regenerate the corpus after an upgrade.
 *
 * <p>The representation is CodeMirror's own, so the port follows {@code change.ts} function by function:
 * {@code sections} holds {@code (len, ins)} pairs, where {@code len} is the length of the span in the old document
 * and {@code ins} is {@code -1} for a kept span or the length of the replacement text otherwise; {@code inserted}
 * holds the replacement text per section index (empty where there is none). {@code composeSets} and {@code mapSet}
 * live in {@link ComposeSets} and {@link MapSet}, {@code SectionIter} in {@link SectionIter}, and
 * {@code addSection}/{@code addInsert} in {@link SectionBuilder}. Lengths and positions are UTF-16 code units, which
 * are Java {@code String} indices. Text is LF-only: a line break in inserted text is one {@code \n}.</p>
 *
 * <p>Where CodeMirror throws {@code RangeError}/{@code Error} this throws {@link IllegalArgumentException}; it also
 * checks set lengths up front in {@link #compose} and {@link #map}, where CodeMirror would hand back a set of the wrong
 * length when one side is empty.</p>
 */
public final class ChangeSet {

    /** CodeMirror's {@code DefaultSplit}: every line-break form is one break. */
    private static final Pattern LINE_BREAK = Pattern.compile( "\r\n?|\n" );

    /** The {@code ins} of a kept span. */
    static final int KEPT = -1;

    private final int[] sections;
    private final String[] inserted;

    ChangeSet( final int[] sections, final String[] inserted ) {
        this.sections = sections;
        this.inserted = inserted;
    }

    /** An identity set over a document of {@code length}. */
    public static ChangeSet empty( final int length ) {
        if ( length < 0 ) {
            throw new IllegalArgumentException( "Negative document length " + length );
        }
        return new ChangeSet( length == 0 ? new int[ 0 ] : new int[] { length, KEPT }, new String[ 0 ] );
    }

    /**
     * One replacement of {@code [from, to)} by {@code insert} in a document of {@code length}: the set
     * {@code ChangeSet.of({from, to, insert}, length)} builds. As in CodeMirror, {@code \r\n} and {@code \r} in
     * {@code insert} are line breaks and become {@code \n}. A {@code null} insert inserts nothing.
     */
    public static ChangeSet replace( final int length, final int from, final int to, final String insert ) {
        if ( length < 0 || from < 0 || from > to || to > length ) {
            throw new IllegalArgumentException(
                    "Invalid change range " + from + " to " + to + " (in doc of length " + length + ")" );
        }
        final String text = insert == null ? "" : LINE_BREAK.matcher( insert ).replaceAll( "\n" );
        if ( from == to && text.isEmpty() ) {
            return empty( length );
        }
        final SectionBuilder b = new SectionBuilder();
        b.addSection( from, KEPT, false );
        b.addSection( to - from, text.length(), false );
        b.addInsert( text );
        b.addSection( length - to, KEPT, false );
        return b.build();
    }

    /** The length of the document before the change. */
    public int length() {
        int result = 0;
        for ( int i = 0; i < sections.length; i += 2 ) {
            result += sections[ i ];
        }
        return result;
    }

    /** The length of the document after the change. */
    public int newLength() {
        int result = 0;
        for ( int i = 0; i < sections.length; i += 2 ) {
            final int ins = sections[ i + 1 ];
            result += ins < 0 ? sections[ i ] : ins;
        }
        return result;
    }

    /** False when there are actual changes in this set. */
    public boolean empty() {
        return sections.length == 0 || sections.length == 2 && sections[ 1 ] < 0;
    }

    /**
     * Applies the changes to {@code doc}.
     *
     * @throws IllegalArgumentException when {@code doc} is not exactly {@link #length()} long
     */
    public String apply( final String doc ) {
        Objects.requireNonNull( doc, "doc" );
        if ( doc.length() != length() ) {
            throw new IllegalArgumentException( "Applying change set to a document with the wrong length (set length "
                    + length() + ", document length " + doc.length() + ")" );
        }
        final StringBuilder out = new StringBuilder( newLength() );
        int posA = 0;
        for ( int i = 0; i < sections.length; i += 2 ) {
            final int len = sections[ i ];
            final int ins = sections[ i + 1 ];
            if ( ins < 0 ) {
                out.append( doc, posA, posA + len );
            } else if ( ins > 0 ) {
                out.append( insertedAt( i >> 1 ) );
            }
            posA += len;
        }
        return out.toString();
    }

    /**
     * The combined effect of applying {@code next} after this set ({@code docA → docC} for this {@code docA → docB}
     * and {@code next} {@code docB → docC}).
     *
     * @throws IllegalArgumentException when {@code next} does not start in the document this set produces
     */
    public ChangeSet compose( final ChangeSet next ) {
        if ( newLength() != next.length() ) {
            throw new IllegalArgumentException( "Mismatched change set lengths: composing a set of length "
                    + next.length() + " after one producing " + newLength() );
        }
        return empty() ? next : next.empty() ? this : new ComposeSets( this, next ).run();
    }

    /** {@link #map(ChangeSet, boolean)} with {@code before = false}: {@code other} is treated as coming first. */
    public ChangeSet map( final ChangeSet other ) {
        return map( other, false );
    }

    /**
     * Maps this set, which starts in the same document as {@code other}, over {@code other} so it can be applied after
     * it. When {@code before} is true, insertions at the same position order as if this set came first.
     *
     * @throws IllegalArgumentException when the two sets do not start in documents of the same length
     */
    public ChangeSet map( final ChangeSet other, final boolean before ) {
        if ( length() != other.length() ) {
            throw new IllegalArgumentException( "Mismatched change set lengths: mapping a set of length " + length()
                    + " over one of length " + other.length() );
        }
        return other.empty() ? this : new MapSet( this, other, before ).run();
    }

    /**
     * Maps a position in the old document to the new one ({@code MapMode.Simple}). {@code assoc} below zero keeps a
     * position at an insertion before the inserted text; otherwise it moves after it.
     *
     * @throws IllegalArgumentException when {@code pos} is outside {@code [0, length()]}
     */
    public int mapPos( final int pos, final int assoc ) {
        if ( pos < 0 ) {
            throw new IllegalArgumentException( "Position " + pos + " is out of range" );
        }
        int posA = 0;
        int posB = 0;
        for ( int i = 0; i < sections.length; i += 2 ) {
            final int len = sections[ i ];
            final int ins = sections[ i + 1 ];
            final int endA = posA + len;
            if ( ins < 0 ) {
                if ( endA > pos ) {
                    return posB + ( pos - posA );
                }
                posB += len;
            } else {
                if ( endA > pos || endA == pos && assoc < 0 && len == 0 ) {
                    return pos == posA || assoc < 0 ? posB : posB + ins;
                }
                posB += ins;
            }
            posA = endA;
        }
        if ( pos > posA ) {
            throw new IllegalArgumentException( "Position " + pos + " is out of range for changeset of length " + posA );
        }
        return posB;
    }

    /**
     * The {@code {fromA, toA}} range of each change in the old document, ascending. Adjacent changes are one range
     * (CodeMirror's {@code iterChangedRanges} with {@code individual = false}); an insertion is an empty range.
     */
    public List< int[] > touchedRanges() {
        final List< int[] > ranges = new ArrayList<>();
        int posA = 0;
        int i = 0;
        while ( i < sections.length ) {
            final int len = sections[ i ];
            final boolean kept = sections[ i + 1 ] < 0;
            i += 2;
            if ( kept ) {
                posA += len;
                continue;
            }
            int endA = posA + len;
            while ( i < sections.length && sections[ i + 1 ] >= 0 ) {
                endA += sections[ i ];
                i += 2;
            }
            ranges.add( new int[] { posA, endA } );
            posA = endA;
        }
        return ranges;
    }

    // ---- package-private views for the algorithms, ChangeSetJson and CollabText ----

    /** The raw {@code (len, ins)} pairs; never modified by callers. */
    int[] rawSections() {
        return sections;
    }

    int sectionCount() {
        return sections.length >> 1;
    }

    int sectionLength( final int index ) {
        return sections[ index << 1 ];
    }

    int sectionInsert( final int index ) {
        return sections[ ( index << 1 ) + 1 ];
    }

    /** The text inserted by section {@code index}; empty when it inserts none. */
    String insertedAt( final int index ) {
        return index < inserted.length ? inserted[ index ] : "";
    }

    @Override
    public boolean equals( final Object o ) {
        if ( this == o ) {
            return true;
        }
        if ( !( o instanceof ChangeSet other ) || !Arrays.equals( sections, other.sections ) ) {
            return false;
        }
        for ( int i = 0; i < sectionCount(); i++ ) {
            if ( sectionInsert( i ) > 0 && !insertedAt( i ).equals( other.insertedAt( i ) ) ) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode( sections );
        for ( int i = 0; i < sectionCount(); i++ ) {
            if ( sectionInsert( i ) > 0 ) {
                h = 31 * h + insertedAt( i ).hashCode();
            }
        }
        return h;
    }

    /** The JSON form, as {@code ChangeSet.toJSON()} would serialise it. */
    @Override
    public String toString() {
        return ChangeSetJson.toJson( this ).toString();
    }
}

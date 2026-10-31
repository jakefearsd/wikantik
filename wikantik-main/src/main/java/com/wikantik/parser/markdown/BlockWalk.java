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

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.TextBase;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.markdown.extensions.callouts.CalloutBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single definition of what an addressable block is, how blocks are numbered, and where their {@code ^id} markers
 * sit — over a parsed flexmark tree, with no knowledge of source offsets. {@link BlockIds} maps its result back to the
 * raw page text; the render post-processor ({@code BlockIdPostProcessor}) stamps it onto the HTML as {@code id} /
 * {@code data-block}. Both use this walk, so a rendered ordinal always equals the {@link BlockIds.Block#index()} of the
 * same block.
 *
 * <p>Public only because the render post-processor lives in another package; it is not an API for anything else.
 * The tree must be parsed from {@link MarkdownBodySource}-prepared text with {@link MarkdownDocument#structuralOptions()}
 * (callouts already built, inline math parsed), and walked <b>before</b> any post-processor that changes block
 * structure further (the native-wikilink embed replacement).
 *
 * <p>Marker forms ({@link Form}):
 * <ul>
 *   <li>{@link Form#INLINE}: whitespace + {@code ^id} ending the last text node of a paragraph;</li>
 *   <li>{@link Form#LAST_LINE}: {@code ^id} alone on the <em>last line</em> of a multi-line paragraph, after a soft or
 *       hard line break ({@code "Para\n^id"}). The line break is the whitespace the marker needs, as in Obsidian, so it
 *       tags that paragraph like an inline marker. Separated by a blank line instead ({@code "Para\n\n^id"}) it is a
 *       paragraph of its own: standalone.</li>
 *   <li>{@link Form#STANDALONE}: a paragraph that is only {@code ^id}; it tags the block right before it in the same
 *       container, or is an orphan when there is none.</li>
 * </ul>
 */
public final class BlockWalk {

    /** How a marker sits in the tree. */
    public enum Form { INLINE, LAST_LINE, STANDALONE }

    /**
     * A marker as found in the tree.
     *
     * @param id        the id, without {@code ^}
     * @param form      how it sits
     * @param paragraph the paragraph holding it
     * @param text      the text node holding it
     * @param caret     offset of its {@code ^} in the parsed text (orders markers as the source does)
     */
    public record Marker( String id, Form form, Paragraph paragraph, Text text, int caret ) {
    }

    /**
     * A numbered block.
     *
     * @param node      its node
     * @param kind      what it is
     * @param marker    the marker tagging it, or {@code null} (a losing duplicate still has its marker here)
     * @param textLine  the paragraph whose last line can carry an inline marker for it (the paragraph itself, or a list
     *                  item's first paragraph), or {@code null} when it can't take one
     */
    public record Entry( Node node, BlockIds.Kind kind, Marker marker, Paragraph textLine ) {
    }

    /** What a walk found: the blocks in ordinal order, orphan markers, and which markers win their id. */
    public static final class Result {
        private final List< Entry > entries;
        private final List< Marker > orphans;
        private final List< Marker > markers;
        private final Set< Marker > winners;

        private Result( final List< Entry > entries, final List< Marker > orphans ) {
            this.entries = List.copyOf( entries );
            this.orphans = List.copyOf( orphans );
            final List< Marker > all = new ArrayList<>( orphans );
            for ( final Entry e : entries ) {
                if ( e.marker() != null ) {
                    all.add( e.marker() );
                }
            }
            all.sort( Comparator.comparingInt( Marker::caret ) );
            this.markers = List.copyOf( all );
            this.winners = Collections.newSetFromMap( new IdentityHashMap<>() );
            final Set< String > seen = new HashSet<>();
            for ( final Marker m : all ) {
                if ( seen.add( m.id() ) ) {
                    winners.add( m );
                }
            }
        }

        /** The blocks; an entry's position is its ordinal. */
        public List< Entry > entries() {
            return entries;
        }

        /** Standalone markers with no block before them to tag. */
        public List< Marker > orphans() {
            return orphans;
        }

        /** Every marker, in source order. */
        public List< Marker > markers() {
            return markers;
        }

        /** Whether {@code m} is the first marker of its id in source order (orphans included): the one that keeps it. */
        public boolean wins( final Marker m ) {
            return winners.contains( m );
        }

        /** The id {@code e} carries: its marker's, when that marker wins; else {@code null}. */
        public String idOf( final Entry e ) {
            return e.marker() != null && wins( e.marker() ) ? e.marker().id() : null;
        }
    }

    private BlockWalk() {
    }

    /**
     * Numbers the addressable blocks under {@code root} in pre-order and finds their markers. Read-only: the tree is
     * not changed.
     */
    public static Result walk( final Node root ) {
        final Numbering numbering = new Numbering();
        numbering.number( root );
        return new Result( numbering.entries, numbering.orphans );
    }

    /**
     * Takes every marker of {@code result} out of the tree: inline ones are trimmed from their text (a last-line one
     * with the line break before it), standalone paragraphs are unlinked — or, for an orphan, replaced by what
     * {@code orphanAnchor} gives (unlinked when it gives {@code null}).
     */
    public static void detach( final Result result, final Function< Marker, Node > orphanAnchor ) {
        final Set< Marker > orphanSet = Collections.newSetFromMap( new IdentityHashMap<>() );
        orphanSet.addAll( result.orphans() );
        for ( final Marker m : result.markers() ) {
            switch ( m.form() ) {
                case INLINE -> Markers.trimInline( m.text() );
                case LAST_LINE -> Markers.removeLastLine( m );
                case STANDALONE -> {
                    final Node anchor = orphanSet.contains( m ) ? orphanAnchor.apply( m ) : null;
                    if ( anchor != null ) {
                        m.paragraph().insertBefore( anchor );
                    }
                    m.paragraph().unlink();
                }
            }
        }
    }

    // ---- numbering ----------------------------------------------------------------------------------------------------

    /** One walk's state: the blocks so far, orphan markers, and the standalone markers already claimed. */
    private static final class Numbering {
        private final List< Entry > entries = new ArrayList<>();
        private final List< Marker > orphans = new ArrayList<>();
        /** Standalone marker paragraphs already taken by the block before them. */
        private final Set< Node > claimed = Collections.newSetFromMap( new IdentityHashMap<>() );

        /** Visits every node under {@code root} in pre-order (iteratively: nesting depth is unbounded in a page). */
        void number( final Node root ) {
            final Deque< Node > stack = new ArrayDeque<>();
            pushChildren( stack, root );
            while ( !stack.isEmpty() ) {
                final Node node = stack.pop();
                if ( visit( node ) ) {
                    pushChildren( stack, node );
                }
            }
        }

        private static void pushChildren( final Deque< Node > stack, final Node parent ) {
            for ( Node c = parent.getLastChild(); c != null; c = c.getPrevious() ) {
                stack.push( c );
            }
        }

        /** Records {@code node} if it is a block; returns whether its children can hold blocks. */
        private boolean visit( final Node node ) {
            return switch ( node ) {
                case Paragraph p -> {
                    paragraph( p );
                    yield false;
                }
                case ListItem item -> {
                    listItem( item );
                    yield true;
                }
                case ListBlock list -> {
                    if ( Markers.standaloneAfter( list ) != null ) {
                        entries.add( tagged( list, BlockIds.Kind.LIST ) );
                    }
                    yield true;
                }
                case TableBlock table -> {
                    entries.add( tagged( table, BlockIds.Kind.TABLE ) );
                    yield false;
                }
                case CalloutBlock callout -> {
                    entries.add( tagged( callout, BlockIds.Kind.CALLOUT ) );
                    yield true;
                }
                case BlockQuote quote -> {
                    entries.add( tagged( quote, BlockIds.Kind.QUOTE ) );
                    yield true;
                }
                case FencedCodeBlock fence -> {
                    final boolean math = "math".equals( fence.getInfo().toString().trim() );
                    entries.add( tagged( fence, math ? BlockIds.Kind.MATH : BlockIds.Kind.CODE ) );
                    yield false;
                }
                case IndentedCodeBlock code -> {
                    entries.add( tagged( code, BlockIds.Kind.CODE ) );
                    yield false;
                }
                default -> true;
            };
        }

        private void paragraph( final Paragraph p ) {
            if ( Markers.standaloneId( p ) != null ) {
                if ( !claimed.contains( p ) ) {
                    orphans.add( Markers.standalone( p ) );
                }
                return;
            }
            if ( p.getParent() instanceof ListItem item && item.getFirstChild() == p ) {
                return;   // the list item is the block
            }
            final Marker inline = Markers.inline( p );
            entries.add( new Entry( p, BlockIds.Kind.PARAGRAPH, inline != null ? inline : claimNext( p ), p ) );
        }

        private void listItem( final ListItem item ) {
            final Paragraph first = item.getFirstChild() instanceof Paragraph fp && Markers.standaloneId( fp ) == null ? fp : null;
            Marker marker = null;
            if ( first != null ) {
                final Marker inline = Markers.inline( first );
                marker = inline != null ? inline : claimNext( first );
            }
            entries.add( new Entry( item, BlockIds.Kind.LIST_ITEM, marker, first ) );
        }

        /** A block that only a standalone marker right after it can tag. */
        private Entry tagged( final Node node, final BlockIds.Kind kind ) {
            return new Entry( node, kind, claimNext( node ), null );
        }

        /** The standalone marker right after {@code node} in its container, now claimed by it; or {@code null}. */
        private Marker claimNext( final Node node ) {
            final Paragraph next = Markers.standaloneAfter( node );
            if ( next == null ) {
                return null;
            }
            claimed.add( next );
            return Markers.standalone( next );
        }
    }

    // ---- markers ------------------------------------------------------------------------------------------------------

    /** Finding markers in paragraph text, and taking them out. */
    private static final class Markers {

        /** An inline marker at the end of a paragraph's last text node. */
        private static final Pattern INLINE = Pattern.compile( "(?<=[ \\t])\\^([A-Za-z0-9-]+)[ \\t]*\\r?$" );

        /** A text node that is only a marker. */
        private static final Pattern ALONE = Pattern.compile( "^\\^([A-Za-z0-9-]+)[ \\t]*\\r?$" );

        private Markers() {
        }

        static Paragraph standaloneAfter( final Node node ) {
            return node.getNext() instanceof Paragraph p && standaloneId( p ) != null ? p : null;
        }

        /**
         * The id of a paragraph that is only a marker, else {@code null}. Read from its one {@link Text} child, not its
         * chars: a callout's body paragraph still spans the title line in its chars.
         */
        static String standaloneId( final Paragraph p ) {
            if ( !( p.getFirstChild() instanceof Text t ) || t != p.getLastChild() ) {
                return null;
            }
            final Matcher m = ALONE.matcher( t.getChars() );
            return m.matches() ? m.group( 1 ) : null;
        }

        static Marker standalone( final Paragraph p ) {
            final Text t = ( Text ) p.getFirstChild();
            return new Marker( standaloneId( p ), Form.STANDALONE, p, t, t.getStartOffset() );
        }

        /** The inline or last-line marker ending {@code p}, or {@code null}. */
        static Marker inline( final Paragraph p ) {
            final Text last = lastText( p );
            if ( last == null ) {
                return null;
            }
            final Matcher m = INLINE.matcher( last.getChars() );
            if ( m.find() ) {
                return new Marker( m.group( 1 ), Form.INLINE, p, last, last.getStartOffset() + m.start() );
            }
            final Matcher alone = ALONE.matcher( last.getChars() );
            if ( last.getParent() == p && isLineBreak( last.getPrevious() ) && alone.matches() ) {
                return new Marker( alone.group( 1 ), Form.LAST_LINE, p, last, last.getStartOffset() );
            }
            return null;
        }

        static void trimInline( final Text text ) {
            final Matcher matcher = INLINE.matcher( text.getChars() );
            if ( matcher.find() ) {
                text.setChars( text.getChars().subSequence( 0, matcher.start() ).trimEnd() );
            }
        }

        /** Removes a last-line marker with the line break before it, and the spaces that break left on the line. */
        static void removeLastLine( final Marker m ) {
            final Node lineBreak = m.text().getPrevious();
            m.text().unlink();
            if ( lineBreak != null ) {
                lineBreak.unlink();
            }
            if ( m.paragraph().getLastChild() instanceof Text t ) {
                t.setChars( t.getChars().trimEnd() );
            }
        }

        private static boolean isLineBreak( final Node n ) {
            return n instanceof SoftLineBreak || n instanceof HardLineBreak;
        }

        private static Text lastText( final Node parent ) {
            Node last = parent.getLastChild();
            while ( last instanceof TextBase ) {
                last = last.getLastChild();
            }
            return last instanceof Text t ? t : null;
        }
    }
}

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

import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.markdown.extensions.callouts.CalloutTitle;
import com.wikantik.markdown.extensions.math.InlineMathParser;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockIds.Kind;
import com.wikantik.util.TrackedText;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How {@link BlockIds} reads a page: strip its access rules and comments as a render does ({@link Shown}), parse, number the blocks
 * with {@link BlockWalk}, settle duplicate ids, compute plain text, and map every position back to the raw text
 * ({@link Coords}). Package-private; {@link BlockIds} is the API.
 */
final class BlockModel {

    private static final Parser PARSER = FlexmarkParsers.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();

    /** Test seam: how many times this thread has parsed a page here (per thread, so other tests' engines can't count). */
    private static final ThreadLocal< long[] > PARSES = ThreadLocal.withInitial( () -> new long[ 1 ] );

    private BlockModel() {
    }

    /** Test seam: the pages parsed on this thread so far. */
    static long parsesOnThisThread() {
        return PARSES.get()[ 0 ];
    }

    /** Test seam: how many texts this thread has stripped of access rules here. */
    private static final ThreadLocal< long[] > STRIPS = ThreadLocal.withInitial( () -> new long[ 1 ] );

    /** Test seam: the texts stripped on this thread so far. */
    static long stripsOnThisThread() {
        return STRIPS.get()[ 0 ];
    }

    /** The cheap raw pre-check (see {@link BlockWalk#RAW_POSSIBLE_MARKER}). */
    static boolean rawMayHoldMarker( final String raw ) {
        return raw != null && raw.indexOf( '^' ) >= 0 && BlockWalk.RAW_POSSIBLE_MARKER.matcher( raw ).find();
    }


    /**
     * The text a render parses — {@code raw} with its access rules stripped ({@code DisplayText}), then its body's Obsidian
     * {@code %%comments%%} ({@code MarkdownParser}, newlines kept) — tracked back to {@code raw}. A position called
     * <em>shown</em> below indexes {@link #text()}; {@link #tracked()} takes it to {@code raw}. So a comment-only paragraph
     * is no block, a comment shifts no later ordinal, and a block or marker inside a comment is none — as the reader sees it.
     *
     * @param raw     the text as stored
     * @param tracked the stripped text and its map back to {@code raw}; {@code null} until needed, for a text that cannot
     *                hold a marker ({@link #rawMayHoldMarker} says no: not even stripped)
     * @param body    whether {@code raw} is a body only (no frontmatter: a {@code %%} anywhere in it is a comment marker)
     */
    record Shown( String raw, TrackedText tracked, boolean body ) {

        static Shown of( final String raw ) {
            return of( raw, false );
        }

        static Shown of( final String raw, final boolean body ) {
            return new Shown( raw, rawMayHoldMarker( raw ) ? strip( raw, body ) : null, body );
        }

        /** This, stripped if it was not yet. */
        Shown stripped() {
            return tracked != null ? this : new Shown( raw, strip( raw, body ), body );
        }

        private static TrackedText strip( final String raw, final boolean body ) {
            STRIPS.get()[ 0 ]++;
            return withoutComments( AclDirectiveText.stripTracked( raw ), body );
        }

        /** {@code text} without its body's comments, each replaced by the line breaks it held (copied, so they map back). */
        private static TrackedText withoutComments( final TrackedText text, final boolean body ) {
            final String s = text.text();
            if ( s.indexOf( "%%" ) < 0 ) {
                return text;
            }
            final int bodyStart = body ? 0 : FrontmatterParser.bodyOffset( s );
            final List< int[] > comments = ReaderComments.ranges( s.substring( bodyStart ) );
            if ( comments.isEmpty() ) {
                return text;
            }
            final TrackedText.Builder out = text.builder();
            int copied = 0;
            for ( final int[] c : comments ) {
                final int from = bodyStart + c[ 0 ];
                final int to = bodyStart + c[ 1 ];
                out.copy( copied, from );
                for ( int p = s.indexOf( '\n', from ); p >= 0 && p < to; p = s.indexOf( '\n', p + 1 ) ) {
                    out.copy( p, p + 1 );
                }
                copied = to;
            }
            return out.copy( copied, s.length() ).build();
        }

        String text() {
            return tracked.text();
        }

        /** The pre-check ({@link BlockWalk#POSSIBLE_MARKER}), on the very text the walk reads. */
        boolean mayHoldMarker() {
            return tracked != null && BlockWalk.POSSIBLE_MARKER.matcher( tracked.text() ).find();
        }
    }

    /**
     * A marker's range, in the shown text and in the raw one.
     *
     * @param id         the id it names
     * @param shownStart start in the shown text (with the blanks before it, as {@link Block#markerStart()} says)
     * @param shownEnd   end in the shown text (with the blanks after it)
     * @param caret      its {@code ^} in the shown text
     * @param start      see {@link Block#markerStart()}
     * @param end        see {@link Block#markerEnd()}
     */
    record Marker( String id, int shownStart, int shownEnd, int caret, int start, int end ) {
    }

    /**
     * A numbered block before ids are settled; positions in the shown text.
     *
     * @param node     its AST node
     * @param kind     its kind
     * @param start    start
     * @param end      end (trimmed)
     * @param marker   the marker addressing it, or {@code null}
     * @param inlineAt where an inline marker would go, or {@code -1} when it can't take one
     */
    record Entry( Node node, Kind kind, int start, int end, Marker marker, int inlineAt ) {
    }

    /** Everything one read of a page yields. */
    record Model( Shown shown, List< Entry > entries, List< Block > blocks, List< Marker > markers ) {
    }

    /** {@code fullText} read with every block's plain text. */
    static Model read( final String fullText ) {
        return read( Shown.of( fullText ), id -> true, false );
    }

    /** For a model whose block texts are never read: no block's plain text is worked out. */
    static final Predicate< String > NO_TEXT = id -> false;

    private static boolean isBlank( final char c ) {
        return c == ' ' || c == '\t';
    }


    /** The pre-check of a raw text (see {@link Shown#mayHoldMarker}). */
    static boolean mayHoldMarker( final String text ) {
        return Shown.of( text ).mayHoldMarker();
    }

    /** {@link #read(Shown, Predicate, boolean)} of a raw text. */
    static Model read( final String fullText, final Predicate< String > withText, final boolean body ) {
        return read( Shown.of( fullText, body ), withText, body );
    }

    /**
     * @param withText which blocks get their plain text worked out, by id (a block without one is asked with
     *                 {@code null}); the others get {@code ""}
     * @param body     whether the text is a body only: then a leading {@code ---} pair is content, not frontmatter
     */
    static Model read( final Shown given, final Predicate< String > withText, final boolean body ) {
        if ( given.raw() == null || given.raw().isEmpty() ) {
            return new Model( given, List.of(), List.of(), List.of() );
        }
        final Shown shown = given.stripped();
        final String text = shown.text();
        final int bodyStart = body ? 0 : FrontmatterParser.bodyOffset( text );
        final MarkdownBodySource.Prepared prepared = MarkdownBodySource.prepare( text.substring( bodyStart ) );
        final Node document = PARSER.parse( prepared.text() );
        PARSES.get()[ 0 ]++;
        final BlockWalk.Result walk = BlockWalk.walk( document );
        final Coords coords = new Coords( shown.tracked(), prepared, bodyStart );

        // Source ranges first, while the tree still holds every marker.
        final Map< BlockWalk.Marker, Marker > located = new IdentityHashMap<>();
        final List< Marker > markers = new ArrayList<>( walk.markers().size() );
        for ( final BlockWalk.Marker m : walk.markers() ) {   // source order
            final Marker r = coords.marker( m );
            located.put( m, r );
            markers.add( r );
        }
        final List< Entry > entries = new ArrayList<>( walk.entries().size() );
        for ( final BlockWalk.Entry e : walk.entries() ) {
            entries.add( coords.entry( e, e.marker() == null ? null : located.get( e.marker() ) ) );
        }

        // Take the markers out of the tree (as rendering does) so a block's plain text never carries one.
        BlockWalk.detach( walk, m -> null );
        final List< Block > blocks = blocks( coords, entries, walk, withText );
        return new Model( shown, List.copyOf( entries ), List.copyOf( blocks ), List.copyOf( markers ) );
    }

    private static List< Block > blocks( final Coords coords, final List< Entry > entries, final BlockWalk.Result walk,
                                         final Predicate< String > withText ) {
        final List< Block > blocks = new ArrayList<>( entries.size() );
        for ( int i = 0; i < entries.size(); i++ ) {
            final Entry e = entries.get( i );
            final Marker m = e.marker();
            final String id = walk.idOf( walk.entries().get( i ) );
            final int start = coords.rawStart( e.start() );
            blocks.add( new Block( i, id, e.kind(), start, Math.max( start, coords.rawEnd( e.end() ) ),
                    m == null ? -1 : m.start(), m == null ? -1 : m.end(), withText.test( id ) ? plainText( e.node() ) : "" ) );
        }
        return blocks;
    }

    private static String plainText( final Node node ) {
        return WikiLinkSyntax.toPlainText( collectText( node ) ).trim();
    }

    /**
     * The node's text, one line per child block. {@link TextCollectingVisitor} is only run on blocks without child
     * blocks: on a container (a list, an item with a nested list) whose text holds backslash escapes such as
     * {@code \$}, flexmark's space-inserting builder fails its own length assertion.
     */
    private static String collectText( final Node node ) {
        if ( node instanceof CalloutTitle ) {
            // Its inline nodes were moved in from the quote's first paragraph; its own chars are empty, which
            // TextCollectingVisitor cannot take. Collect them one by one, as CalloutBlock.titleText() does.
            final StringBuilder title = new StringBuilder();
            for ( Node c = node.getFirstChild(); c != null; c = c.getNext() ) {
                title.append( new TextCollectingVisitor().collectAndGetText( c ) );
            }
            return title.toString();
        }
        boolean container = false;
        for ( Node c = node.getFirstChild(); c != null && !container; c = c.getNext() ) {
            container = c instanceof com.vladsch.flexmark.util.ast.Block;   // not BlockIds.Block
        }
        if ( !container ) {
            return new TextCollectingVisitor().collectAndGetText( node );
        }
        final StringBuilder out = new StringBuilder();
        for ( Node c = node.getFirstChild(); c != null; c = c.getNext() ) {
            final String text = collectText( c );
            if ( !text.isBlank() ) {
                out.append( out.isEmpty() ? "" : "\n" ).append( text.strip() );
            }
        }
        return out.toString();
    }

    /** Positions in the parsed text, taken back to the shown text, and from there to the raw one. */
    private static final class Coords {
        private final TrackedText shown;
        private final String text;
        private final MarkdownBodySource.Prepared prepared;
        private final int bodyStart;

        Coords( final TrackedText shown, final MarkdownBodySource.Prepared prepared, final int bodyStart ) {
            this.shown = shown;
            this.text = shown.text();
            this.prepared = prepared;
            this.bodyStart = bodyStart;
        }

        /** The raw position of the shown position {@code pos} where something starts. */
        int rawStart( final int pos ) {
            return shown.toSource( pos );
        }

        /** The raw position of the shown exclusive end {@code end}: just past the raw character of the one before it. */
        int rawEnd( final int end ) {
            return shown.toSourceEnd( end );
        }

        int start( final Node node ) {
            return map( node.getStartOffset() );
        }

        /**
         * The marker's range: an inline (or last-line) one from the spaces and tabs before its {@code ^}; a standalone
         * one from its {@code ^}, which starts its line or follows its container's prefix — or, when an access rule was
         * stripped from its line right before those blanks ({@code [{ALLOW view Admin}] ^id}), from the blanks, so that
         * cutting it leaves the rule on its own.
         */
        Marker marker( final BlockWalk.Marker m ) {
            final int caret = caret( m.caret() );
            final int blanks = spaceBefore( caret );
            final boolean fromBlanks = m.form() != BlockWalk.Form.STANDALONE || blanks < caret && shown.cutBefore( blanks );
            final int start = fromBlanks ? blanks : caret;
            final int end = spaceAfter( caret + 1 + m.id().length() );
            final int rawStart = rawStart( start );
            return new Marker( m.id(), start, end, caret, rawStart, Math.max( rawStart, rawEnd( end ) ) );
        }

        /** The block's range, and where an inline marker for it would go. */
        Entry entry( final BlockWalk.Entry e, final Marker marker ) {
            final Node node = e.node();
            if ( e.kind() == Kind.PARAGRAPH ) {
                // From its first inline node: a callout's body paragraph keeps the title's text range in its chars.
                final int start = node.getFirstChild() != null ? start( node.getFirstChild() ) : start( node );
                return new Entry( node, e.kind(), start, end( node ), marker, lineEnd( node ) );
            }
            if ( node instanceof IndentedCodeBlock ) {
                // Its chars start after its four-column indent; that indent is what makes it code, so the block starts
                // before it (and what precedes that is its container's prefix).
                return new Entry( node, e.kind(), codeIndentStart( start( node ) ), end( node ), marker, -1 );
            }
            final int inlineAt = e.textLine() != null ? lineEnd( e.textLine() ) : -1;
            return new Entry( node, e.kind(), start( node ), end( node ), marker, inlineAt );
        }

        /** The node's end, trailing whitespace and line breaks excluded (never before its start). */
        int end( final Node node ) {
            final int start = start( node );
            int end = mapEnd( node.getEndOffset() );
            while ( end > start && Character.isWhitespace( text.charAt( end - 1 ) ) ) {
                end--;
            }
            return end;
        }

        /**
         * Where an inline marker for the paragraph {@code node} goes: at the end of its last line, before the spaces and
         * tabs that end it (a marker may be followed by those) but after any other trailing character — a marker
         * followed by a vertical tab or form feed would be none.
         */
        int lineEnd( final Node node ) {
            final int start = start( node );
            int end = mapEnd( node.getEndOffset() );
            while ( end > start && " \t\r\n".indexOf( text.charAt( end - 1 ) ) >= 0 ) {
                end--;
            }
            return end;
        }

        /**
         * The shown position of a {@code ^} found at {@code pos} of the parsed text. It is a character the strip copied
         * from the raw text: a marker the render sees is never made of text the strip wrote itself.
         */
        int caret( final int pos ) {
            final int body = prepared.toBody( pos );
            final int at = body < 0 ? -1 : bodyStart + body;
            if ( at < 0 || at >= text.length() || text.charAt( at ) != '^' || !shown.isCopied( at ) ) {
                throw new IllegalStateException( "Block marker at parsed offset " + pos + " maps to no ^ in the source (" + at + ")" );
            }
            return at;
        }

        int spaceBefore( final int pos ) {
            int p = pos;
            while ( p > 0 && isBlank( text.charAt( p - 1 ) ) ) {
                p--;
            }
            return p;
        }

        /** Back from an indented code block's first character over its own indent: four spaces, or a tab. */
        int codeIndentStart( final int pos ) {
            int p = pos;
            for ( int columns = 0; columns < 4 && p > 0 && isBlank( text.charAt( p - 1 ) ); columns++ ) {
                p--;
                if ( text.charAt( p ) == '\t' ) {
                    break;
                }
            }
            return p;
        }

        int spaceAfter( final int pos ) {
            int p = pos;
            while ( p < text.length() && isBlank( text.charAt( p ) ) ) {
                p++;
            }
            return p;
        }

        /**
         * The shown position of the parsed-text position {@code pos} where a block or marker starts. A position inside
         * generated {@code ```math} fence markup has no source of its own ({@link MarkdownBodySource.Prepared#toBody}
         * gives -1), so it is taken from its line: the rewrite starts at a line start and copies the indent before the
         * fence verbatim, so the offset within the line holds.
         */
        private int map( final int pos ) {
            final int direct = prepared.toBody( pos );
            if ( direct >= 0 ) {
                return direct + bodyStart;
            }
            final int lineStart = prepared.text().lastIndexOf( '\n', pos - 1 ) + 1;
            if ( lineStart == 0 ) {
                return bodyStart + pos;
            }
            final int lineBreak = prepared.toBody( lineStart - 1 );
            if ( lineBreak < 0 ) {
                throw new IllegalStateException( "Parsed offset " + pos + " maps to no source position" );
            }
            return bodyStart + lineBreak + 1 + ( pos - lineStart );
        }

        /**
         * The shown position of the exclusive end {@code pos}: one past its last character — the insertion point when
         * that is an inserted {@code ()}, which has no character of its own. When it is generated {@code ```math}
         * markup, the end of its line is used instead: a rewritten block ends exactly where its closing {@code $$} line
         * did.
         */
        private int mapEnd( final int pos ) {
            final int last = prepared.toBodyEnd( pos );
            if ( last >= 0 ) {
                return bodyStart + last;
            }
            final String parsed = prepared.text();
            final int nl = parsed.indexOf( '\n', pos - 1 );
            final int lineEnd = prepared.toBody( nl < 0 ? parsed.length() : nl );
            if ( lineEnd < 0 ) {
                throw new IllegalStateException( "Parsed end offset " + pos + " maps to no source position" );
            }
            return bodyStart + lineEnd;
        }
    }
}

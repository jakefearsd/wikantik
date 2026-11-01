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

import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.markdown.extensions.callouts.CalloutBlock;
import com.wikantik.markdown.extensions.callouts.CalloutTitle;
import com.wikantik.markdown.extensions.math.InlineMathParser;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server's model of a page's addressable blocks and their Obsidian-style {@code ^id} block markers
 * (spec {@code 2026-10-31-obsidian-block-references-design.md} §1.1, §1.3).
 *
 * <p>The page is read the way rendering reads it: frontmatter is skipped, the body goes through
 * {@link MarkdownBodySource#prepare} (plugin {@code ()} insertion, {@code $$…$$} → {@code ```math}), and is parsed with
 * {@link MarkdownDocument#structuralOptions()} plus inline {@code $…$} math. Every offset this class reports indexes the
 * <b>raw</b> text passed in, frontmatter included, so {@link #assign} and {@link #strip} can edit it directly.
 *
 * <p><b>Blocks</b>, numbered in pre-order (document order, a container before its contents): paragraphs (except a list
 * item's own first paragraph — the item is the block), list items, tables, block quotes ({@link CalloutBlock} gives
 * {@link Kind#CALLOUT}), fenced and indented code ({@code math} fences give {@link Kind#MATH}), and a list only when a
 * standalone marker addresses it. Headings are never blocks. Rendered ordinals ({@code data-block}) must equal
 * {@link Block#index()}, so the numbering lives in one place, {@link BlockWalk}, which rendering uses too.
 *
 * <p><b>Markers.</b> An inline marker is whitespace + {@code ^id} ending the last {@link Text} node of a paragraph (or of
 * a list item's first paragraph). A marker alone on the <b>last line</b> of a multi-line paragraph ({@code "Para\n^id"})
 * is inline as well: the line break is the whitespace it needs, as in Obsidian. A standalone marker is a paragraph that
 * is only {@code ^id} (so {@code "Para\n\n^id"}, after a blank line), and tags the block right before it in the same
 * container. Code spans, code blocks, inline and display math and headings never hold a marker, because the marker is
 * only looked for in paragraph text nodes. The first occurrence of an id in source order wins; later duplicates (and
 * standalone markers with nothing to tag) keep their marker range, so {@link #strip} still removes them, but carry no
 * id. (The editor's JavaScript mirror follows these same rules.)
 */
public final class BlockIds {

    /** What a block is. */
    public enum Kind { PARAGRAPH, LIST_ITEM, LIST, TABLE, QUOTE, CALLOUT, CODE, MATH }

    /**
     * One addressable block.
     *
     * @param index       position in {@link #list} order (the rendered {@code data-block} ordinal)
     * @param id          the block id without {@code ^}, or {@code null} when the block has none (or a duplicate)
     * @param kind        what the block is
     * @param start       start of the block's source in the full text
     * @param end         end of the block's source in the full text, trailing whitespace and line breaks excluded
     * @param markerStart start of the block's marker (the spaces and tabs before an inline {@code ^id} on its line, or
     *                    the {@code ^} of a standalone marker line), or {@code -1}
     * @param markerEnd   end of the marker, trailing spaces and tabs included, line break excluded; or {@code -1}
     * @param plainText   the block's text with markdown, wikilinks and markers reduced away, trimmed
     */
    public record Block( int index, String id, Kind kind, int start, int end, int markerStart, int markerEnd, String plainText ) {
    }

    /** A block id, without its {@code ^}. */
    public static final Pattern ID = Pattern.compile( "[A-Za-z0-9-]+" );

    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();

    private static final String NEW_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int NEW_ID_LENGTH = 6;
    private static final int FINGERPRINT_LENGTH = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Test seam: how many times this thread has parsed a page here (per thread, so other tests' engines can't count). */
    private static final ThreadLocal< long[] > PARSES = ThreadLocal.withInitial( () -> new long[ 1 ] );

    private BlockIds() {
    }

    /** Test seam: the pages parsed on this thread so far. */
    static long parsesOnThisThread() {
        return PARSES.get()[ 0 ];
    }

    // ---- public API ---------------------------------------------------------------------------------------------------

    /**
     * Every addressable block of the page, in pre-order.
     *
     * @param fullText raw page text, frontmatter included; may be {@code null}
     * @return the blocks; never {@code null}
     */
    public static List< Block > list( final String fullText ) {
        return model( fullText ).blocks();
    }

    /**
     * The block that carries {@code id} (case-sensitive; the first one in source order).
     *
     * @param fullText raw page text; may be {@code null}
     * @param id       the id without {@code ^}; may be {@code null}
     * @return the block, or empty
     */
    public static Optional< Block > find( final String fullText, final String id ) {
        return find( fullText, id, false );
    }

    /**
     * {@link #find} over a page <em>body</em> (frontmatter already split off): a leading {@code ---} pair in it is two
     * thematic breaks, never frontmatter. Offsets index {@code body}.
     */
    public static Optional< Block > findBody( final String body, final String id ) {
        return find( body, id, true );
    }

    private static Optional< Block > find( final String text, final String id, final boolean body ) {
        if ( id == null || !mayHoldMarker( text ) ) {
            return Optional.empty();
        }
        return ModelReader.model( text, id::equals, body ).blocks().stream().filter( b -> id.equals( b.id() ) ).findFirst();
    }

    /**
     * The block's source with its own marker removed (markers of blocks nested in it are kept), as markdown that stands
     * on its own: the prefix of the container it sits in ({@code "> "}, a list item's indent) is taken off its
     * continuation lines. A list item keeps its list marker and nested children.
     */
    public static String sourceOf( final String fullText, final Block b ) {
        String source = fullText.substring( b.start(), b.end() );
        final boolean cut = b.markerStart() >= b.start() && b.markerStart() < b.end();
        if ( cut ) {
            final int cutStart = b.markerStart() - b.start();
            final int cutEnd = Math.min( b.markerEnd(), b.end() ) - b.start();
            source = source.substring( 0, cutStart ) + source.substring( cutEnd );
        }
        source = Edits.withoutContainerPrefix( source,
                Edits.continuationPrefix( fullText.substring( Edits.lineStart( fullText, b.start() ), b.start() ) ) );
        return cut ? source.stripTrailing() : source;
    }

    /**
     * Gives block {@code b} the id {@code id}: as an inline {@code " ^id"} at the end of a paragraph or of a list item's
     * first line, otherwise as a standalone marker line after the block (with a blank line before it, inside the same
     * container). A block whose duplicate marker carries no id gets that marker replaced.
     *
     * @param fullText the raw text {@code b} was listed from
     * @param b        a block from {@code list( fullText )}
     * @param id       the new id, matching {@link #ID}
     * @return the edited text
     * @throws IllegalArgumentException when {@code id} is malformed, {@code b} is not a current block of {@code fullText},
     *                                  or the block already has an id or cannot take one
     * @throws IllegalStateException    when the edited text does not read back with {@code b} tagged {@code id}
     */
    public static String assign( final String fullText, final Block b, final String id ) {
        if ( id == null || !ID.matcher( id ).matches() ) {
            throw new IllegalArgumentException( "Not a block id: " + id );
        }
        final Model model = model( fullText );
        if ( b.index() < 0 || b.index() >= model.entries().size() || !model.blocks().get( b.index() ).equals( b ) ) {
            throw new IllegalArgumentException( "Block " + b.index() + " is not a current block of this text" );
        }
        if ( b.id() != null ) {
            throw new IllegalArgumentException( "Block " + b.index() + " already has id ^" + b.id() );
        }
        final String result = Edits.tag( fullText, b, model.entries().get( b.index() ), id );
        final List< Block > after = list( result );
        if ( b.index() >= after.size() || !id.equals( after.get( b.index() ).id() ) ) {
            throw new IllegalStateException( "Adding ^" + id + " to block " + b.index() + " (" + b.kind() + ") did not tag it" );
        }
        return result;
    }

    /**
     * The text with every block marker removed — inline, standalone, duplicate and orphan alike. The line count is kept:
     * a standalone marker's line keeps its line break (and any container prefix such as {@code "> "}).
     *
     * @param fullText raw page text; may be {@code null}
     * @return the stripped text; the very same instance when it holds no marker (a text in which no {@code ^} could be
     *         one is not even parsed)
     */
    public static String strip( final String fullText ) {
        return strip( fullText, false );
    }

    /**
     * {@link #strip} over a page <em>body</em> (frontmatter already split off, as excerpt, chunk, export and embed
     * callers hold it): a leading {@code ---} pair in it is two thematic breaks, never frontmatter, so markers inside
     * it are stripped too.
     *
     * @param body a page body; may be {@code null}
     * @return the stripped body; the very same instance when it holds no marker
     */
    public static String stripBody( final String body ) {
        return strip( body, true );
    }

    private static String strip( final String fullText, final boolean body ) {
        if ( !mayHoldMarker( fullText ) ) {
            return fullText;
        }
        final List< Marker > markers = ModelReader.model( fullText, NO_TEXT, body ).markers();
        if ( markers.isEmpty() ) {
            return fullText;
        }
        final StringBuilder out = new StringBuilder( fullText.length() );
        int copied = 0;
        for ( final Marker m : markers ) {
            out.append( fullText, copied, m.start() );
            copied = m.end();
        }
        return out.append( fullText, copied, fullText.length() ).toString();
    }

    /**
     * The id of every marker in the text — block ids, losing duplicates and orphans alike — so a new id can collide with
     * none of them.
     *
     * @param fullText raw page text; may be {@code null}
     * @return the ids; never {@code null}
     */
    public static Set< String > markerIds( final String fullText ) {
        final Set< String > ids = new java.util.HashSet<>();
        if ( !mayHoldMarker( fullText ) ) {
            return ids;
        }
        for ( final Marker m : ModelReader.model( fullText, NO_TEXT, false ).markers() ) {
            ids.add( m.id() );
        }
        return ids;
    }

    /** A fresh id: {@value #NEW_ID_LENGTH} chars of {@code [a-z0-9]}, not in {@code existing} (which may be {@code null}). */
    public static String newId( final Set< String > existing ) {
        while ( true ) {
            final StringBuilder sb = new StringBuilder( NEW_ID_LENGTH );
            for ( int i = 0; i < NEW_ID_LENGTH; i++ ) {
                sb.append( NEW_ID_CHARS.charAt( RANDOM.nextInt( NEW_ID_CHARS.length() ) ) );
            }
            final String id = sb.toString();
            if ( existing == null || !existing.contains( id ) ) {
                return id;
            }
        }
    }

    /** The text lowercased, reduced to letters and digits, first {@value #FINGERPRINT_LENGTH} chars ("" for {@code null}). */
    public static String fingerprint( final String text ) {
        if ( text == null ) {
            return "";
        }
        final String reduced = text.toLowerCase( Locale.ROOT ).replaceAll( "[^\\p{L}\\p{N}]", "" );
        return reduced.length() <= FINGERPRINT_LENGTH ? reduced : reduced.substring( 0, FINGERPRINT_LENGTH );
    }

    /** Whether a wikilink heading part addresses a block: it starts with {@code ^}. */
    public static boolean isBlockRef( final String heading ) {
        return heading != null && heading.startsWith( "^" );
    }

    /** The block id of a {@code ^}-heading, without the {@code ^} ({@code null} stays {@code null}). */
    public static String idOf( final String heading ) {
        return isBlockRef( heading ) ? heading.substring( 1 ) : heading;
    }

    // ---- the model ----------------------------------------------------------------------------------------------------

    /**
     * A marker's range in the full text.
     *
     * @param id    the id it names
     * @param start see {@link Block#markerStart()}
     * @param end   see {@link Block#markerEnd()}
     */
    private record Marker( String id, int start, int end ) {
    }

    /**
     * A numbered block before ids are settled.
     *
     * @param node     its AST node
     * @param kind     its kind
     * @param start    full-text start
     * @param end      full-text end (trimmed)
     * @param marker   the marker addressing it, or {@code null}
     * @param inlineAt where an inline marker would go (full text), or {@code -1} when it can't take one
     */
    private record Entry( Node node, Kind kind, int start, int end, Marker marker, int inlineAt ) {
    }

    /** Everything one read of a page yields. */
    private record Model( List< Entry > entries, List< Block > blocks, List< Marker > markers ) {
    }

    private static final Model EMPTY = new Model( List.of(), List.of(), List.of() );

    private static Model model( final String fullText ) {
        return ModelReader.model( fullText, id -> true, false );
    }

    /** For a model whose block texts are never read: no block's plain text is worked out. */
    private static final java.util.function.Predicate< String > NO_TEXT = id -> false;

    /**
     * Whether a marker could be in the text at all: a {@code ^id} after a line start, a blank, a block-quote
     * {@code >} or an access rule's closing {@code ]} / {@code )}, and at the end of its line or before an access rule.
     * A superset of every marker form, so a text without one (math superscripts, footnotes) is never parsed for markers.
     */
    private static final Pattern POSSIBLE_MARKER = Pattern.compile( "(?m)(?:^|[\\s>\\])])\\^[A-Za-z0-9-]+[ \\t]*(?:\\r?$|\\[\\{)" );

    private static boolean mayHoldMarker( final String text ) {
        return text != null && text.indexOf( '^' ) >= 0 && POSSIBLE_MARKER.matcher( text ).find();
    }

    private static boolean isBlank( final char c ) {
        return c == ' ' || c == '\t';
    }

    /** Reads a page into its {@link Model}: parse, number, settle duplicate ids, compute plain text. */
    private static final class ModelReader {
        private ModelReader() {
        }

        /**
         * @param withText which blocks get their plain text worked out, by id (a block without one is asked with
         *                 {@code null}); the others get {@code ""}
         * @param body     whether the text is a body only: then a leading {@code ---} pair is content, not frontmatter
         */
        static Model model( final String fullText, final java.util.function.Predicate< String > withText,
                            final boolean body ) {
            if ( fullText == null || fullText.isEmpty() ) {
                return EMPTY;
            }
            final int bodyStart = body ? 0 : FrontmatterParser.bodyOffset( fullText );
            final String source = fullText.substring( bodyStart );
            MarkdownBodySource.Prepared prepared = MarkdownBodySource.prepare( source );
            Node document = PARSER.parse( prepared.text() );
            PARSES.get()[ 0 ]++;
            final String emptied = EmptyRuleItems.blank( source, prepared, document );
            if ( emptied != null ) {   // read those list items as empty, as the render does: parse again
                prepared = MarkdownBodySource.prepare( emptied );
                document = PARSER.parse( prepared.text() );
            }
            final BlockWalk.Result walk = BlockWalk.walk( document );
            final Coords coords = new Coords( fullText, prepared, bodyStart );

            // Source ranges first, while the tree still holds every marker.
            final Map< BlockWalk.Marker, Marker > raw = new IdentityHashMap<>();
            final List< Marker > markers = new ArrayList<>( walk.markers().size() );
            for ( final BlockWalk.Marker m : walk.markers() ) {   // source order
                final Marker r = coords.marker( m );
                raw.put( m, r );
                markers.add( r );
            }
            final List< Entry > entries = new ArrayList<>( walk.entries().size() );
            for ( final BlockWalk.Entry e : walk.entries() ) {
                entries.add( coords.entry( e, e.marker() == null ? null : raw.get( e.marker() ) ) );
            }

            // Take the markers out of the tree (as rendering does) so a block's plain text never carries one.
            BlockWalk.detach( walk, m -> null );
            // ... and the access-rule links, which a render strips before it parses.
            final List< Node > ruleLinks = new ArrayList<>();
            for ( final Node n : document.getDescendants() ) {
                if ( BlockWalk.isAccessRuleLink( n ) ) {
                    ruleLinks.add( n );
                }
            }
            ruleLinks.forEach( Node::unlink );
            final List< Block > blocks = new ArrayList<>( entries.size() );
            for ( int i = 0; i < entries.size(); i++ ) {
                final Entry e = entries.get( i );
                final Marker m = e.marker();
                final String id = walk.idOf( walk.entries().get( i ) );
                blocks.add( new Block( i, id, e.kind(), e.start(), e.end(), m == null ? -1 : m.start(),
                        m == null ? -1 : m.end(), withText.test( id ) ? plainText( e.node() ) : "" ) );
            }
            return new Model( List.copyOf( entries ), List.copyOf( blocks ), List.copyOf( markers ) );
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
    }

    /**
     * List items whose first line holds nothing but access rules ({@code - [{ALLOW view Admin}]}). The render strips
     * those rules before it parses, which leaves an <em>empty</em> list item ({@code - }) — and an empty item parses
     * differently: it cannot interrupt a paragraph, and a blank line after it ends it. To number blocks as the render does,
     * the raw text is parsed again with those rules blanked out (spaces, so every offset stays where it is).
     */
    private static final class EmptyRuleItems {
        private EmptyRuleItems() {
        }

        /** {@code body} with the rules of such item lines turned into spaces, or {@code null} when there are none. */
        static String blank( final String body, final MarkdownBodySource.Prepared prepared, final Node document ) {
            char[] out = null;
            for ( final Node n : document.getDescendants() ) {
                if ( !( n instanceof ListItem item ) || !( item.getFirstChild() instanceof Paragraph first )
                        || !onlyRulesOnFirstLine( prepared.text(), item, first ) ) {
                    continue;
                }
                for ( Node c = first.getFirstChild(); c != null && !isLineBreak( c ); c = c.getNext() ) {
                    final int from = prepared.toBody( c.getStartOffset() );
                    final int to = prepared.toBody( c.getEndOffset() );
                    if ( BlockWalk.isAccessRuleLink( c ) && from >= 0 && to > from && body.indexOf( '\n', from ) >= to ) {
                        out = out == null ? body.toCharArray() : out;
                        java.util.Arrays.fill( out, from, to, ' ' );
                    }
                }
            }
            return out == null ? null : new String( out );
        }

        /** Whether {@code first} starts on the item's marker line and that line holds only access rules and blanks. */
        private static boolean onlyRulesOnFirstLine( final String text, final ListItem item, final Paragraph first ) {
            if ( text.substring( item.getStartOffset(), first.getStartOffset() ).indexOf( '\n' ) >= 0 ) {
                return false;
            }
            boolean rule = false;
            for ( Node c = first.getFirstChild(); c != null && !isLineBreak( c ); c = c.getNext() ) {
                if ( BlockWalk.isAccessRuleLink( c ) ) {
                    rule = true;
                } else if ( !( c instanceof Text t && t.getChars().isBlank() ) ) {
                    return false;
                }
            }
            return rule;
        }

        private static boolean isLineBreak( final Node n ) {
            return n instanceof SoftLineBreak || n instanceof HardLineBreak;
        }
    }

    // ---- editing ------------------------------------------------------------------------------------------------------

    /** Text edits: adding a marker, and taking a nested block's source out of its container. */
    private static final class Edits {
        private Edits() {
        }

        /** {@code fullText} with {@code b} (whose numbered entry is {@code entry}) tagged {@code id}. */
        static String tag( final String fullText, final Block b, final Entry entry, final String id ) {
            if ( entry.marker() != null ) {   // a duplicate: its marker carries no id, so its ^id is replaced
                final int caret = fullText.indexOf( '^', b.markerStart() );
                return fullText.substring( 0, caret ) + "^" + id + fullText.substring( b.markerEnd() );
            }
            if ( entry.inlineAt() >= 0 ) {
                return fullText.substring( 0, entry.inlineAt() ) + " ^" + id + fullText.substring( entry.inlineAt() );
            }
            if ( b.kind() == Kind.LIST_ITEM ) {
                throw new IllegalArgumentException( "List item " + b.index() + " has no text line to carry an id" );
            }
            return withStandaloneMarker( fullText, b, id );
        }

        /** Standalone insertion of {@code ^id} after a block that cannot take an inline marker. */
        private static String withStandaloneMarker( final String text, final Block b, final String id ) {
            final String prefix = markerPrefix( text, b );
            final String blank = prefix.stripTrailing();
            final int nl = text.indexOf( '\n', b.end() );
            if ( nl < 0 ) {
                final String eol = text.contains( "\r\n" ) ? "\r\n" : "\n";
                return text + eol + blank + eol + prefix + "^" + id;
            }
            final String eol = nl > 0 && text.charAt( nl - 1 ) == '\r' ? "\r\n" : "\n";
            final int at = nl + 1;
            final StringBuilder insert = new StringBuilder().append( blank ).append( eol ).append( prefix ).append( '^' ).append( id ).append( eol );
            if ( !isBlankLine( text, at ) ) {
                insert.append( blank ).append( eol );   // keep the marker its own paragraph, apart from what follows
            }
            return text.substring( 0, at ) + insert + text.substring( at );
        }

        /** The prefix a marker line needs to sit in {@code b}'s container ({@code "> "}, a list item's indent). */
        private static String markerPrefix( final String text, final Block b ) {
            return continuationPrefix( text.substring( lineStart( text, b.start() ), b.start() ) );
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

    /** Positions in the parsed text, taken back to the full text. */
    private static final class Coords {
        private final String full;
        private final MarkdownBodySource.Prepared prepared;
        private final int bodyStart;

        Coords( final String full, final MarkdownBodySource.Prepared prepared, final int bodyStart ) {
            this.full = full;
            this.prepared = prepared;
            this.bodyStart = bodyStart;
        }

        int start( final Node node ) {
            return map( node.getStartOffset() );
        }

        /**
         * The marker's range in the full text: an inline one from the spaces and tabs before its {@code ^}, and so a
         * standalone one that shares its line with an access rule ({@code [{ALLOW view Admin}] ^id}); a standalone one
         * that starts its line keeps its container's prefix.
         */
        Marker marker( final BlockWalk.Marker m ) {
            final int caret = caret( m.caret() );
            final boolean ownLine = m.form() == BlockWalk.Form.STANDALONE
                    && ( m.text().getPrevious() == null || m.text().getPrevious() instanceof SoftLineBreak
                         || m.text().getPrevious() instanceof HardLineBreak );
            final int start = ownLine ? caret : spaceBefore( caret );
            return new Marker( m.id(), start, spaceAfter( caret + 1 + m.id().length() ) );
        }

        /** The block's range in the full text, and where an inline marker for it would go. */
        Entry entry( final BlockWalk.Entry e, final Marker marker ) {
            final Node node = e.node();
            if ( e.kind() == Kind.PARAGRAPH ) {
                final int end = end( node );
                // From its first inline node: a callout's body paragraph keeps the title's text range in its chars.
                final int start = node.getFirstChild() != null ? start( node.getFirstChild() ) : start( node );
                return new Entry( node, e.kind(), start, end, marker, end );
            }
            if ( node instanceof IndentedCodeBlock ) {
                // Its chars start after its four-column indent; that indent is what makes it code, so the block starts
                // before it (and what precedes that is its container's prefix).
                return new Entry( node, e.kind(), codeIndentStart( start( node ) ), end( node ), marker, -1 );
            }
            final int inlineAt = e.textLine() != null ? end( e.textLine() ) : -1;
            return new Entry( node, e.kind(), start( node ), end( node ), marker, inlineAt );
        }

        /** The node's end, trailing whitespace and line breaks excluded (never before its start). */
        int end( final Node node ) {
            final int start = start( node );
            int end = mapEnd( node.getEndOffset() );
            while ( end > start && Character.isWhitespace( full.charAt( end - 1 ) ) ) {
                end--;
            }
            return end;
        }

        /** The full-text position of a {@code ^} found at {@code pos} of the parsed text. */
        int caret( final int pos ) {
            final int at = map( pos );
            if ( at >= full.length() || full.charAt( at ) != '^' ) {
                throw new IllegalStateException( "Block marker at parsed offset " + pos + " maps to no ^ in the source (" + at + ")" );
            }
            return at;
        }

        int spaceBefore( final int pos ) {
            int p = pos;
            while ( p > 0 && isBlank( full.charAt( p - 1 ) ) ) {
                p--;
            }
            return p;
        }

        /** Back from an indented code block's first character over its own indent: four spaces, or a tab. */
        int codeIndentStart( final int pos ) {
            int p = pos;
            for ( int columns = 0; columns < 4 && p > 0 && isBlank( full.charAt( p - 1 ) ); columns++ ) {
                p--;
                if ( full.charAt( p ) == '\t' ) {
                    break;
                }
            }
            return p;
        }

        int spaceAfter( final int pos ) {
            int p = pos;
            while ( p < full.length() && isBlank( full.charAt( p ) ) ) {
                p++;
            }
            return p;
        }

        /**
         * The full-text position of the parsed-text position {@code pos} where a block or marker starts. A position inside
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
         * The full-text position of the exclusive end {@code pos}: one past its last character. When that character is
         * generated {@code ```math} markup, the end of its line is used instead — a rewritten block ends exactly where
         * its closing {@code $$} line did.
         */
        private int mapEnd( final int pos ) {
            if ( pos <= 0 ) {
                return bodyStart;
            }
            final int last = prepared.toBody( pos - 1 );
            if ( last >= 0 ) {
                return bodyStart + last + 1;
            }
            final String text = prepared.text();
            final int nl = text.indexOf( '\n', pos - 1 );
            final int lineEnd = prepared.toBody( nl < 0 ? text.length() : nl );
            if ( lineEnd < 0 ) {
                throw new IllegalStateException( "Parsed end offset " + pos + " maps to no source position" );
            }
            return bodyStart + lineEnd;
        }
    }
}

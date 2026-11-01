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

import com.vladsch.flexmark.ast.Text;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.markdown.extensions.callouts.CalloutBlock;
import com.wikantik.util.TrackedText;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server's model of a page's addressable blocks and their Obsidian-style {@code ^id} block markers
 * (spec {@code 2026-10-31-obsidian-block-references-design.md} §1.1, §1.3).
 *
 * <p><b>The page is read exactly as rendering reads it — the same function of the same text.</b> Its access rules are
 * stripped with {@link AclDirectiveText#stripTracked}, the very strip a render applies before it parses
 * ({@code DisplayText}); frontmatter is skipped; the body goes through {@link MarkdownBodySource#prepare} (plugin
 * {@code ()} insertion, {@code $$…$$} → {@code ```math}), is parsed with {@link MarkdownDocument#structuralOptions()}
 * plus inline {@code $…$} math, and its blocks are numbered by {@link BlockWalk}, the walk the render post-processor
 * runs. The raw text itself is never parsed, so whatever stripping a rule does to the structure — a rule beside a
 * marker, a rule-only line, a rule-only list item, an indent or tab left behind — this model sees, by construction.
 *
 * <p>Every offset this class reports is then mapped back to the <b>raw</b> text passed in, frontmatter and access rules
 * included, through the strip's own record of what it cut ({@link TrackedText}): {@link #assign} and {@link #strip} edit
 * the raw text, and only ever insert text or delete what the stripped text holds — never an access rule, and never text
 * a render does not show. A {@code ^id} written inside a rule is stripped with it, so it is no marker here either.
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
 * id. (The editor's JavaScript mirror follows these rules over the raw text; see {@code blockIds.js} for where that can
 * differ.)
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
     * @param plainText   the block's text with markdown, wikilinks and markers reduced away, trimmed — as the reader
     *                    sees it (read from the stripped text, so no access rule is in it)
     */
    public record Block( int index, String id, Kind kind, int start, int end, int markerStart, int markerEnd, String plainText ) {
    }

    /** A block id, without its {@code ^}. */
    public static final Pattern ID = Pattern.compile( "[A-Za-z0-9-]+" );

    private static final String NEW_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int NEW_ID_LENGTH = 6;
    private static final int FINGERPRINT_LENGTH = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BlockIds() {
    }

    /** Test seam: the pages parsed on this thread so far. */
    static long parsesOnThisThread() {
        return BlockModel.parsesOnThisThread();
    }

    // ---- public API ---------------------------------------------------------------------------------------------------

    /**
     * Every addressable block of the page, in pre-order.
     *
     * @param fullText raw page text, frontmatter included; may be {@code null}
     * @return the blocks; never {@code null}
     */
    public static List< Block > list( final String fullText ) {
        return BlockModel.read( fullText ).blocks();
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
        final BlockModel.Shown shown = BlockModel.Shown.of( text );
        if ( id == null || !shown.mayHoldMarker() ) {
            return Optional.empty();
        }
        return BlockModel.read( shown, id::equals, body ).blocks().stream().filter( b -> id.equals( b.id() ) ).findFirst();
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
        source = BlockEdits.withoutContainerPrefix( source,
                BlockEdits.continuationPrefix( fullText.substring( BlockEdits.lineStart( fullText, b.start() ), b.start() ) ) );
        return cut ? source.stripTrailing() : source;
    }

    /**
     * Gives block {@code b} the id {@code id}: as an inline {@code " ^id"} at the end of a paragraph or of a list item's
     * first line, otherwise as a standalone marker line after the block (with a blank line before it, inside the same
     * container). A block whose duplicate marker carries no id gets that marker replaced. The edit is worked out on the
     * text a render shows and made in the raw text through the strip's map: it inserts, or replaces what the shown text
     * holds of the old marker, and leaves every access rule as it is.
     *
     * @param fullText the raw text {@code b} was listed from
     * @param b        a block from {@code list( fullText )}
     * @param id       the new id, matching {@link #ID}
     * @return the edited text
     * @throws IllegalArgumentException when {@code id} is malformed, {@code b} is not a current block of {@code fullText},
     *                                  or the block already has an id or cannot take one — which includes a block no
     *                                  marker can tag without changing the blocks around it (a table whose paragraph
     *                                  runs on into the next line: the blank line a standalone marker needs would end
     *                                  that paragraph). Every edit is read back before it is returned: {@code b} must
     *                                  carry {@code id}, and every block must be of the kind it was.
     */
    public static String assign( final String fullText, final Block b, final String id ) {
        if ( id == null || !ID.matcher( id ).matches() ) {
            throw new IllegalArgumentException( "Not a block id: " + id );
        }
        final BlockModel.Model model = untagged( BlockModel.read( fullText ), b );
        final List< Kind > kinds = kinds( model.blocks() );
        for ( final BlockEdits.Edit edit : BlockEdits.candidates( model.shown().text(), b, model.entries().get( b.index() ), id ) ) {
            final String result = edit.applyTo( model.shown() );
            final List< Block > after = list( result );
            if ( b.index() < after.size() && id.equals( after.get( b.index() ).id() ) && kinds( after ).equals( kinds ) ) {
                return result;
            }
        }
        throw new IllegalArgumentException( "Block " + b.index() + " (" + b.kind() + ") cannot take ^" + id
                + " without changing the blocks around it" );
    }

    /** {@code model}, once {@code b} is checked to be one of its blocks, as listed, and without an id. */
    private static BlockModel.Model untagged( final BlockModel.Model model, final Block b ) {
        if ( b.index() < 0 || b.index() >= model.entries().size() || !model.blocks().get( b.index() ).equals( b ) ) {
            throw new IllegalArgumentException( "Block " + b.index() + " is not a current block of this text" );
        }
        if ( b.id() != null ) {
            throw new IllegalArgumentException( "Block " + b.index() + " already has id ^" + b.id() );
        }
        return model;
    }

    private static List< Kind > kinds( final List< Block > blocks ) {
        return blocks.stream().map( Block::kind ).toList();
    }

    /**
     * The text with every block marker removed — inline, standalone, duplicate and orphan alike. The line count is kept:
     * a standalone marker's line keeps its line break (and any container prefix such as {@code "> "}). Only the marker's
     * own characters go (with the blanks around it): an access rule beside or inside a marker stays.
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
        final BlockModel.Shown shown = BlockModel.Shown.of( fullText );
        if ( !shown.mayHoldMarker() ) {
            return fullText;
        }
        final BlockModel.Model model = BlockModel.read( shown, BlockModel.NO_TEXT, body );
        if ( model.markers().isEmpty() ) {
            return fullText;
        }
        final List< int[] > cuts = new ArrayList<>();
        for ( final BlockModel.Marker m : model.markers() ) {
            cuts.addAll( shown.tracked().sourceSpans( m.shownStart(), m.shownEnd() ) );
        }
        return BlockEdits.without( fullText, cuts );
    }

    /**
     * The id of every marker in the text — block ids, losing duplicates and orphans alike — so a new id can collide with
     * none of them.
     *
     * @param fullText raw page text; may be {@code null}
     * @return the ids; never {@code null}
     */
    public static Set< String > markerIds( final String fullText ) {
        return markerIds( fullText, true );
    }

    /** Test seam: {@link #markerIds(String)}, with or without the pre-check that skips the parse. */
    static Set< String > markerIds( final String fullText, final boolean preCheck ) {
        final Set< String > ids = new HashSet<>();
        final BlockModel.Shown shown = BlockModel.Shown.of( fullText );
        if ( fullText == null || preCheck && !shown.mayHoldMarker() ) {
            return ids;
        }
        for ( final BlockModel.Marker m : BlockModel.read( shown, BlockModel.NO_TEXT, false ).markers() ) {
            ids.add( m.id() );
        }
        return ids;
    }

    /** Test seam: whether the pre-check lets {@code fullText} be parsed for markers. */
    static boolean mayHoldMarker( final String fullText ) {
        return BlockModel.mayHoldMarker( fullText );
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

}

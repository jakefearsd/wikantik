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
package com.wikantik.content;

import com.google.gson.JsonParser;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockTextGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's block ids come from the server's own model of the editor's buffer: whatever block the reader shows at an
 * offset is the one linked, an id the reader would not show is never handed out, and adding one changes no other
 * block's id — no orphan, no mis-target, no dangling marker.
 */
class BlockDraftsTest {

    /** Each shape is tried as a whole page ({@code null}) and as the editor's body behind a saved frontmatter. */
    private static final List< String > FRONTMATTERS = java.util.Arrays.asList( null, "---\ntitle: T\n---\n" );

    private static String nextId( final Set< String > taken ) {
        return BlockIds.newId( taken );
    }

    /** What is wrong with linking the block at {@code offset} of the whole page {@code text}, or an empty list. */
    static List< String > problems( final String text, final int offset ) {
        return problems( text, offset, null );
    }

    /**
     * The reader's blocks of {@code text}: of the page itself when {@code frontmatter} is {@code null}, else of the saved
     * page — {@code text} as a body behind {@code frontmatter} — with offsets in {@code text}.
     */
    static List< Block > reader( final String text, final String frontmatter ) {
        if ( frontmatter == null ) {
            return BlockIds.list( text );
        }
        final int shift = frontmatter.length();
        return BlockIds.list( frontmatter + text ).stream().map( b -> new Block( b.index(), b.id(), b.kind(), b.start() - shift,
                b.end() - shift, b.markerStart() < 0 ? -1 : b.markerStart() - shift, b.markerEnd() < 0 ? -1 : b.markerEnd() - shift,
                b.plainText() ) ).toList();
    }

    /**
     * What is wrong with linking the block at {@code offset} of {@code text} — a whole page when {@code frontmatter} is
     * {@code null}, else a body checked against what the reader gets for {@code frontmatter + text} — or an empty list.
     */
    static List< String > problems( final String text, final int offset, final String frontmatter ) {
        final List< String > out = new ArrayList<>();
        final List< Block > before = reader( text, frontmatter );
        final Block target = BlockDrafts.blockAt( before, offset ).orElse( null );
        final BlockDrafts.Link link;
        try {
            link = BlockDrafts.linkAt( "Draft", text, frontmatter != null, offset, BlockDraftsTest::nextId );
        } catch ( final BlockDrafts.Refused e ) {
            if ( e.reason() == BlockDrafts.Refusal.NO_BLOCK && target != null ) {
                out.add( "no block at " + offset + " though block " + target.index() + " holds it" );
            }
            return out;
        }
        if ( target == null || link.block().index() != target.index() ) {
            out.add( "mis-target: linked block " + link.block().index() + " at " + offset + ", the reader's is " + target );
            return out;
        }
        if ( link.change() == null ) {
            if ( !link.id().equals( target.id() ) ) {
                out.add( "copied ^" + link.id() + " but the reader shows " + target.id() );
            }
            return out;
        }
        final String after = text.substring( 0, link.change().from() ) + link.change().insert() + text.substring( link.change().to() );
        final List< Block > now = reader( after, frontmatter );
        if ( now.size() != before.size() ) {
            out.add( "the blocks changed: " + before.size() + " -> " + now.size() );
            return out;
        }
        if ( !link.id().equals( now.get( target.index() ).id() ) ) {
            out.add( "dangling: ^" + link.id() + " is not on block " + target.index() + " after the change" );
        }
        for ( int i = 0; i < before.size(); i++ ) {
            if ( i != target.index() && !Objects.equals( before.get( i ).id(), now.get( i ).id() ) ) {
                out.add( "orphan: block " + i + " went from ^" + before.get( i ).id() + " to ^" + now.get( i ).id() );
            }
        }
        return out;
    }

    /** Offsets worth trying in {@code text}: each block's start, middle and end, and a few at random. */
    static List< Integer > offsets( final String text, final Random random ) {
        final Set< Integer > out = new HashSet<>();
        for ( final Block b : BlockIds.list( text ) ) {
            out.add( b.start() );
            out.add( ( b.start() + b.end() ) / 2 );
            out.add( b.end() );
        }
        for ( int i = 0; i < 3; i++ ) {
            out.add( random.nextInt( text.length() + 1 ) );
        }
        return new ArrayList<>( out );
    }

    private static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" ).replace( "\t", "\\t" );
    }

    @Test
    void theReviewersShapesLinkTheBlockTheReaderShowsAndOrphanNothing() throws IOException {
        final List< String > shapes = new ArrayList<>();
        try ( InputStream in = getClass().getResourceAsStream( "block-draft-shapes.json" ) ) {
            assertNotNull( in );
            JsonParser.parseString( new String( in.readAllBytes(), StandardCharsets.UTF_8 ) ).getAsJsonArray()
                    .forEach( e -> shapes.add( e.getAsString() ) );
        }
        assertTrue( shapes.size() >= 50 );
        final List< String > problems = new ArrayList<>();
        for ( final String text : shapes ) {
            for ( final String fm : FRONTMATTERS ) {
                for ( int offset = 0; offset <= text.length(); offset++ ) {
                    final int at = offset;
                    problems( text, at, fm ).forEach( p -> problems.add( fm + "|" + esc( text ) + " @" + at + ": " + p ) );
                }
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
    }

    @Test
    void aMarkerTheReaderReadsAsCodeIsNeverTheIdOfItsBlock() throws BlockDrafts.Refused {
        final String text = "> Para\n>\n> [{ALLOW view Admin}]    ^b\n\nOther ^b\n";
        final BlockDrafts.Link para = BlockDrafts.linkAt( "Draft", text, text.indexOf( "Para" ), taken -> "nw1234" );
        assertEquals( "nw1234", para.id() );
        assertNotNull( para.change() );
        final BlockDrafts.Link other = BlockDrafts.linkAt( "Draft", text, text.indexOf( "Other" ) + 2, taken -> "unused" );
        assertEquals( "b", other.id() );
        assertNull( other.change() );
    }

    @Test
    void theChangeIsTheOneMinimalEdit() throws BlockDrafts.Refused {
        final BlockDrafts.Link link = BlockDrafts.linkAt( "Draft", "Alpha.\n\nBeta.\n", 9, taken -> "bb1234" );
        assertEquals( new BlockDrafts.Change( 13, 13, " ^bb1234" ), link.change() );
        assertEquals( 1, link.block().index() );
        final BlockDrafts.Link dup = BlockDrafts.linkAt( "Draft", "A ^d\n\nB ^d\n", 7, taken -> "zz9999" );
        assertEquals( new BlockDrafts.Change( 9, 10, "zz9999" ), dup.change() );
        assertEquals( new BlockDrafts.Change( 3, 3, "" ), BlockDrafts.minimalEdit( "abc", "abc" ) );
        assertEquals( new BlockDrafts.Change( 1, 1, "X" ), BlockDrafts.minimalEdit( "aa", "aXa" ) );
    }

    @Test
    void anOffsetOutsideEveryBlockOrABlockThatCannotBeTaggedIsRefused() {
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", "# Heading\n\nPara.\n", 3, taken -> "x" ) ).reason() );
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", "Para.\n", 99, taken -> "x" ) ).reason() );
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", null, 0, taken -> "x" ) ).reason() );
        final String table = "| a | b |\n|---|---|\n| 1 | 2 |\n\tPara.\n";
        assertEquals( BlockDrafts.Refusal.REFUSED, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", table, 3, taken -> "tb1234" ) ).reason() );
        assertEquals( BlockDrafts.Refusal.REFUSED, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", "- ^x\n", 0, taken -> "tb1234" ) ).reason() );
    }

    @Test
    void theInnermostBlockHoldingTheOffsetIsTheOneLinked() {
        final List< Block > blocks = BlockIds.list( "- one\n  - two\n> q\n" );
        assertEquals( 1, BlockDrafts.blockAt( blocks, "- one\n  - t".length() ).orElseThrow().index() );
        assertEquals( 0, BlockDrafts.blockAt( blocks, 1 ).orElseThrow().index() );
    }

    /** The reviewer's shape, in the editor: a body opening with a thematic break, behind the page's frontmatter. */
    @Test
    void aBodyOpeningWithAThematicBreakLinksTheBlocksTheReaderShowsBehindTheFrontmatter() throws BlockDrafts.Refused {
        final String body = "---\nIntro ^x\n\n---\n\nPara ^x\n\nThird\n";
        final BlockDrafts.Link intro = BlockDrafts.linkAt( "Draft", body, true, body.indexOf( "Intro" ), taken -> "unused" );
        assertEquals( "x", intro.id() );
        assertNull( intro.change() );
        final BlockDrafts.Link para = BlockDrafts.linkAt( "Draft", body, true, body.indexOf( "Para" ), taken -> "nw1234" );
        assertEquals( "nw1234", para.id() );
        // the losing duplicate's marker is renamed in place — one minimal edit
        assertEquals( new BlockDrafts.Change( body.indexOf( "Para ^x" ) + 6, body.indexOf( "Para ^x" ) + 7, "nw1234" ),
                para.change() );
        final String hidden = "---\n\nIntro para.\n\nSecond para with words.\n\n---\n\nPara\n";
        assertNotNull( BlockDrafts.linkAt( "Draft", hidden, true, hidden.indexOf( "Second" ), taken -> "nw1234" ).change() );
        // the same text as a whole page: its leading pair IS frontmatter, as a save without metadata makes it
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "Draft", hidden, false, hidden.indexOf( "Second" ), taken -> "x" ) ).reason() );
    }

    /**
     * 2,500 generated bodies — half opening with {@code ---} — behind frontmatter, every action, against the reader (run
     * at 6,000 when written: 0 problems; trimmed to keep the unit suite quick, the page-mode run above is 6,000).
     */
    @Test
    void generatedBodiesBehindFrontmatterNeverOrphanMisTargetOrDangle() {
        final BlockTextGenerator generator = new BlockTextGenerator( 0xB0D1E5L );
        final Random random = new Random( 0x5EED5L );
        final List< String > problems = new ArrayList<>();
        int actions = 0;
        for ( int i = 0; i < 2_500 && problems.size() < 20; i++ ) {
            final String text = switch ( random.nextInt( 4 ) ) {
                case 0 -> "---\n" + generator.next();
                case 1 -> "---\n" + generator.next() + "\n---\n" + generator.next();
                default -> generator.next();
            };
            final String fm = FRONTMATTERS.get( 1 );
            for ( final int offset : offsets( text, random ) ) {
                actions++;
                final int n = i;
                problems( text, offset, fm ).forEach( p -> problems.add( "#" + n + " " + esc( text ) + " @" + offset + ": " + p ) );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        assertTrue( actions > 12_000, "actions: " + actions );
    }

    /** 6,000 generated pages, every action: 0 orphans, 0 mis-targets, 0 dangling markers. */
    @Test
    void generatedPagesNeverOrphanMisTargetOrDangle() {
        final BlockTextGenerator generator = new BlockTextGenerator( 0xD2AF7L );
        final Random random = new Random( 0x0FF5E7L );
        final List< String > problems = new ArrayList<>();
        int actions = 0;
        for ( int i = 0; i < 6_000 && problems.size() < 20; i++ ) {
            final String text = generator.next();
            for ( final int offset : offsets( text, random ) ) {
                actions++;
                final int n = i;
                problems( text, offset ).forEach( p -> problems.add( "#" + n + " " + esc( text ) + " @" + offset + ": " + p ) );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        assertTrue( actions > 30_000, "actions: " + actions );
    }
}

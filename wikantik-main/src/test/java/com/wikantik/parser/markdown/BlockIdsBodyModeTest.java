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

import com.wikantik.api.frontmatter.FrontmatterWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body-mode reads ({@link BlockIds#listBody}, {@link BlockIds#markerIdsBody}, {@link BlockIds#assignBody}) of a page
 * <em>body</em> — the editor's buffer, which holds no frontmatter — are exactly what the reader gets for the saved page,
 * the body behind its real frontmatter: index by index, the same kind, id and text, at the same place shifted by the
 * frontmatter's length. A leading {@code ---} pair in a body is two thematic breaks (or a setext underline), never
 * frontmatter, because on the saved page it stands behind the real one.
 */
class BlockIdsBodyModeTest {

    /** Bodies the full-page reading gets wrong: a leading thematic break, a setext underline, a frontmatter-shaped body. */
    static final List< String > SHAPES = List.of(
            "---\nIntro ^x\n\n---\n\nPara ^x\n\nThird\n",
            "---\nIntro\n---\n\nPara\n",
            "---\n\nIntro para.\n\nSecond para with words.\n\n---\n\nPara\n",
            "---\ntitle: X ^a\n---\nPara one\n\nPara two ^p\n",
            "---\r\nIntro ^x\r\n\r\n---\r\n\r\nPara ^y\r\n",
            "Title ^t\n---\n\nPara ^p\n",
            "---\n---\nPara ^p\n",
            "---\n",
            "---",
            "- a\n---\n- b ^b\n",
            "> q ^q\n---\nPara\n",
            "---\n[{ALLOW view Admin}]\n---\n\nPara ^p\n",
            "[{ALLOW view Admin}]\n---\nIntro ^x\n---\n",
            "" );

    /** Frontmatter as a save writes it ({@link FrontmatterWriter}), plus hand-written spellings pages carry. */
    static List< String > frontmatters() {
        final List< String > out = new ArrayList<>();
        out.add( FrontmatterWriter.write( Map.of( "title", "T" ), "" ) );
        final Map< String, Object > tricky = new LinkedHashMap<>();
        tricky.put( "title", "A ^x" );
        tricky.put( "summary", "[{ALLOW view Admin}]" );
        tricky.put( "notes", "one\n---\ntwo ^y" );
        tricky.put( "tags", List.of( "a", "---" ) );
        out.add( FrontmatterWriter.write( tricky, "" ) );
        out.add( "---\ntitle: T\n---\n" );
        out.add( "---\r\ntitle: T\r\n---\r\n" );
        out.add( "---\n---\n" );
        out.add( "---\ntitle: T ^a\n---\n\n" );
        return out;
    }

    /** What differs between the body-mode reads of {@code body} and the reader's reads of {@code frontmatter + body}. */
    static List< String > differences( final String frontmatter, final String body ) {
        final List< String > out = new ArrayList<>();
        final String page = frontmatter + body;
        final int shift = frontmatter.length();
        final List< BlockIds.Block > mine = BlockIds.listBody( body );
        final List< BlockIds.Block > reader = BlockIds.list( page );
        if ( mine.size() != reader.size() ) {
            out.add( "blocks " + mine.size() + " vs reader " + reader.size() );
            return out;
        }
        for ( int i = 0; i < mine.size(); i++ ) {
            final BlockIds.Block m = mine.get( i );
            final BlockIds.Block r = reader.get( i );
            if ( m.index() != i || m.kind() != r.kind() || !java.util.Objects.equals( m.id(), r.id() )
                    || !BlockIds.fingerprint( m.plainText() ).equals( BlockIds.fingerprint( r.plainText() ) )
                    || m.start() != r.start() - shift || m.end() != r.end() - shift
                    || m.markerStart() != ( r.markerStart() < 0 ? -1 : r.markerStart() - shift )
                    || m.markerEnd() != ( r.markerEnd() < 0 ? -1 : r.markerEnd() - shift ) ) {
                out.add( "block " + i + ": " + m + " vs reader " + r );
            }
        }
        if ( !BlockIds.markerIdsBody( body ).equals( BlockIds.markerIds( page ) ) ) {
            out.add( "marker ids " + BlockIds.markerIdsBody( body ) + " vs reader " + BlockIds.markerIds( page ) );
        }
        final String stripped = BlockIds.strip( page );
        if ( !stripped.startsWith( frontmatter ) || !BlockIds.stripBody( body ).equals( stripped.substring( shift ) ) ) {
            out.add( "strip differs" );
        }
        for ( int i = 0; i < mine.size(); i++ ) {
            if ( mine.get( i ).id() != null ) {
                continue;
            }
            final BlockIds.Block m = mine.get( i );
            final BlockIds.Block r = reader.get( i );
            final String a = assigned( () -> BlockIds.assignBody( body, m, "nw1234" ) );
            final String b = assigned( () -> BlockIds.assign( page, r, "nw1234" ) );
            if ( a == null || b == null ) {
                if ( a != b ) {
                    out.add( "assign block " + i + ": " + a + " vs reader " + b );
                }
                continue;
            }
            // a save writes LF frontmatter: then the edit is the reader's own, character for character; behind a CRLF
            // one, assign may end an appended marker line with CRLF — the same blocks and ids either way
            if ( !frontmatter.contains( "\r\n" ) && !b.equals( frontmatter + a ) ) {
                out.add( "assign block " + i + ": " + a + " vs reader " + b );
            }
            final List< BlockIds.Block > now = BlockIds.list( frontmatter + a );
            boolean same = now.size() == reader.size() && "nw1234".equals( now.get( i ).id() );
            for ( int k = 0; same && k < now.size(); k++ ) {
                same = now.get( k ).kind() == reader.get( k ).kind()
                        && ( k == i || java.util.Objects.equals( now.get( k ).id(), reader.get( k ).id() ) );
            }
            if ( !same ) {
                out.add( "assign block " + i + " behind the frontmatter: " + now );
            }
        }
        return out;
    }

    private interface Edit {
        String apply();
    }

    /** The edited text, or {@code null} when the edit is refused. */
    private static String assigned( final Edit edit ) {
        try {
            return edit.apply();
        } catch ( final IllegalArgumentException e ) {
            return null;
        }
    }

    private static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" ).replace( "\t", "\\t" );
    }

    @Test
    void theShapesTheFullPageReadingGetsWrongReadAsTheReaderReadsTheSavedPage() {
        final List< String > problems = new ArrayList<>();
        for ( final String fm : frontmatters() ) {
            for ( final String body : SHAPES ) {
                differences( fm, body ).forEach( d -> problems.add( esc( fm ) + " + " + esc( body ) + ": " + d ) );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
    }

    @Test
    void theReviewersShapeLinksIntroAndNeverHandsOutTheLosingDuplicate() {
        final String body = SHAPES.get( 0 );
        final List< BlockIds.Block > blocks = BlockIds.listBody( body );
        assertEquals( "x", blocks.stream().filter( b -> b.plainText().equals( "Intro" ) ).findFirst().orElseThrow().id() );
        assertEquals( null, blocks.stream().filter( b -> b.plainText().equals( "Para" ) ).findFirst().orElseThrow().id() );
        assertEquals( java.util.Set.of( "x" ), BlockIds.markerIdsBody( body ) );
        assertEquals( 3, BlockIds.listBody( SHAPES.get( 2 ) ).size() );
        assertEquals( List.of(), BlockIds.listBody( null ) );
        assertEquals( java.util.Set.of(), BlockIds.markerIdsBody( null ) );
    }

    /** Generated bodies — some opening with {@code ---}, some frontmatter-shaped — behind every frontmatter. */
    @Test
    void generatedBodiesReadAsTheReaderReadsThemBehindFrontmatter() {
        final BlockTextGenerator generator = new BlockTextGenerator( 0xB0D7L );
        final Random random = new Random( 0xF00DL );
        final List< String > fms = frontmatters();
        final List< String > problems = new ArrayList<>();
        int checked = 0;
        for ( int i = 0; i < 3_000 && problems.size() < 20; i++ ) {
            String body = generator.next();
            switch ( random.nextInt( 4 ) ) {
                case 0 -> body = "---\n" + body;
                case 1 -> body = "---\n" + generator.next() + "\n---\n" + body;
                default -> { }
            }
            final String fm = fms.get( random.nextInt( fms.size() ) );
            final String b = body;
            final int n = checked;
            differences( fm, b ).forEach( d -> problems.add( "#" + n + " " + esc( fm ) + " + " + esc( b ) + ": " + d ) );
            checked++;
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        assertTrue( checked >= 3_000 );
    }
}

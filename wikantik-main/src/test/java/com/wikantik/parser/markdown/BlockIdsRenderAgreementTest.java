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

import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.parser.markdown.BlockIds.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantee the block endpoints rest on: a render parses {@code AclDirectiveText.strip(raw)} (DisplayText.of, the
 * non-WYSIWYG path of DefaultRenderingManager), numbers its blocks with the same walk, and stamps the ordinal on the
 * HTML. The ordinal a reader sends to {@code POST /blocks} is then looked up in {@code BlockIds.list(raw)}. So the two
 * lists must agree index by index on kind, text and id — on every text, access rules or not.
 */
class BlockIdsRenderAgreementTest {

    /** Mismatch descriptions between the raw list and the list the render path parses; empty when they agree. */
    static List< String > disagreements( final String raw ) {
        final List< Block > a = BlockIds.list( raw );
        final List< Block > b = BlockIds.list( AclDirectiveText.strip( raw ) );
        final List< String > out = new ArrayList<>();
        if ( a.size() != b.size() ) {
            out.add( "block count raw=" + a.size() + " rendered=" + b.size() );
        }
        for ( int i = 0; i < Math.min( a.size(), b.size() ); i++ ) {
            final Block x = a.get( i );
            final Block y = b.get( i );
            if ( x.kind() != y.kind() || !BlockIds.fingerprint( x.plainText() ).equals( BlockIds.fingerprint( y.plainText() ) )
                    || !java.util.Objects.equals( x.id(), y.id() ) ) {
                out.add( "block " + i + " raw=" + x.kind() + "/" + x.id() + "/'" + x.plainText() + "' rendered="
                        + y.kind() + "/" + y.id() + "/'" + y.plainText() + "'" );
            }
        }
        return out;
    }

    @Test
    void syntheticAccessRuleLayoutsAgree() {
        final String[] cases = {
            "[{ALLOW view Admin}]\n\nFirst.\n\nSecond.\n",
            "First.\n\n[{ALLOW view Admin}]\n\nSecond.\n",
            "# Heading\n\n[{DENY view Guest}]\n\nSecond.\n",
            "> [{ALLOW view Admin}]\n\nAfter quote.\n",
            "> Quote text.\n> [{ALLOW view Admin}]\n\nAfter quote.\n",
            "- item one\n[{ALLOW view Admin}]\n- item two\n\nAfter.\n",
            "- item one\n  [{ALLOW view Admin}]\n- item two\n",
            "- [{ALLOW view Admin}]\n- item two\n",
            "Text before [{ALLOW view Admin}] and after.\n\nSecond.\n",
            "Line one\n[{ALLOW view Admin}]\nline three\n\nSecond.\n",
            "Line one\n[{ALLOW view Admin}]\n^tag\n\nSecond.\n",
            "Para.\n\n[{ALLOW view Admin}]\n\n^after\n\nSecond.\n",
            "[{ALLOW view Admin}]\n\n^orphan\n\nSecond.\n",
            "Para. ^one\n\n[{ALLOW view Admin}]\n\n^two\n\nSecond.\n",
            "| a | b |\n|---|---|\n| 1 | 2 |\n\n[{ALLOW view Admin}]\n\n^tbl\n",
            "---\ntitle: X\n---\n[{ALLOW view Admin}]\n\nFirst.\n\nSecond.\n",
            "[{ALLOW view Admin}]\r\n\r\nFirst.\r\n\r\nSecond.\r\n",
            "[{ALLOW view Admin}] [{DENY edit Guest}]\n\nFirst.\n\nSecond.\n",
        };
        final List< String > problems = new ArrayList<>();
        for ( final String c : cases ) {
            disagreements( c ).forEach( d -> problems.add( c.replace( "\n", "\\n" ) + " -> " + d ) );
        }
        assertEquals( List.of(), problems );
    }

    /**
     * A paragraph (or list item) holding access rules and one marker is, once the rules are stripped, a standalone marker
     * paragraph: it tags the block before it in its container, or is an orphan. The raw reading must say the same.
     */
    @Test
    void aDirectiveWithAMarkerReadsAsTheStandaloneMarkerItStripsTo() {
        final String[] cases = {
            "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter.",
            "Para.\n\n[{ALLOW view Admin}]\n^id\n\nAfter.",
            "> [{ALLOW view Admin}] ^q\n\nAfter.",
            "- item\n- [{ALLOW view Admin}] ^id",
            "Para.\n\n^id [{DENY edit Guest}]\n\nAfter.",
            "Para.\n\n^id\n[{DENY edit Guest}]\n\nAfter.",
            "Para.\n\n[{ALLOW view Admin}] [{DENY edit Guest}] ^id\n\nAfter.",
        };
        final List< String > problems = new ArrayList<>();
        for ( final String c : cases ) {
            disagreements( c ).forEach( d -> problems.add( c.replace( "\n", "\\n" ) + " -> " + d ) );
        }
        assertEquals( List.of(), problems );
        final List< Block > tagged = BlockIds.list( "Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter." );
        assertEquals( 2, tagged.size() );
        assertEquals( "id", tagged.get( 0 ).id() );
        assertEquals( "Para.", tagged.get( 0 ).plainText() );
    }

    /** Where a directive can sit. Each takes the directive line {@code %s}; {@code %p} is its container's line prefix. */
    private static final String[][] POSITIONS = {
        { "top", "%s\n\nAfter.\n" },
        { "between paragraphs", "Before.\n\n%s\n\nAfter.\n" },
        { "after a heading", "# Heading\n%s\n\nAfter.\n" },
        { "after a heading, blank line", "# Heading\n\n%s\n\nAfter.\n" },
        { "in a blockquote", "> %s\n\nAfter.\n" },
        { "later in a blockquote", "> Quoted.\n>\n> %s\n\nAfter.\n" },
        { "in a callout", "> [!note] Title\n> %s\n\nAfter.\n" },
        { "in a callout body", "> [!note] Title\n> Body.\n>\n> %s\n\nAfter.\n" },
        { "first list item", "- %s\n- two\n\nAfter.\n" },
        { "later list item", "- one\n- %s\n\nAfter.\n" },
        { "nested list item", "- one\n  - %s\n  - three\n- four\n" },
        { "later nested list item", "- one\n  - two\n  - %s\n- four\n" },
    };

    /** The continuation prefix of each position's directive line, for the marker forms that add lines. */
    private static String prefixOf( final String position ) {
        if ( position.contains( "quote" ) || position.contains( "callout" ) ) {
            return "> ";
        }
        if ( position.contains( "nested" ) ) {
            return "    ";
        }
        return position.contains( "list" ) ? "  " : "";
    }

    @Test
    void everyDirectivePositionAndMarkerFormAgrees() {
        final String rule = "[{ALLOW view Admin}]";
        final List< String > problems = new ArrayList<>();
        int checked = 0;
        for ( final String[] position : POSITIONS ) {
            final String p = prefixOf( position[ 0 ] );
            final String[] forms = {
                rule + " ^mk",                                          // inline, same line
                rule + "\n" + p + "^mk",                                // next line (last-line form)
                rule + "\n" + p.stripTrailing() + "\n" + p + "^mk",     // standalone after a blank line
                rule,                                                   // no marker
            };
            for ( final String form : forms ) {
                for ( final boolean crlf : new boolean[]{ false, true } ) {
                    String text = position[ 1 ].replace( "%s", form );
                    if ( crlf ) {
                        text = text.replace( "\n", "\r\n" );
                    }
                    checked++;
                    final String shown = text.replace( "\r", "\\r" ).replace( "\n", "\\n" );
                    disagreements( text ).forEach( d -> problems.add( position[ 0 ] + ": " + shown + " -> " + d ) );
                }
            }
        }
        assertEquals( POSITIONS.length * 4 * 2, checked );
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
    }

    @Test
    void aDirectiveOnlyParagraphIsNotABlock() {
        final List< Block > blocks = BlockIds.list( "[{ALLOW view Admin}]\n\nFirst.\n\nSecond.\n" );
        assertEquals( 2, blocks.size() );
        assertEquals( "First.", blocks.get( 0 ).plainText() );
        assertEquals( "Second.", blocks.get( 1 ).plainText() );
    }

    @Test
    void aStandaloneMarkerAfterADirectiveOnlyParagraphTagsTheBlockBeforeIt() {
        final List< Block > blocks = BlockIds.list( "Para.\n\n[{ALLOW view Admin}]\n\n^after\n\nSecond.\n" );
        assertEquals( 2, blocks.size() );
        assertEquals( "after", blocks.get( 0 ).id() );
        assertEquals( null, blocks.get( 1 ).id() );
    }

    @Test
    void everyShippedPageAgrees() throws IOException {
        final Path pages = Path.of( System.getProperty( "user.dir" ) ).getParent().resolve( "docs/wikantik-pages" );
        final List< String > problems = new ArrayList<>();
        int withRules = 0;
        try ( Stream< Path > files = Files.list( pages ) ) {
            for ( final Path page : files.filter( p -> p.toString().endsWith( ".md" ) ).sorted().toList() ) {
                final String text = Files.readString( page );
                if ( !AclDirectiveText.strip( text ).equals( text ) ) {
                    withRules++;
                }
                disagreements( text ).forEach( d -> problems.add( page.getFileName() + ": " + d ) );
            }
        }
        assertTrue( withRules > 0, "the corpus holds pages with access rules" );
        assertEquals( List.of(), problems );
    }
}

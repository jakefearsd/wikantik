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
import java.util.Set;
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

    /**
     * Shapes where a raw-text reading and the render once parted: the render strips the rules first, and what that leaves
     * — an indented line (code), a tab, a setext underline with nothing to underline, a vertical tab after a marker — is
     * what decides. Block numbering parses that same stripped text, so these agree by construction.
     */
    @Test
    void theReviewersShapesAgree() {
        final String[] cases = {
            "Para ^id\n[{ALLOW view Admin}]\n\nAfter.",
            "Para ^id [{ALLOW view Admin}]",
            "- item ^id\n  [{ALLOW view Admin}]",
            "> quote ^q\n> [{ALLOW view Admin}]",
            "Para.\n^id [{DENY edit Guest}]",
            "Para.\n^id\n[{DENY edit Guest}]",
            "Para.\n\n[{ALLOW view Admin}]\n===\n\nAfter ^x",
            "Para.\n\n[{ALLOW view Admin}]\t^id\n\nAfter.",
            "Para.\n\n[{ALLOW view Admin}]    ^id\n\nAfter.",
            "Para.\n\n  [{ALLOW view Admin}]  ^id\n\nAfter.",
            "Para.\n\n[{ALLOW view Admin}]\n    ^id\n\nAfter.",
            "Para.\n\n[{ALLOW view Admin}] ^id\u000B\n\nAfter.",
            "> quote\n[{ALLOW view Admin}]\n> body ^i",
        };
        final List< String > problems = new ArrayList<>();
        for ( final String c : cases ) {
            for ( final String text : new String[]{ c, c.replace( "\n", "\r\n" ) } ) {
                disagreements( text ).forEach( d -> problems.add( esc( text ) + " -> " + d ) );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        // what the render shows: an indented "^id" is code, not a marker; a tab after the rule makes one too
        final List< Block > tab = BlockIds.list( "Para.\n\n[{ALLOW view Admin}]\t^id\n\nAfter." );
        assertEquals( List.of( BlockIds.Kind.PARAGRAPH, BlockIds.Kind.CODE, BlockIds.Kind.PARAGRAPH ),
                tab.stream().map( Block::kind ).toList() );
        assertEquals( null, tab.get( 0 ).id() );
        // a setext underline under a rule that is stripped underlines nothing: it is a paragraph of its own
        assertEquals( 3, BlockIds.list( "Para.\n\n[{ALLOW view Admin}]\n===\n\nAfter ^x" ).size() );
        // the quote's paragraph reads "quote body" once the rule line between is gone: one fingerprint for both
        final Block body = BlockIds.find( "> quote\n[{ALLOW view Admin}]\n> body ^i", "i" ).orElseThrow();
        assertEquals( "quotebody", BlockIds.fingerprint( body.plainText() ) );
    }

    /** Cases for the property test: enough that every shape the generator can make comes up many times. */
    private static final int PROPERTY_CASES = 30_000;

    /**
     * The property behind the endpoints, over generated pages: {@code list(raw)} equals the list of the text a render
     * parses, block by block; every range lies in the raw text; stripping markers removes them all and no access rule;
     * every listed id is found and is among the marker ids; and tagging a block changes no other block. (Stripping can
     * reveal a marker — {@code "^a\\n^b"} is a paragraph tagged {@code b} whose text reads {@code ^a} — so the stripped
     * text is not asked to hold none.)
     */
    @Test
    void generatedPagesAgreeWithTheTextTheRenderParses() {
        final BlockTextGenerator generator = new BlockTextGenerator( 0xB10C5L );
        final List< String > problems = new ArrayList<>();
        int withRules = 0;
        int withIds = 0;
        for ( int i = 0; i < PROPERTY_CASES && problems.size() < 20; i++ ) {
            final String raw = generator.next();
            try {
                final List< String > found = new ArrayList<>( disagreements( raw ) );
                found.addAll( invariantsOf( raw, i % 10 == 0 ) );
                final int n = i;
                found.forEach( d -> problems.add( "#" + n + " " + esc( raw ) + " -> " + d ) );
                withRules += BlockTextGenerator.ruleCount( raw ) > 0 ? 1 : 0;
                withIds += BlockIds.list( raw ).stream().anyMatch( b -> b.id() != null ) ? 1 : 0;
            } catch ( final RuntimeException e ) {
                problems.add( "#" + i + " " + esc( raw ) + " -> threw " + e );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        // refusals are rare and only of the known kinds (an unknown one is a problem above)
        final int refused = REFUSALS.values().stream().mapToInt( Integer::intValue ).sum();
        assertTrue( refused < PROPERTY_CASES / 10 / 10, "assign refused " + refused + " of the tagged cases: " + REFUSALS );
        assertTrue( withRules > PROPERTY_CASES / 3, "too few pages with access rules: " + withRules );
        assertTrue( withIds > PROPERTY_CASES / 3, "too few pages with block ids: " + withIds );
    }

    /** What must hold for {@code raw} on its own; {@code assign} also tags its first untagged block. */
    static List< String > invariantsOf( final String raw, final boolean assign ) {
        final List< String > out = new ArrayList<>();
        final List< Block > blocks = BlockIds.list( raw );
        final Set< String > markerIds = BlockIds.markerIds( raw );
        if ( !BlockIds.markerIds( raw, false ).isEmpty() && !BlockIds.rawMayHoldMarker( raw ) ) {
            out.add( "the raw pre-check refuses a text with markers " + BlockIds.markerIds( raw, false ) );
        }
        for ( final Block b : blocks ) {
            if ( b.start() < 0 || b.start() > b.end() || b.end() > raw.length()
                    || b.markerStart() >= 0 && ( b.markerStart() > b.markerEnd() || b.markerEnd() > raw.length() ) ) {
                out.add( "block " + b.index() + " has a range outside the text: " + b );
            }
            if ( b.id() != null && ( !markerIds.contains( b.id() )
                    || BlockIds.find( raw, b.id() ).map( Block::index ).orElse( -1 ) != b.index() ) ) {
                out.add( "id " + b.id() + " of block " + b.index() + " is not found as listed" );
            }
        }
        final String stripped = BlockIds.strip( raw );
        if ( BlockTextGenerator.ruleCount( stripped ) != BlockTextGenerator.ruleCount( raw ) ) {
            out.add( "stripping markers removed an access rule: " + esc( stripped ) );
        }
        if ( ( stripped == raw ) != markerIds.isEmpty() ) {
            out.add( "stripping " + ( stripped == raw ? "kept" : "changed" ) + " a text with markers " + markerIds );
        }
        if ( assign ) {
            for ( final Block b : blocks ) {
                if ( b.id() == null && b.markerStart() < 0 && !( b.kind() == BlockIds.Kind.LIST_ITEM && b.plainText().isEmpty() ) ) {
                    out.addAll( afterAssigning( raw, blocks, b ) );
                    break;
                }
            }
        }
        return out;
    }

    /** Why assign refused, by reason, over the property run: only the known refusals may occur. */
    static final java.util.Map< String, Integer > REFUSALS = new java.util.TreeMap<>();

    /** The reasons assign may refuse a listed, untagged block for. */
    private static final List< String > KNOWN_REFUSALS = List.of( "has no text line", "without changing the blocks around it" );

    private static List< String > afterAssigning( final String raw, final List< Block > blocks, final Block b ) {
        final String tagged;
        try {
            tagged = BlockIds.assign( raw, b, "zz9" );
        } catch ( final IllegalArgumentException e ) {
            final String reason = KNOWN_REFUSALS.stream().filter( e.getMessage()::contains ).findFirst().orElse( null );
            if ( reason == null ) {
                return List.of( "assign refused block " + b.index() + " for an unknown reason: " + e.getMessage() );
            }
            synchronized ( REFUSALS ) {
                REFUSALS.merge( reason, 1, Integer::sum );
            }
            return List.of();
        }
        final List< Block > after = BlockIds.list( tagged );
        final List< String > out = new ArrayList<>();
        if ( !after.stream().map( Block::kind ).toList().equals( blocks.stream().map( Block::kind ).toList() ) ) {
            out.add( "tagging block " + b.index() + " changed the blocks: " + esc( tagged ) );
        }
        if ( BlockTextGenerator.ruleCount( tagged ) != BlockTextGenerator.ruleCount( raw ) ) {
            out.add( "tagging block " + b.index() + " removed an access rule: " + esc( tagged ) );
        }
        return out;
    }

    static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" ).replace( "\t", "\\t" ).replace( "\u000B", "\\v" )
                .replace( "\f", "\\f" );
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

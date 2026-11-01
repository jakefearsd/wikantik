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

import java.util.Random;

/**
 * Seeded random page texts made of natural lines — paragraphs, access rules ({@code [{ALLOW …}]}, {@code [{DENY …}]}) on
 * lines of their own and inline, block markers in every position (inline, last line, standalone, before and after a
 * rule, tab- or space-separated, indented), lists, quotes, callouts, indented lines, tabs, blank lines, headings and
 * setext underlines, fences and display math — in LF or CRLF, with or without a final line end. For property tests of
 * block numbering: what {@link BlockIds} reads from the raw text against what a render reads from the stripped one.
 */
public final class BlockTextGenerator {

    private static final String[] IDS = { "a", "b", "mk", "id", "ab-1", "Q9" };

    private static final String[] RULES = {
        "[{ALLOW view Admin}]", "[{DENY edit Guest}]", "[{ALLOW edit Alice,Bob}]()", "[{ALLOW view All}]",
    };

    private static final String[] TEXTS = {
        "Para.", "Some text here", "Text *em* more", "Use `code ^c` here", "x^2 and 2^10", "A [[Page]] link",
        "Sum $a^b$ then", "Line with trailing  ", "Text {.cls}", "Ends in a rule",
    };

    private static final String[] PREFIXES = {
        "", "", "", "", "> ", ">", "- ", "1. ", "  ", "  - ", "    ", "\t", "> - ", "* ",
    };

    private final Random random;

    public BlockTextGenerator( final long seed ) {
        this.random = new Random( seed );
    }

    /** The next text. */
    public String next() {
        final StringBuilder sb = new StringBuilder();
        final int lines = 2 + random.nextInt( 9 );
        for ( int i = 0; i < lines; i++ ) {
            if ( i > 0 ) {
                sb.append( '\n' );
            }
            sb.append( line() );
        }
        if ( random.nextInt( 4 ) > 0 ) {
            sb.append( '\n' );
        }
        final String text = sb.toString();
        return random.nextInt( 4 ) == 0 ? text.replace( "\n", "\r\n" ) : text;
    }

    private String pick( final String[] from ) {
        return from[ random.nextInt( from.length ) ];
    }

    private String marker() {
        return "^" + pick( IDS );
    }

    private String rule() {
        return pick( RULES );
    }

    private String line() {
        return switch ( random.nextInt( 30 ) ) {
            case 0, 1, 2 -> "";
            case 3 -> "# Heading";
            case 4 -> "## Heading " + marker();
            case 5 -> random.nextBoolean() ? "===" : "---";
            case 6 -> "```\ncode " + marker() + "\n```";
            case 7 -> "$$\nx^2 " + marker() + "\n$$";
            case 8 -> "| a | b |\n|---|---|\n| 1 | 2 |";
            case 9 -> "> [!note] Title";
            case 10, 11 -> pick( PREFIXES ) + rule();
            case 12 -> pick( PREFIXES ) + rule() + pick( new String[]{ " ", "\t", "    ", "", "  " } ) + marker()
                    + pick( new String[]{ "", " ", "\t", "\u000B", "\f" } );
            case 13 -> pick( PREFIXES ) + marker() + " " + rule();
            case 14 -> pick( PREFIXES ) + marker();
            case 15 -> pick( new String[]{ "    ", "  ", "\t", "   " } ) + marker();
            case 16 -> pick( PREFIXES ) + pick( TEXTS ) + " " + rule() + pick( new String[]{ "", " more", " " + marker() } );
            case 17 -> pick( PREFIXES ) + pick( TEXTS ) + " " + marker() + " " + rule();
            case 18 -> pick( PREFIXES ) + "x" + rule();
            case 19 -> pick( PREFIXES ) + pick( TEXTS ) + " " + marker() + pick( new String[]{ "", " ", "\t", "\u000B", "\f" } );
            case 20 -> "  " + rule() + "  " + marker();
            default -> pick( PREFIXES ) + pick( TEXTS );
        };
    }

    /** How many access-rule directives {@code text} holds, written out (the generator's own spellings). */
    public static int ruleCount( final String text ) {
        int count = 0;
        for ( int i = text.indexOf( "[{" ); i >= 0; i = text.indexOf( "[{", i + 2 ) ) {
            if ( text.startsWith( "[{ALLOW", i ) || text.startsWith( "[{DENY", i ) ) {
                count++;
            }
        }
        return count;
    }
}

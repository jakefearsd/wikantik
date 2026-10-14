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
package com.wikantik.auth.acl;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facade's guarantee at span level, on seeded token soups: in whatever {@link AclDirectiveText#strip} returns, the
 * ACL manager's text scan finds no directive and the render path reads no rule (both re-run on the output). The
 * fragments are the shapes that broke earlier rounds: {@code $$} math next to fences, HTML blocks and blank lines,
 * footnotes, attribute text, frontmatter, and the wrapped, upper-case, {@code DENY} and {@code ALLOWED} rule shapes. Stripping
 * also never creates a frontmatter block nor destroys one, at the start of the text or of its body.
 */
class AclDirectiveTextGuaranteeFuzzTest {

    private static final int INPUTS = 120_000;

    private static final String[] FRAGMENTS = {
            "[{ALLOW view pZa}]", "[{ALLOW edit pZb,\n pZc}]", "[{DENY view pZd}]()", "[{ALLOW VIEW pZe}]", "[{ALLOWED edit pZf}]",
            "[{ALLOW view pZg}](x)", "[{ ALLOW edit pZh}]", "[{ALLOW edit pZi", "}]", "}]()", "]()", "[{SET x=y}]",
            "\n$$\n", "$$", "\n$$\nx = 1\n$$\n", "  $$\n", "$", "`", "```\n", "\n```\n", "~~~\n", "<div>\n", "\n</div>\n",
            "<details>\n", "<!--", "-->", "\n[^1]: ", "[^1]", "{.c}", "{#i}", "{edit ", "---\n", "\n---\n", "x", " ", "\n",
            "\n\n", "\r\n", "\n> ", "\n- ", "    ", "[", "]", "(", ")", "\\", "\n| a |\n|---|\n| ", "," };

    @Test
    void nothingEitherReadingFindsSurvivesOnSeededSoups() {
        final Random random = new Random( 0x5A4E5L );
        final List< String > failures = new ArrayList<>();
        int withRules = 0;
        for ( int i = 0; i < INPUTS && failures.size() < 10; i++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int p = random.nextInt( 16 ); p >= 0; p-- ) {
                sb.append( FRAGMENTS[ random.nextInt( FRAGMENTS.length ) ] );
            }
            final String in = sb.toString();
            final String why = violation( in );
            if ( why != null ) {
                failures.add( "#" + i + " " + why + " in=" + esc( in ) );
            }
            if ( !DefaultAclManager.aclDirectives( in ).isEmpty() ) {
                withRules++;
            }
        }
        assertTrue( failures.isEmpty(), failures.size() + " violation(s): " + String.join( "\n", failures ) );
        assertTrue( withRules > INPUTS / 10, "too few soups carry an enforced rule: " + withRules );
    }

    @Test
    void theFailClosedPathLeavesNothingEitherReadingFinds() {
        // The path taken when the render path's reading cannot be had: it must hold the guarantee on its own.
        final Random random = new Random( 0xFA11L );
        final List< String > failures = new ArrayList<>();
        for ( int i = 0; i < INPUTS / 4 && failures.size() < 10; i++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int p = random.nextInt( 16 ); p >= 0; p-- ) {
                sb.append( FRAGMENTS[ random.nextInt( FRAGMENTS.length ) ] );
            }
            final String in = sb.toString();
            final String why = check( in, AclDirectiveText.failClosed( in, "" ) );
            if ( why != null ) {
                failures.add( "#" + i + " " + why + " in=" + esc( in ) );
            }
        }
        assertTrue( failures.isEmpty(), failures.size() + " violation(s): " + String.join( "\n", failures ) );
    }

    /** Why {@code in} breaks the guarantee, or {@code null}. */
    private static String violation( final String in ) {
        final String out;
        try {
            out = AclDirectiveText.strip( in );
        } catch ( final RuntimeException e ) {
            return "strip threw " + e;
        }
        return check( in, out );
    }

    /**
     * Why {@code out} still holds something either reading takes for a rule, or creates or destroys a frontmatter block
     * (the text's own, or one at the start of its body, which an embed renders on its own), or {@code null}.
     */
    private static String check( final String in, final String out ) {
        final String frontmatter = frontmatterChange( in, out );
        if ( frontmatter != null ) {
            return frontmatter + " out=" + esc( out );
        }
        if ( !DefaultAclManager.aclDirectives( out ).isEmpty() ) {
            return "text scan still reads " + DefaultAclManager.aclDirectives( out ) + " out=" + esc( out );
        }
        final List< RenderPathAccessRules.Rule > rules;
        try {
            rules = RenderPathAccessRules.find( out );
        } catch ( final RuntimeException e ) {
            return "the render path cannot read the output: " + e + " out=" + esc( out );
        }
        return rules.isEmpty() ? null : "render path still reads " + rules + " out=" + esc( out );
    }

    /** Whether stripping made a frontmatter block appear or disappear; an empty result (fail closed) is exempt. */
    private static String frontmatterChange( final String in, final String out ) {
        if ( out.isEmpty() ) {
            return null;
        }
        if ( startsFrontmatter( in ) != startsFrontmatter( out ) ) {
            return startsFrontmatter( in ) ? "the frontmatter was destroyed" : "a frontmatter block was created";
        }
        if ( !startsFrontmatter( bodyOf( in ) ) && startsFrontmatter( bodyOf( out ) ) ) {
            return "the body now starts a frontmatter block";
        }
        return null;
    }

    private static boolean startsFrontmatter( final String text ) {
        return FrontmatterParser.bodyOffset( text ) > 0;
    }

    private static String bodyOf( final String text ) {
        return text.substring( FrontmatterParser.bodyOffset( text ) );
    }

    private static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" );
    }
}

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

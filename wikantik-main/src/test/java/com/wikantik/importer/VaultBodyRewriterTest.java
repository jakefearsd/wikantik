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
package com.wikantik.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class VaultBodyRewriterTest {

    private static final Map< String, LinkTarget > PAGES = Map.of(
            "Old name", LinkTarget.rename( "New name" ),
            "Same", LinkTarget.rename( "Same" ),
            "Existing", LinkTarget.keep() );
    private static final Map< String, LinkTarget > FILES = Map.of(
            "f.png", LinkTarget.rename( "Owner/f.png" ),
            "assets/f.png", LinkTarget.rename( "Owner/f.png" ),
            "doc.pdf", LinkTarget.rename( "Owner/doc.pdf" ),
            "evil.svg", LinkTarget.keep() );
    private static final VaultTargets FAKE = new VaultTargets() {
        @Override public LinkTarget page( final String t, final String from, final boolean rel ) {
            return PAGES.getOrDefault( t, LinkTarget.unresolved() );
        }
        @Override public LinkTarget attachment( final String t, final String from, final boolean rel ) {
            return FILES.getOrDefault( t, LinkTarget.unresolved() );
        }
    };

    private final VaultBodyRewriter rewriter = new VaultBodyRewriter();

    private static final String[][] ROWS = {
        { "[[Old name]]", "[[New name|Old name]]" },
        { "[[Old name|Shown]]", "[[New name|Shown]]" },
        { "[[Old name#Intro]]", "[[New name#Intro|Old name > Intro]]" },
        { "![[Old name]]", "![[New name]]" },
        { "[[Same]]", "[[Same]]" },
        { "[[Existing]]", "[[Existing]]" },
        { "[[Same#^abc]]", "[[Same]]" },
        { "[[Ghost]]", "[[Ghost]]" },
        { "![[f.png|300]]", "![[Owner/f.png|300]]" },
        { "[[doc.pdf]]", "[[Owner/doc.pdf]]" },
        { "[[doc.pdf|Doc]]", "[[Owner/doc.pdf|Doc]]" },
        { "![[evil.svg]]", "![[evil.svg]]" },
        { "| [[Old name\\|x]] |", "| [[New name\\|x]] |" },
        { "![alt](assets/f.png)", "![[Owner/f.png]]" },
        { "[read](doc.pdf)", "[[Owner/doc.pdf|read]]" },
        { "[x](Old%20name.md)", "[[New name|x]]" },
        { "[x](<Old name.md#Intro>)", "[[New name#Intro|x]]" },
        { "[x](Existing.md)", "[[Existing|x]]" },
        { "[x](https://e.com/a.md)", "[x](https://e.com/a.md)" },
        { "para ^block-1", "para" },
        { "a %% hidden\nmore %% b", "a  b" },
        { "```\n[[Old name]] %% c %%\n```", "```\n[[Old name]] %% c %%\n```" },
        { "`[[Old name]]`", "`[[Old name]]`" },
        { "[[ -f \"$x\" ]]", "[[ -f \"$x\" ]]" },
        { "[![img](assets/f.png)](Old name.md)", "[![[Owner/f.png]]](Old name.md)" },
        { "| [[Old name]] | b |", "| [[New name\\|Old name]] | b |" },
        { "| [x](Old%20name.md) |", "| [[New name\\|x]] |" },
        { "[[A|x\\|y]]", "[[A|x\\|y]]" },
        { "[[Old name|x\\|y]]", "[[New name|x\\|y]]" },
        { "[[doc.pdf#page=2]]", "[[Owner/doc.pdf#page=2]]" },
        { "[d](doc.pdf#page=2)", "[[Owner/doc.pdf#page=2|d]]" },
        { "[x](//cdn/x.md) [y](www.x.com/a.md)", "[x](//cdn/x.md) [y](www.x.com/a.md)" },
        { "`x`^abc", "`x`^abc" },
        { "`a %% b` text %% x", "`a %% b` text %% x" },
        { "![x](Old%20name.md)", "![[New name]]" },
        { "[x](ghost.md)", "[x](ghost.md)" },
        { "[x](evil.svg)", "[x](evil.svg)" },
        { "[x](Old%zzname.md)", "[x](Old%zzname.md)" },
        { "[[#^x]]", "[[#^x]]" },
        { "[[Old name#a#^x]]", "[[New name#a|Old name > a]]" },
    };

    @Test
    void table() {
        for ( final String[] row : ROWS ) {
            assertEquals( row[ 1 ], rewriter.rewrite( row[ 0 ], "Notes/Here.md", FAKE ).body(), row[ 0 ] );
        }
    }

    @Test
    void unresolvedMarkdownLinkWarns() {
        assertEquals( java.util.List.of( "link: unresolved [[ghost]]" ),
                rewriter.rewrite( "[x](ghost.md)", "n.md", FAKE ).warnings() );
    }

    @Test
    void warnings() {
        assertTrue( rewriter.rewrite( "[[Same#^abc]]", "n.md", FAKE ).warnings().get( 0 ).startsWith( "blockref:" ) );
        assertEquals( java.util.List.of( "link: unresolved [[Ghost]]" ),
                rewriter.rewrite( "[[Ghost]] [[Ghost]]", "n.md", FAKE ).warnings() );
        assertTrue( rewriter.rewrite( "a %%x%% b", "n.md", FAKE ).warnings().get( 0 ).startsWith( "comment: 1" ) );
        assertTrue( rewriter.rewrite( "para ^block-1", "n.md", FAKE ).warnings().get( 0 ).startsWith( "blockref:" ) );
        assertTrue( rewriter.rewrite( "[[Old name]]", "n.md", FAKE ).warnings().isEmpty() );
    }
}

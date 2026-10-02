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
package com.wikantik.export;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObsidianPageConverterTest {
    private final ObsidianPageConverter conv = new ObsidianPageConverter();

    private static final class FakeCtx implements ExportLinkContext {
        Set< String > pages = Set.of( "Foo", "Bar", "Here" );
        Map< String, Map< String, String > > headings = Map.of(
                "Foo", Map.of( "setup-steps", "Setup Steps" ),
                "Here", Map.of( "intro", "Intro" ) );
        Set< String > attachments = Set.of( "Here/chart.png", "Here/report.pdf", "Foo/diagram.svg" );
        UnresolvedLinkMode mode = UnresolvedLinkMode.KEEP;
        Map< String, String > basenames = Map.of();
        public boolean inExport( String p ) { return pages.contains( p ); }
        public Optional< String > headingText( String p, String s ) { return Optional.ofNullable( headings.getOrDefault( p, Map.of() ).get( s ) ); }
        public Optional< String > attachmentTarget( String p, String f ) { return attachments.contains( p + "/" + f ) ? Optional.of( f ) : Optional.empty(); }
        public Optional< String > slugForCanonicalId( String id ) { return "01FOO".equals( id ) ? Optional.of( "Foo" ) : Optional.empty(); }
        public Optional< String > vaultBasename( String p ) {
            return pages.contains( p ) ? Optional.of( basenames.getOrDefault( p, p ) ) : Optional.empty();
        }
        public String liveUrl( String p ) { return "https://w.example/wiki/" + p; }
        public UnresolvedLinkMode unresolvedMode() { return mode; }
    }

    private String body( final String raw ) { return body( raw, new FakeCtx() ); }
    private String body( final String raw, final FakeCtx ctx ) {
        final String md = conv.convert( "Here", raw, Map.of(), ctx ).markdown();
        return md.substring( md.indexOf( "---\n", 4 ) + 4 );   // strip the generated frontmatter block
    }

    @Test void internalLink() { assertEquals( "See [[Foo|the foo]].\n", body( "See [the foo](Foo).\n" ) ); }
    @Test void internalLinkSameText() { assertEquals( "[[Foo]]\n", body( "[Foo](Foo)\n" ) ); }
    @Test void anchorResolved() { assertEquals( "[[Foo#Setup Steps|go]]\n", body( "[go](Foo#setup-steps)\n" ) ); }
    @Test void anchorUnresolvedFallsBack() { assertEquals( "[[Foo|go]]\n", body( "[go](Foo#nope)\n" ) ); }
    @Test void samePageAnchor() { assertEquals( "[[#Intro|up]]\n", body( "[up](#intro)\n" ) ); }
    @Test void outOfScopeKeep() { assertEquals( "[[Elsewhere|x]]\n", body( "[x](Elsewhere)\n" ) ); }
    @Test void outOfScopeUrl() {
        final FakeCtx c = new FakeCtx(); c.mode = UnresolvedLinkMode.URL;
        assertEquals( "[x](https://w.example/wiki/Elsewhere)\n", body( "[x](Elsewhere)\n", c ) );
    }
    @Test void externalUntouched() { assertEquals( "[g](https://google.com)\n", body( "[g](https://google.com)\n" ) ); }
    @Test void linkInsideCodeUntouched() {
        final String raw = "`[a](Foo)`\n\n```\n[b](Foo)\n[{ALLOW view Admin}]\n```\n";
        assertEquals( raw, body( raw ) );
    }
    @Test void imageAttachment() {
        final ConvertedPage p = conv.convert( "Here", "![c](Here/chart.png)\n", Map.of(), new FakeCtx() );
        assertTrue( p.markdown().endsWith( "![[chart.png]]\n" ) );
        assertEquals( List.of( new AttachmentRef( "Here", "chart.png" ) ), p.attachments() );
    }
    @Test void bareAttachmentOfCurrentPage() { assertEquals( "[[report.pdf|Report]]\n", body( "[Report](report.pdf)\n" ) ); }
    @Test void imageLinkSyntaxToAttachment() { assertEquals( "![[diagram.svg]]\n", body( "[d](Foo/diagram.svg)\n" ) ); }
    @Test void citation() {
        final String out = body( "Claim [it works](cite://01FOO/Setup%20Steps \"exact span\") ok.\n" );
        assertEquals( "Claim [[Foo#Setup Steps|it works]][^c1] ok.\n\n[^c1]: exact span\n", out );
    }
    @Test void citationInTableCellEscapesAliasPipe() {
        final String raw = "| a | b |\n|---|---|\n| [it](cite://01FOO/Setup%20Steps \"span\") | y |\n";
        assertEquals( "| a | b |\n|---|---|\n| [[Foo#Setup Steps\\|it]][^c1] | y |\n\n[^c1]: span\n", body( raw ) );
    }
    /** A case-collided page is written as {@code Foo~2.md}; links must target that file, keeping the page name as alias. */
    @Test void caseCollidedTargetUsesVaultBasename() {
        final FakeCtx c = new FakeCtx(); c.basenames = Map.of( "Foo", "Foo~2" );
        assertEquals( "[[Foo~2|Foo]] [[Foo~2|the foo]] [[Foo~2#Setup Steps|go]]\n",
                body( "[Foo](Foo) [the foo](Foo) [go](Foo#setup-steps)\n", c ) );
        assertEquals( "Claim [[Foo~2#Setup Steps|it]][^c1].\n\n[^c1]: s\n",
                body( "Claim [it](cite://01FOO/Setup%20Steps \"s\").\n", c ) );
        assertEquals( "![[Foo~2]]\n", body( "[{InsertPage page='Foo'}]\n", c ) );
    }
    @Test void aclAndSetRemoved() {
        assertEquals( "Text\n", body( "[{ALLOW view Admin}]\n[{SET foo=bar}]()\nText\n" ) );
    }
    @Test void tocRemoved() { assertEquals( "# H\n", body( "[{TableOfContents}]\n# H\n" ) ); }
    @Test void insertPageEmbeds() { assertEquals( "![[Bar]]\n", body( "[{InsertPage page='Bar'}]\n" ) ); }
    @Test void otherPluginOwnLineBecomesCallout() {
        assertEquals( "> [!note] Wiki plugin omitted: Relationships — [view on the wiki](https://w.example/wiki/Here)\n",
                body( "[{Relationships depth=2}]\n" ) );
    }
    @Test void otherPluginInline() { assertEquals( "Now *(wiki plugin omitted: CurrentTimePlugin)* ok\n", body( "Now [{CurrentTimePlugin}] ok\n" ) ); }
    @Test void tableCellPipeEscaped() {
        final String raw = "| a | b |\n|---|---|\n| [x](Foo) | y |\n";
        assertEquals( "| a | b |\n|---|---|\n| [[Foo\\|x]] | y |\n", body( raw ) );
    }
    @Test void mathUntouched() {
        final String raw = "Inline $x_1$ and\n\n$$\n\\mathbb{E}[X]\n$$\n";
        assertEquals( raw, body( raw ) );
    }
    @Test void untouchedBytesIdentical() {
        final String raw = "Some *emphasis*,  double  spaces,\ttabs and a [x](Foo).\n\n- list\n";
        assertEquals( "Some *emphasis*,  double  spaces,\ttabs and a [[Foo|x]].\n\n- list\n", body( raw ) );
    }
    @Test void aliasBracketsStripped() { assertEquals( "[[Foo|a b]]\n", body( "[a [b]](Foo)\n" ) ); }
    @Test void frontmatterPreservedAndExtended() {
        final String raw = "---\ntype: article\ndate: 2026-01-15\n---\nx\n";
        final String md = conv.convert( "Here", raw, Map.of( "wikantik_version", "4" ), new FakeCtx() ).markdown();
        assertEquals( "---\ntype: article\ndate: 2026-01-15\nwikantik_version: \"4\"\n---\nx\n", md );
    }

    @Test void nativeWikilinksRemapToVaultBasenamesAndCollectAttachments() {
        final FakeCtx c = new FakeCtx();
        c.pages = Set.of( "Here", "Hub Page" );
        c.basenames = Map.of( "Hub Page", "Hub Page~2" );
        c.attachments = Set.of( "Hub Page/pic.png" );
        final ConvertedPage p = conv.convert( "Here", "[[Hub Page|h]] ![[Hub Page#Usage]] ![[Hub Page/pic.png|300]]\n",
                Map.of(), c );
        assertTrue( p.markdown().endsWith( "[[Hub Page~2|h]] ![[Hub Page~2#Usage]] ![[pic.png|300]]\n" ), p.markdown() );
        assertEquals( List.of( new AttachmentRef( "Hub Page", "pic.png" ) ), p.attachments() );
    }
    @Test void nativeWikilinkInCodeAndSamePageUntouched() {
        final String raw = "`[[Foo]]` [[#Intro]]\n";
        assertEquals( raw, body( raw ) );
    }
}

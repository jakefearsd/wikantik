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
package com.wikantik.mentions;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInlineBase;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gitlab.GitLabInlineMath;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.api.parser.MarkdownLinkScanner;
import com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry;
import com.wikantik.markdown.extensions.math.InlineMathParser;
import com.wikantik.parser.markdown.MarkdownDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Finds phrases in a draft that name another page (its de-CamelCased name, title or an alias) but are not
 * linked. Only plain prose is eligible: text in paragraphs, list items, blockquotes and table cells, never in
 * headings, code, math, links, images, HTML, plugin markup, bare URLs or frontmatter. Matching is whole-word,
 * case-insensitive, over word tokens, with the longest phrase winning. Pure: no wiki state.
 */
public final class MentionScanner {

    public static final int MAX_RESULTS = 50;
    static final int MIN_PHRASE_CHARS = 4;
    private static final int CONTEXT_CHARS = 30;

    /** Single words too generic to suggest as links on their own. */
    static final Set< String > COMMON_WORDS = Set.of(
            "about", "api", "architecture", "change", "changes", "config", "configuration", "content", "data",
            "design", "editor", "example", "examples", "faq", "glossary", "guide", "help", "history", "home",
            "index", "install", "installation", "introduction", "issue", "issues", "links", "list", "lists", "main",
            "model", "models", "notes", "overview", "page", "pages", "performance", "plan", "plans", "problem",
            "process", "project", "projects", "reference", "release", "releases", "resources", "roadmap", "search",
            "security", "setup", "solution", "status", "summary", "system", "systems", "task", "tasks", "test",
            "testing", "tests", "tool", "tools", "update", "updates", "usage" );

    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();
    private static final Set< Class< ? extends Node > > INELIGIBLE = Set.of(
            Heading.class, Code.class, FencedCodeBlock.class, IndentedCodeBlock.class, HtmlBlock.class,
            Link.class, LinkRef.class, Image.class, ImageRef.class, AutoLink.class, MailLink.class,
            GitLabInlineMath.class );

    private MentionScanner() {}

    public static List< Mention > scan( final String text, final String selfPage, final List< TitleEntry > entries ) {
        if ( text == null || text.isBlank() ) {
            return List.of();
        }
        final Set< String > linked = MarkdownLinkScanner.findLocalLinks( text ).stream()
                .map( s -> s.toLowerCase( Locale.ROOT ) ).collect( Collectors.toSet() );
        final PhraseTrie root = PhraseTrie.build( entries, selfPage, linked );
        final String masked = MentionMasking.mask( text );

        final Map< String, Mention > firstByTarget = new LinkedHashMap<>();
        final Map< String, Integer > extra = new HashMap<>();
        for ( final Node n : PARSER.parse( masked ).getDescendants() ) {
            if ( n instanceof Text t && eligible( t ) ) {
                matchSegment( text, masked, t.getStartOffset(), t.getEndOffset(), root, firstByTarget, extra );
            }
        }
        return firstByTarget.values().stream()
                .map( m -> new Mention( m.target(), m.title(), m.phrase(), m.from(), m.to(), m.line(), m.context(),
                        extra.getOrDefault( m.target(), 0 ) ) )
                .sorted( Comparator.comparingInt( Mention::from ) )
                .toList();
    }

    private static boolean eligible( final Text t ) {
        boolean inProse = false;
        for ( Node p = t.getParent(); p != null; p = p.getParent() ) {
            if ( INELIGIBLE.contains( p.getClass() ) || p instanceof HtmlInlineBase ) {
                return false;
            }
            if ( p instanceof Paragraph || p instanceof TableCell ) {
                inProse = true;
            }
        }
        return inProse;
    }

    private static void matchSegment( final String text, final String masked, final int start, final int end, final PhraseTrie root,
                                      final Map< String, Mention > firstByTarget, final Map< String, Integer > extra ) {
        final List< int[] > spans = new ArrayList<>();   // [from, to] per token
        final Matcher m = PhraseTrie.TOKEN.matcher( masked ).region( start, end );
        while ( m.find() ) {
            spans.add( new int[]{ m.start(), m.end() } );
        }
        int i = 0;
        while ( i < spans.size() ) {
            final PhraseTrie.Match match = root.longestMatch( masked, spans, i );
            if ( match == null ) {
                i++;
                continue;
            }
            final TitleEntry hit = match.target();
            final int hitEnd = match.lastToken();
            final int from = spans.get( i )[ 0 ];
            final int to = spans.get( hitEnd )[ 1 ];
            if ( firstByTarget.containsKey( hit.slug() ) ) {
                extra.merge( hit.slug(), 1, Integer::sum );
            } else {
                firstByTarget.put( hit.slug(), new Mention( hit.slug(), hit.title(), text.substring( from, to ),
                        from, to, lineOf( text, from ), context( text, from, to ), 0 ) );
            }
            i = hitEnd + 1;
        }
    }

    private static int lineOf( final String text, final int offset ) {
        int line = 1;
        for ( int i = 0; i < offset; i++ ) {
            if ( text.charAt( i ) == '\n' ) {
                line++;
            }
        }
        return line;
    }

    private static String context( final String text, final int from, final int to ) {
        final int lineStart = text.lastIndexOf( '\n', from - 1 ) + 1;
        int lineEnd = text.indexOf( '\n', to );
        if ( lineEnd < 0 ) {
            lineEnd = text.length();
        }
        final int a = Math.max( lineStart, from - CONTEXT_CHARS );
        final int b = Math.min( lineEnd, to + CONTEXT_CHARS );
        return ( a > lineStart ? "…" : "" ) + text.substring( a, b ).strip() + ( b < lineEnd ? "…" : "" );
    }
}

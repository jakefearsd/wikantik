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
import java.util.regex.Pattern;
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
    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{.*?}]", Pattern.DOTALL );
    private static final Pattern BARE_URL = Pattern.compile( "\\b(?:https?://|www\\.)[^\\s)>\\]\"]+" );
    private static final Pattern TOKEN = Pattern.compile( "[\\p{L}\\p{N}]+" );
    private static final Set< Class< ? extends Node > > INELIGIBLE = Set.of(
            Heading.class, Code.class, FencedCodeBlock.class, IndentedCodeBlock.class, HtmlBlock.class,
            Link.class, LinkRef.class, Image.class, ImageRef.class, AutoLink.class, MailLink.class,
            GitLabInlineMath.class );

    private MentionScanner() {}

    /** A phrase trie keyed by lowercase word tokens; terminals name the target page. */
    private static final class Trie {
        final Map< String, Trie > next = new HashMap<>();
        TitleEntry target;
    }

    public static List< Mention > scan( final String text, final String selfPage, final List< TitleEntry > entries ) {
        if ( text == null || text.isBlank() ) {
            return List.of();
        }
        final Set< String > linked = MarkdownLinkScanner.findLocalLinks( text ).stream()
                .map( s -> s.toLowerCase( Locale.ROOT ) ).collect( Collectors.toSet() );
        final Trie root = buildTrie( entries, selfPage, linked );
        final String masked = mask( text );

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

    private static Trie buildTrie( final List< TitleEntry > entries, final String selfPage, final Set< String > linked ) {
        final Trie root = new Trie();
        final List< TitleEntry > sorted = entries.stream()
                .sorted( Comparator.comparing( TitleEntry::slug ) ).toList();  // deterministic winner on collisions
        for ( final TitleEntry e : sorted ) {
            if ( e.slug().equalsIgnoreCase( selfPage ) || linked.contains( e.slug().toLowerCase( Locale.ROOT ) ) ) {
                continue;
            }
            for ( final String phrase : e.phrases() ) {
                final List< String > tokens = tokens( phrase );
                if ( tokens.isEmpty() || phrase.trim().length() < MIN_PHRASE_CHARS
                        || ( tokens.size() == 1 && COMMON_WORDS.contains( tokens.get( 0 ) ) ) ) {
                    continue;
                }
                Trie node = root;
                for ( final String tok : tokens ) {
                    node = node.next.computeIfAbsent( tok, k -> new Trie() );
                }
                if ( node.target == null ) {
                    node.target = e;
                }
            }
        }
        return root;
    }

    /**
     * Replaces frontmatter, plugin spans and bare URLs with spaces (newlines kept) so they can't match, while
     * every offset and line number stays identical to {@code text}.
     */
    static String mask( final String text ) {
        final char[] chars = text.toCharArray();
        final int fmEnd = frontmatterEnd( text );
        for ( int i = 0; i < fmEnd; i++ ) {
            blank( chars, i );
        }
        for ( final Pattern p : List.of( PLUGIN, BARE_URL ) ) {
            final Matcher m = p.matcher( text );
            while ( m.find() ) {
                for ( int i = m.start(); i < m.end(); i++ ) {
                    blank( chars, i );
                }
            }
        }
        return new String( chars );
    }

    private static void blank( final char[] chars, final int i ) {
        if ( chars[ i ] != '\n' && chars[ i ] != '\r' ) {
            chars[ i ] = ' ';
        }
    }

    /** Offset just past a leading {@code ---} … {@code ---} frontmatter block, or 0 when there is none. */
    static int frontmatterEnd( final String text ) {
        if ( !text.startsWith( "---" ) ) {
            return 0;
        }
        final Matcher close = Pattern.compile( "\\r?\\n---[ \\t]*(\\r?\\n|$)" ).matcher( text );
        return close.find( 3 ) ? close.end() : 0;
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

    private static void matchSegment( final String text, final String masked, final int start, final int end, final Trie root,
                                      final Map< String, Mention > firstByTarget, final Map< String, Integer > extra ) {
        final List< int[] > spans = new ArrayList<>();   // [from, to] per token
        final Matcher m = TOKEN.matcher( masked ).region( start, end );
        while ( m.find() ) {
            spans.add( new int[]{ m.start(), m.end() } );
        }
        int i = 0;
        while ( i < spans.size() ) {
            Trie node = root;
            TitleEntry hit = null;
            int hitEnd = -1;
            for ( int j = i; j < spans.size(); j++ ) {
                node = node.next.get( masked.substring( spans.get( j )[ 0 ], spans.get( j )[ 1 ] ).toLowerCase( Locale.ROOT ) );
                if ( node == null ) {
                    break;
                }
                if ( node.target != null ) {
                    hit = node.target;
                    hitEnd = j;
                }
            }
            if ( hit == null ) {
                i++;
                continue;
            }
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

    private static List< String > tokens( final String phrase ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = TOKEN.matcher( phrase );
        while ( m.find() ) {
            out.add( m.group().toLowerCase( Locale.ROOT ) );
        }
        return out;
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

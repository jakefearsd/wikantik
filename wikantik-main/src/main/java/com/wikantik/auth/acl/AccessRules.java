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
import com.wikantik.util.AclDirectiveScanner;
import com.wikantik.util.OffsetRanges;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one reader of a page's access rules: {@link #read} is the only source of the ACL the wiki enforces
 * ({@code DefaultAclManager} builds it from the {@link Reading}, and nothing else sets a page's ACL).
 *
 * <p>Two readings used to feed enforcement and disagreed on several shapes: the text scan (one-line
 * <code>[{ALLOW perm …}]</code> anywhere outside Markdown code, frontmatter included) and the render path (any link
 * label starting <code>{ALLOW</code> or <code>{DENY</code>, body only, real Markdown structure). This reader sees
 * every shape either could take for a rule, and accepts a span as a rule only where both agree on it exactly:
 * {@link Shape#CANONICAL}. Every other directive-shaped span is <em>disputed</em> and {@link Locked locks} the page
 * (only administrators may then use it) rather than guess which reading the author meant.
 *
 * <p>How a span is found and classified:
 * <ul>
 *   <li>Candidates are every span either old reading takes for a rule, plus every <code>[{</code> whose first word
 *       starts {@code allow} or {@code deny} in any case, up to the first <code>}]</code> with no blank line between
 *       (in the frontmatter and in the body, each on its own).</li>
 *   <li>The body is parsed once, the way the renderer parses it ({@link RenderPathAccessRules#structure}, bounded:
 *       a parse that runs over its budget or throws makes the whole text {@link Shape#UNREADABLE}).</li>
 *   <li>A candidate in code that neither reading takes for a rule is literal text, not a span at all. Otherwise its
 *       region decides first ({@link Shape#IN_FRONTMATTER}, {@link Shape#IN_MATH}, {@link Shape#IN_HTML_BLOCK},
 *       {@link Shape#LINK_WITH_TARGET}, {@link Shape#ESCAPED}), then its spelling ({@link Shape#DENY},
 *       {@link Shape#ALLOW_PREFIXED_WORD}, {@link Shape#BAD_PERMISSION}, {@link Shape#WRAPPED},
 *       {@link Shape#LEADING_SPACE}, {@link Shape#CASE_VARIANT}). A clean spelling both readings take for the same
 *       rule over the same span is {@link Shape#CANONICAL}; one they read differently anyway is
 *       {@link Shape#UNREADABLE}.</li>
 * </ul>
 * A text with no <code>{allow</code> or <code>{deny</code> (any case, any whitespace after the brace) is not parsed.
 */
public final class AccessRules {

    /** How a directive-shaped span is written, and so whether the two old readings agreed on it. */
    public enum Shape {
        /** One line, {@code ALLOW} and a lower-case permission, in body prose: both old readings read it alike. */
        CANONICAL,
        /** Runs over more than one line (no blank line): only the render path read it. */
        WRAPPED,
        /** The keyword or the permission is not in its canonical case ({@code allow}, {@code VIEW}). */
        CASE_VARIANT,
        /** Whitespace between the brace and the keyword: only the text scan read it. */
        LEADING_SPACE,
        /** The opening bracket is backslash-escaped: only the text scan read it. */
        ESCAPED,
        /** {@code DENY}: the ACL model is allow-only; the render path read it as a grant. */
        DENY,
        /** A first word that starts with {@code ALLOW} but is longer ({@code ALLOWED}): only the render path read it. */
        ALLOW_PREFIXED_WORD,
        /** Inside an HTML block, an HTML comment or an inline HTML tag: only the text scan read it. */
        IN_HTML_BLOCK,
        /** Inside inline or display math: only the text scan read it. */
        IN_MATH,
        /** In the frontmatter, which is never rendered: only the text scan read it. */
        IN_FRONTMATTER,
        /** The text of a link that has a target, an image, or a link reference: only the text scan read it. */
        LINK_WITH_TARGET,
        /** A permission that is not a page permission: both readings dropped it, leaving the page open. */
        BAD_PERMISSION,
        /** The text cannot be read the way the renderer reads it, or the readings disagree for no reason above. */
        UNREADABLE
    }

    /**
     * A directive-shaped span of the text.
     *
     * @param start offset of its <code>[</code>
     * @param end   offset just past it (and past an empty {@code ()} after it)
     * @param shape how it is written
     */
    public record Span( int start, int end, Shape shape ) {
    }

    /**
     * Why a page is locked: one disputed span (or the text as a whole, for {@link Shape#UNREADABLE}).
     *
     * @param shape what is wrong
     * @param line  the 1-based line the span starts on
     * @param text  the span's text (cut short when long), or what could not be read
     */
    public record Reason( Shape shape, int line, String text ) {
    }

    /** What {@link #read} makes of a text. */
    public sealed interface Reading permits Rules, Locked {
        /** @return every directive-shaped span of the text that is not literal code, in document order */
        List< Span > spans();
    }

    /**
     * Every span is a rule both old readings agree on.
     *
     * @param ruleLines the rule lines ({@code ALLOW view Admin}) to build the ACL from, in document order
     * @param spans     their spans, one per rule line, in the same order
     */
    public record Rules( List< String > ruleLines, List< Span > spans ) implements Reading {
        public Rules {
            ruleLines = List.copyOf( ruleLines );
            spans = List.copyOf( spans );
        }
    }

    /**
     * At least one span is disputed, or the text is unreadable: only administrators may use the page.
     *
     * @param reasons one per disputed span (or one for an unreadable text)
     * @param spans   every directive-shaped span, disputed or not, in document order
     */
    public record Locked( List< Reason > reasons, List< Span > spans ) implements Reading {
        public Locked {
            reasons = List.copyOf( reasons );
            spans = List.copyOf( spans );
        }
    }

    private static final Logger LOG = LogManager.getLogger( AccessRules.class );

    /** Longest span text a {@link Reason} carries. */
    static final int MAX_REASON_TEXT = 200;

    /** Test seam: how many times {@link #read} has gone past its no-directive shortcut to parse a text. */
    static final AtomicLong PARSES = new AtomicLong();

    private static final Rules NONE = new Rules( List.of(), List.of() );

    private AccessRules() {
    }

    /**
     * Whether a span of this shape locks the page. In this release every shape but {@link Shape#CANONICAL} does.
     *
     * @param shape a span's shape
     * @return {@code true} when the span locks the page
     */
    static boolean disputed( final Shape shape ) {
        return shape != Shape.CANONICAL;
    }

    /**
     * @param fullText raw page text, frontmatter included; may be {@code null}
     * @return the reading of its access rules; never {@code null}
     */
    public static Reading read( final String fullText ) {
        if ( fullText == null || !DirectiveCandidates.mayHoldDirective( fullText, 0, fullText.length() ) ) {
            return NONE;
        }
        final int bodyStart = FrontmatterParser.bodyOffset( fullText );
        final TreeMap< Integer, Integer > candidates = DirectiveCandidates.find( fullText, bodyStart );
        if ( !DirectiveCandidates.mayHoldDirective( fullText, bodyStart, fullText.length() ) ) {
            // Only the frontmatter holds anything directive-shaped: no body to parse, and every candidate is disputed.
            return reading( fullText, frontmatterOnly( candidates, bodyStart ), Map.of() );
        }
        PARSES.incrementAndGet();
        final RenderPathAccessRules.Structure structure;
        try {
            structure = RenderPathAccessRules.structure( fullText );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            // Not an error here: the lock it becomes is what is reported (DefaultAclManager warns once per version).
            LOG.debug( "Access rules read as unreadable: {}", e.getMessage() );
            return unreadable( fullText, bodyStart, candidates, e.getMessage() );
        }
        final Map< Integer, RenderPathAccessRules.Rule > rules = new HashMap<>();
        for ( final RenderPathAccessRules.Rule rule : structure.rules() ) {
            rules.put( rule.start(), rule );
            candidates.putIfAbsent( rule.start(), rule.end() );
        }
        final Map< Integer, Integer > scanned = new HashMap<>();
        for ( final AclDirectiveScanner.Span span : EnforcedAclDirectives.spans( fullText ) ) {
            scanned.put( span.start(), AclDirectiveScanner.endWithEmptyParentheses( fullText, span ) );
        }
        final Regions regions = new Regions( structure );
        final List< Span > spans = new ArrayList<>( candidates.size() );
        for ( final Map.Entry< Integer, Integer > candidate : candidates.entrySet() ) {
            final Shape shape = classify( fullText, bodyStart, candidate.getKey(), candidate.getValue(), regions, rules, scanned );
            if ( shape != null ) {
                spans.add( new Span( candidate.getKey(), candidate.getValue(), shape ) );
            }
        }
        return reading( fullText, spans, rules );
    }

    /** The shape of one candidate, or {@code null} when it is literal text in code that neither old reading read. */
    private static Shape classify( final String text, final int bodyStart, final int start, final int end,
                                   final Regions regions, final Map< Integer, RenderPathAccessRules.Rule > rules,
                                   final Map< Integer, Integer > scanned ) {
        if ( start < bodyStart ) {
            return Shape.IN_FRONTMATTER;
        }
        final RenderPathAccessRules.Rule rule = rules.get( start );
        if ( regions.code().contains( start ) ) {
            // Code to the render path; literal only when the text scan's own code exclusion agrees.
            return rule == null && !scanned.containsKey( start ) ? null : Shape.UNREADABLE;
        }
        if ( regions.math().contains( start ) ) {
            return Shape.IN_MATH;
        }
        if ( regions.html().contains( start ) ) {
            return Shape.IN_HTML_BLOCK;
        }
        if ( regions.links().contains( start ) ) {
            return Shape.LINK_WITH_TARGET;
        }
        if ( DirectiveSpelling.escaped( text, start ) ) {
            return Shape.ESCAPED;
        }
        final Shape spelling = DirectiveSpelling.of( text, start, end );
        if ( spelling != null ) {
            return spelling;
        }
        final Integer scannedEnd = scanned.get( start );
        if ( rule != null && rule.end() == end && scannedEnd != null && scannedEnd == end ) {
            return Shape.CANONICAL;
        }
        return Shape.UNREADABLE;
    }

    private static Reading reading( final String text, final List< Span > spans,
                                    final Map< Integer, RenderPathAccessRules.Rule > rules ) {
        final List< Reason > reasons = new ArrayList<>();
        final List< String > ruleLines = new ArrayList<>();
        final LineCounter lines = new LineCounter( text );
        for ( final Span span : spans ) {
            if ( disputed( span.shape() ) ) {
                reasons.add( new Reason( span.shape(), lines.lineOf( span.start() ), excerpt( text, span.start(), span.end() ) ) );
            } else {
                ruleLines.add( rules.get( span.start() ).ruleLine() );
            }
        }
        return reasons.isEmpty() ? new Rules( ruleLines, spans ) : new Locked( reasons, spans );
    }

    private static Locked unreadable( final String text, final int bodyStart, final TreeMap< Integer, Integer > candidates,
                                      final String why ) {
        final List< Span > spans = new ArrayList<>( candidates.size() );
        for ( final Map.Entry< Integer, Integer > candidate : candidates.entrySet() ) {
            spans.add( new Span( candidate.getKey(), candidate.getValue(),
                    candidate.getKey() < bodyStart ? Shape.IN_FRONTMATTER : Shape.UNREADABLE ) );
        }
        final LineCounter lines = new LineCounter( text );
        final Integer firstInBody = candidates.ceilingKey( bodyStart );
        final List< Reason > reasons = new ArrayList<>();
        reasons.add( new Reason( Shape.UNREADABLE, firstInBody == null ? 1 : lines.lineOf( firstInBody ),
                "the page text cannot be read the way the renderer reads it: " + why ) );
        return new Locked( reasons, spans );
    }

    private static List< Span > frontmatterOnly( final TreeMap< Integer, Integer > candidates, final int bodyStart ) {
        final List< Span > spans = new ArrayList<>( candidates.size() );
        for ( final Map.Entry< Integer, Integer > candidate : candidates.entrySet() ) {
            spans.add( new Span( candidate.getKey(), candidate.getValue(),
                    candidate.getKey() < bodyStart ? Shape.IN_FRONTMATTER : Shape.UNREADABLE ) );
        }
        return spans;
    }

    /** The parse's regions, merged per kind for a logarithmic lookup. */
    private record Regions( OffsetRanges code, OffsetRanges math, OffsetRanges html, OffsetRanges links ) {
        Regions( final RenderPathAccessRules.Structure structure ) {
            this( OffsetRanges.of( structure.code() ), OffsetRanges.of( structure.math() ), OffsetRanges.of( structure.html() ),
                  OffsetRanges.of( structure.links() ) );
        }
    }

    /**
     * {@code text} less {@code spans} — how an ACL rewrite removes the rules a reading found before writing new ones.
     *
     * @param text  the text the spans were read from
     * @param spans spans in document order; overlapping ones are cut once
     * @return the text without them
     */
    static String without( final String text, final List< Span > spans ) {
        final StringBuilder out = new StringBuilder( text.length() );
        int copiedTo = 0;
        for ( final Span span : spans ) {
            if ( span.start() > copiedTo ) {
                out.append( text, copiedTo, span.start() );
            }
            copiedTo = Math.max( copiedTo, span.end() );
        }
        return out.append( text, Math.min( copiedTo, text.length() ), text.length() ).toString();
    }

    /**
     * @param text  a page text
     * @param rules printed rules to add
     * @return {@code rules} put at the start of {@code text}'s body: after its frontmatter, which stays what it was
     *         (a closing {@code ---} with no line end after it gets one, in the frontmatter's own style, so the rules
     *         start a line of their own and the block still closes)
     */
    static String withRules( final String text, final String rules ) {
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        final String head = text.substring( 0, bodyStart );
        final String lineEnd = bodyStart == 0 || head.endsWith( "\n" ) ? "" : head.contains( "\r\n" ) ? "\r\n" : "\n";
        return head + lineEnd + rules + text.substring( bodyStart );
    }

    private static String excerpt( final String text, final int start, final int end ) {
        return end - start <= MAX_REASON_TEXT ? text.substring( start, end ) : text.substring( start, start + MAX_REASON_TEXT ) + "…";
    }

    /**
     * @param text   a text
     * @param offset an offset in it
     * @return the 1-based line {@code offset} is on
     */
    static int lineOf( final String text, final int offset ) {
        return new LineCounter( text ).lineOf( Math.min( offset, text.length() ) );
    }

    /** 1-based line numbers for ascending offsets, counted once across the text. */
    private static final class LineCounter {
        private final String text;
        private int pos;
        private int line = 1;

        LineCounter( final String text ) {
            this.text = text;
        }

        int lineOf( final int offset ) {
            if ( offset < pos ) {
                pos = 0;
                line = 1;
            }
            for ( ; pos < offset; pos++ ) {
                if ( text.charAt( pos ) == '\n' ) {
                    line++;
                }
            }
            return line;
        }
    }
}

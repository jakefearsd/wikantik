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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reader of a page's access rules: {@link #read} is the only source of the ACL the wiki enforces
 * ({@code DefaultAclManager} builds it from the {@link Reading}, and nothing else sets a page's ACL).
 *
 * <p>Two readings used to feed enforcement and disagreed on several shapes: the text scan (one-line
 * <code>[{ALLOW perm …}]</code> anywhere outside Markdown code, frontmatter included) and the render path (any link
 * label starting <code>{ALLOW</code> or <code>{DENY</code>, body only, real Markdown structure). This reader sees
 * every shape either could take for a rule. Where the author's intent is plain it reads a rule — {@link Shape#CANONICAL},
 * which both old readings read alike, and {@link Shape#WRAPPED}, {@link Shape#CASE_VARIANT} and
 * {@link Shape#LEADING_SPACE}, which they did not — each as one canonical rule line ({@link #isRule}); an
 * {@link Shape#ESCAPED} rule is literal text. Every other directive-shaped span that either old reading took for a rule
 * is <em>disputed</em> and {@link Locked locks} the page (only administrators may then use it) rather than guess which
 * reading the author meant ({@link #disputed}).
 *
 * <p>How a span is found and classified:
 * <ul>
 *   <li>Candidates are every span either old reading takes for a rule, plus every <code>[{</code> whose first word
 *       starts {@code allow} or {@code deny} in any case, up to the first <code>}]</code> with no blank line between
 *       (in the frontmatter and in the body, each on its own).</li>
 *   <li>The body is parsed once, the way the renderer parses it ({@link RenderPathAccessRules#structure}, bounded:
 *       a parse that runs over its budget or throws makes the whole text {@link Shape#UNREADABLE}).</li>
 *   <li>A candidate's region decides first ({@link Shape#IN_FRONTMATTER}, code, {@link Shape#IN_MATH},
 *       {@link Shape#IN_HTML_BLOCK}, {@link Shape#LINK_WITH_TARGET}, {@link Shape#ESCAPED}), then its spelling
 *       ({@link Shape#DENY}, {@link Shape#ALLOW_PREFIXED_WORD}, {@link Shape#BAD_PERMISSION}, {@link Shape#WRAPPED},
 *       {@link Shape#LEADING_SPACE}, {@link Shape#CASE_VARIANT}). {@code DENY} in any case, after any whitespace, is
 *       {@link Shape#DENY} wherever it is outside code — frontmatter, HTML, math and links included: a rule spelled so is
 *       enforced for {@code ALLOW}, so the same spelling of {@code DENY} may not be ignored. Any other candidate in code,
 *       in a disputed region, or with a longer first word ({@code ALLOWED}, {@code Denylist}) is literal text — no span
 *       at all — when neither old reading took it for a rule ({@code [{Allowance}]}, a rule in a YAML code span): the
 *       page was open under both, and nothing tells it apart from prose.</li>
 *   <li>A rule shape is read from its label as the render parse sees it (a blockquote's continuation markers left
 *       out); a wrapped one whose line break falls inside a name, with no comma beside it, is {@link Shape#WRAPPED_NAME}. A clean spelling is {@link Shape#CANONICAL} only where both old readings take it for the same rule over
 *       the same span; any rule shape whose span the parse does not see as one label is {@link Shape#UNREADABLE}.</li>
 *   <li>A body that holds an Obsidian {@code %%comment%%} is parsed a second time without its comments, as the render
 *       parses it. A span inside a comment that would be a span without the comment markers is {@link Shape#IN_COMMENT}
 *       (unless escaped): "commented out" never silently grants. A span outside the comments that the removal moves
 *       into or out of code, where either reading takes it for a rule, is {@link Shape#UNREADABLE}: the render would show
 *       a rule the reader enforces, or read one it does not.</li>
 * </ul>
 * A text with no <code>{allow</code> or <code>{deny</code> (any case, any whitespace after the brace) is not parsed.
 */
public final class AccessRules {

    private static final Logger LOG = LogManager.getLogger( AccessRules.class );

    /** How a directive-shaped span is written, and so whether the two old readings agreed on it. */
    public enum Shape {
        /** One line, {@code ALLOW} and a lower-case permission, in body prose: both old readings read it alike. */
        CANONICAL,
        /** Runs over more than one line (no blank line): only the render path read it. */
        WRAPPED,
        /**
         * Wrapped, with a line break inside a name, no comma beside it ({@code [{ALLOW edit Jane\n Bob}]}): the lines
         * joined say "Jane Bob", one name, where the author may have meant two.
         */
        WRAPPED_NAME,
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
        /**
         * Inside an Obsidian {@code %%comment%%}. The reader reads the stored text, comments included, so the rule would
         * be enforced while it reads as "commented out" and every reader is blind to it: it locks the page instead.
         */
        IN_COMMENT,
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
     * Why a page is locked: one disputed span (or the text as a whole, for {@link Shape#UNREADABLE}). Where it is, is
     * said as the editor shows the page: the body apart from the frontmatter, which is edited as fields.
     *
     * @param shape what is wrong
     * @param line  the 1-based line of the body the span starts on (the first line after the frontmatter's closing
     *              {@code ---} is line 1); {@code null} for a span in the frontmatter
     * @param field the top-level frontmatter field the span is in; {@code null} for a span in the body, or in a
     *              frontmatter line no field owns
     * @param text  the span's text (cut short when long), or what could not be read
     */
    public record Reason( Shape shape, Integer line, String field, String text ) {
    }

    /** What {@link #read} makes of a text. */
    public sealed interface Reading permits Rules, Locked {
        /** @return every directive-shaped span of the text that is not literal code, in document order */
        List< Span > spans();
    }

    /**
     * No span is disputed: the page's rules are {@code ruleLines}.
     *
     * @param ruleLines the rule lines ({@code ALLOW view Admin}) to build the ACL from, in canonical form ({@code ALLOW},
     *                  a lower-case permission, a wrapped principal list joined with single spaces), in document order
     * @param spans     every directive-shaped span, in document order: one per rule line ({@link #ruleSpans}), and the
     *                  {@link Shape#ESCAPED} ones, which are literal text
     */
    public record Rules( List< String > ruleLines, List< Span > spans ) implements Reading {
        public Rules {
            ruleLines = List.copyOf( ruleLines );
            spans = List.copyOf( spans );
        }

        /** @return the spans that carry {@link #ruleLines}, one per line, in the same order */
        public List< Span > ruleSpans() {
            return spans.stream().filter( span -> isRule( span.shape() ) ).toList();
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

    /** Longest span text a {@link Reason} carries. */
    static final int MAX_REASON_TEXT = 200;

    /** Test seam: how many times {@link #read} has gone past its no-directive shortcut to parse a text. */
    static final AtomicLong PARSES = new AtomicLong();

    private static final Rules NONE = new Rules( List.of(), List.of() );

    private AccessRules() {
    }

    /**
     * Whether a span of this shape locks the page: every shape but the rule shapes ({@link #isRule}) and
     * {@link Shape#ESCAPED}, which is literal text.
     *
     * @param shape a span's shape
     * @return {@code true} when the span locks the page
     */
    static boolean disputed( final Shape shape ) {
        return !isRule( shape ) && shape != Shape.ESCAPED;
    }

    /**
     * Whether a span of this shape is read as a rule: {@link Shape#CANONICAL}, {@link Shape#WRAPPED},
     * {@link Shape#CASE_VARIANT} and {@link Shape#LEADING_SPACE}, the shapes whose intent is plain.
     *
     * @param shape a span's shape
     * @return {@code true} when the span carries a rule line
     */
    public static boolean isRule( final Shape shape ) {
        return switch ( shape ) {
            case CANONICAL, WRAPPED, CASE_VARIANT, LEADING_SPACE -> true;
            default -> false;
        };
    }

    /**
     * @param fullText raw page text, frontmatter included; may be {@code null}
     * @return the reading of its access rules; never {@code null}
     */
    public static Reading read( final String fullText ) {
        final Parsed parsed = parse( fullText );
        return parsed.reading();
    }

    /**
     * What one render-path parse of a text gives: the reading, and the render path's own rules, for
     * {@link AclDirectiveText}, which hides both and so parses each text once, not twice.
     *
     * @param reading     what {@link #read} returns
     * @param renderRules the rules the render path reads ({@link RenderPathAccessRules#find}); empty when the text is
     *                    unreadable
     * @param unreadable  why the text cannot be read the way the renderer reads it; {@code null} when it can
     * @param inMath      {@code {start, end}} of each directive-shaped candidate inside math as the render reads it: shown
     *                    to readers as math source, so hidden like a rule even where no reading takes it for one (#128: a
     *                    fence line inside the math must not make the text scan call it code)
     */
    record Parsed( Reading reading, List< RenderPathAccessRules.Rule > renderRules, RenderPathAccessRules.Unreadable unreadable,
                   List< int[] > inMath ) {
    }

    /** {@link #read}, without its warning, with the render path's rules from the same parse. */
    static Parsed parse( final String fullText ) {
        if ( fullText == null || !DirectiveCandidates.mayHoldDirective( fullText, 0, fullText.length() ) ) {
            return new Parsed( NONE, List.of(), null, List.of() );
        }
        final int bodyStart = FrontmatterParser.bodyOffset( fullText );
        final TreeMap< Integer, Integer > candidates = DirectiveCandidates.find( fullText, bodyStart );
        final Map< Integer, Integer > scanned = new HashMap<>();
        for ( final AclDirectiveScanner.Span span : EnforcedAclDirectives.spans( fullText ) ) {
            scanned.put( span.start(), AclDirectiveScanner.endWithEmptyParentheses( fullText, span ) );
        }
        if ( !DirectiveCandidates.mayHoldDirective( fullText, bodyStart, fullText.length() ) ) {
            // Only the frontmatter holds anything directive-shaped: no body to parse.
            return new Parsed( reading( fullText, unparsed( fullText, candidates, bodyStart, scanned ), start -> {
                throw new IllegalStateException( "an unparsed text holds no rule span" );
            } ), List.of(), null, List.of() );
        }
        PARSES.incrementAndGet();
        final RenderPathAccessRules.Structure structure;
        try {
            structure = RenderPathAccessRules.structure( fullText );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            // One warning per parse: the single place both read() and AclDirectiveText arrive, so neither double-logs.
            // The text becomes a lock (administrators only); DefaultAclManager also warns once per page version, by name.
            LOG.warn( "The access rules of a page text of {} characters cannot be read the way the renderer reads them; "
                    + "the page locks to administrators: {}", fullText.length(), e.getMessage() );
            return new Parsed( unreadable( fullText, bodyStart, candidates, scanned, e.getMessage() ), List.of(), e, List.of() );
        }
        for ( final RenderPathAccessRules.Rule rule : structure.rules() ) {
            candidates.putIfAbsent( rule.start(), rule.end() );
        }
        final SpanClassifier.Comments comments;
        try {
            comments = SpanClassifier.Comments.of( fullText, bodyStart, structure );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            LOG.warn( "The access rules of a page text of {} characters cannot be read the way the renderer reads them once "
                    + "its comments are removed; the page locks to administrators: {}", fullText.length(), e.getMessage() );
            return new Parsed( unreadable( fullText, bodyStart, candidates, scanned, e.getMessage() ), List.of(), e, List.of() );
        }
        final SpanClassifier classifier = new SpanClassifier( fullText, bodyStart, structure, comments );
        final List< Span > spans = new ArrayList<>( candidates.size() );
        for ( final Map.Entry< Integer, Integer > candidate : candidates.entrySet() ) {
            final Shape shape = classifier.classify( candidate.getKey(), candidate.getValue(), scanned.get( candidate.getKey() ) );
            if ( shape != null ) {
                spans.add( new Span( candidate.getKey(), candidate.getValue(), shape ) );
            }
        }
        return new Parsed( reading( fullText, spans, classifier::ruleLine ), structure.rules(), null, inMath( candidates, structure.math() ) );
    }

    /** The candidates that start inside one of {@code math} (ascending, not overlapping), each cut at its region's end. */
    private static List< int[] > inMath( final TreeMap< Integer, Integer > candidates, final List< int[] > math ) {
        final List< int[] > out = new ArrayList<>();
        for ( final int[] region : math ) {
            for ( final Map.Entry< Integer, Integer > candidate : candidates.subMap( region[ 0 ], region[ 1 ] ).entrySet() ) {
                out.add( new int[]{ candidate.getKey(), Math.min( candidate.getValue(), region[ 1 ] ) } );
            }
        }
        return out;
    }

    /** @param ruleLines the canonical rule line of a rule span, by its start */
    private static Reading reading( final String text, final List< Span > spans, final IntFunction< String > ruleLines ) {
        final List< Reason > reasons = new ArrayList<>();
        final List< String > lines = new ArrayList<>();
        final Places places = new Places( text );
        for ( final Span span : spans ) {
            if ( disputed( span.shape() ) ) {
                reasons.add( places.reason( span.shape(), span.start(), excerpt( text, span.start(), span.end() ) ) );
            } else if ( isRule( span.shape() ) ) {
                lines.add( ruleLines.apply( span.start() ) );
            }
        }
        return reasons.isEmpty() ? new Rules( lines, spans ) : new Locked( reasons, spans );
    }

    private static Locked unreadable( final String text, final int bodyStart, final TreeMap< Integer, Integer > candidates,
                                      final Map< Integer, Integer > scanned, final String why ) {
        final List< Span > spans = unparsed( text, candidates, bodyStart, scanned );
        final Integer firstInBody = candidates.ceilingKey( bodyStart );
        final String reason = "the page text cannot be read the way the renderer reads it: " + why;
        final List< Reason > reasons = new ArrayList<>();
        reasons.add( firstInBody == null ? new Reason( Shape.UNREADABLE, 1, null, reason )
                : new Places( text ).reason( Shape.UNREADABLE, firstInBody, reason ) );
        return new Locked( reasons, spans );
    }

    /**
     * The spans of a text whose body is not parsed: a frontmatter candidate the text scan read is
     * {@link Shape#IN_FRONTMATTER}, one spelled {@code DENY} outside the text scan's code is {@link Shape#DENY} (any other
     * is literal text, no span), a body candidate {@link Shape#UNREADABLE}.
     */
    private static List< Span > unparsed( final String text, final TreeMap< Integer, Integer > candidates, final int bodyStart,
                                          final Map< Integer, Integer > scanned ) {
        final List< Span > spans = new ArrayList<>( candidates.size() );
        final EnforcedAclDirectives.CodeRegions code = new EnforcedAclDirectives.CodeRegions( text );
        for ( final Map.Entry< Integer, Integer > candidate : candidates.entrySet() ) {
            final int start = candidate.getKey();
            if ( start >= bodyStart ) {
                spans.add( new Span( start, candidate.getValue(), Shape.UNREADABLE ) );
            } else if ( scanned.containsKey( start ) ) {
                spans.add( new Span( start, candidate.getValue(), Shape.IN_FRONTMATTER ) );
            } else if ( DirectiveSpelling.isDeny( text, start, candidate.getValue() ) && !code.contains( start ) ) {
                spans.add( new Span( start, candidate.getValue(), Shape.DENY ) );
            }
        }
        return spans;
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
     * Where the offsets of one text are, as the editor shows the page: the line of the body (the first line after the
     * frontmatter's closing {@code ---} is line 1) or the frontmatter field. Ask for body offsets in ascending order: the
     * lines are counted once across the text.
     */
    static final class Places {

        /** A top-level frontmatter key at the start of a line: plain, or quoted with either quote. */
        private static final Pattern FIELD = Pattern.compile(
                "(?:\"([^\"\\r\\n]+)\"|'([^'\\r\\n]+)'|([^\\s#'\"\\-\\[{][^:\\r\\n]*?))[ \\t]*:(?:\\s|$)" );

        private final String text;
        private final int bodyStart;
        private final LineCounter lines;
        private final int bodyFirstLine;
        /** Start of the next frontmatter line {@link #fieldAt} has not looked at yet. */
        private int fieldLineStart;
        /** Start of the last line it looked at. */
        private int lastFieldLine = -1;
        /** The key of the nearest line before {@link #fieldLineStart} that has one; {@code null} when none has. */
        private String field;

        /** @param text a page text, frontmatter included */
        Places( final String text ) {
            this.text = text;
            this.bodyStart = FrontmatterParser.bodyOffset( text );
            this.lines = new LineCounter( text );
            this.bodyFirstLine = lines.lineOf( bodyStart );
        }

        /**
         * @param shape  what is wrong
         * @param offset where it starts in the text
         * @param what   the span's text, or what could not be read
         * @return the reason, placed in the body or the frontmatter
         */
        Reason reason( final Shape shape, final int offset, final String what ) {
            if ( offset < bodyStart ) {
                return new Reason( shape, null, fieldAt( offset ), what );
            }
            return new Reason( shape, lines.lineOf( Math.min( offset, text.length() ) ) - bodyFirstLine + 1, null, what );
        }

        /**
         * The top-level key of the frontmatter line holding {@code offset}, or of the nearest line above it that has one.
         * The lines are read once, top down, across ascending offsets: a field that holds many spans is not re-read for
         * each of them (that would cost the square of the frontmatter).
         */
        private String fieldAt( final int offset ) {
            if ( offset < fieldLineStart ) {
                if ( offset >= lastFieldLine ) {
                    return field;           // on the line read last
                }
                fieldLineStart = 0;
                field = null;
            }
            final Matcher key = FIELD.matcher( text );
            while ( fieldLineStart <= offset && fieldLineStart < text.length() ) {
                final int newline = text.indexOf( '\n', fieldLineStart );
                final int lineEnd = newline < 0 ? text.length() : newline;
                key.region( fieldLineStart, lineEnd );
                if ( key.lookingAt() ) {
                    field = key.group( 1 ) != null ? key.group( 1 ) : key.group( 2 ) != null ? key.group( 2 ) : key.group( 3 );
                }
                lastFieldLine = fieldLineStart;
                fieldLineStart = lineEnd + 1;
            }
            return field;
        }
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

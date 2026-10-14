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

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.markdown.extensions.math.InlineMathParser;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.AccessRuleLinkNodePostProcessorState;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.LinkParsingOperations;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The access rules the wiki's <em>render path</em> reads in a page text — what {@link MarkdownParser} and its
 * access-rule post-processor would hand {@code AclManager.parseAcl} — found without a wiki context, with each rule's
 * span in the text. The ACL manager enforces these in addition to its own text scan, so that rendering can never
 * enforce something the text scan does not.
 *
 * <p>Exactly the render path's shapes, because it runs the render path's own steps: the body only (the frontmatter is
 * never rendered); the same preparation ({@link MarkdownBodySource}: plugin syntax <code>[{…}]</code> becomes the
 * inline link <code>[{…}]()</code>, display math becomes a fence); the same flexmark block and inline structure
 * ({@link MarkdownDocument#structuralOptions()} plus the inline-math parser, as the rendered page has); and the same
 * classification ({@link LinkParsingOperations#isAccessRuleLink}: a link whose target starts {@code {ALLOW} or
 * {@code {DENY}) and rule line ({@link AccessRuleLinkNodePostProcessorState#ruleLineOf}). In particular:
 * <ul>
 *   <li>a rule may be wrapped over several lines (a link label may), but not across a blank line, and inside a
 *       blockquote or list the continuation lines' markers are not part of it;</li>
 *   <li>a link label has no length limit (the 999-character limit applies to link <em>reference</em> labels only);</li>
 *   <li>{@code DENY} is read as a grant and {@code {ALLOWED …}} as a rule: the ACL parser skips the first word;</li>
 *   <li>the permission is case-insensitive;</li>
 *   <li>nothing in code, an HTML block or math, and no link reference, is a rule.</li>
 * </ul>
 * Parsing is skipped outright for a text with no {@code {ALLOW} and no {@code {DENY}.
 *
 * <p><b>Bounded cost.</b> This runs on every read of a page that holds a rule, so it may not cost what a render can: the
 * renderer's attributes extension matches <code>{…}</code> with a backtracking expression that is quadratic on, for
 * example, <code>[{ALLOW edit</code> followed by a long run of spaces, and overflows the stack on a long run of
 * attribute-like words. The parse therefore reads its input through a counter and is cut off at
 * {@value #BUDGET_PER_CHAR} character reads per character (the whole page corpus needs at most about 7); a cut-off
 * parse, or a stack overflow, fails <em>closed</em>: the body is then read by bracket pairing alone
 * ({@link #ruleShapedSpans}), code or not — at least the rules the renderer reads, so enforcement and hiding only
 * tighten on such a page.
 */
public final class RenderPathAccessRules {

    private static final Logger LOG = LogManager.getLogger( RenderPathAccessRules.class );

    /** Character reads allowed per character of the prepared body, plus {@link #BUDGET_BASE}. */
    static final int BUDGET_PER_CHAR = 128;

    /** Character reads every parse may make whatever the length. */
    static final int BUDGET_BASE = 65_536;

    private static final String CLOSER = "}]";

    /**
     * One rule: {@code ruleLine} is what the render path hands the ACL parser; {@code text.substring( start, end )} is
     * the link that carries it (with its {@code ()} when the author wrote one).
     *
     * @param start    offset of the link's {@code [} in the page text
     * @param end      offset just past the link in the page text
     * @param ruleLine the rule line, e.g. {@code ALLOW view Admin}
     */
    public record Rule( int start, int end, String ruleLine ) {
    }

    /** Immutable and thread-safe: the rendered page's block and inline structure, without its context-bound extension. */
    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();

    private RenderPathAccessRules() {
    }

    /**
     * @param fullText raw page text, frontmatter included; may be {@code null}
     * @return the render path's access rules, in document order; never {@code null}
     */
    public static List< Rule > find( final String fullText ) {
        if ( !mayHoldRule( fullText ) ) {
            return List.of();
        }
        final int bodyStart = FrontmatterParser.bodyOffset( fullText );
        final String body = fullText.substring( bodyStart );
        if ( !mayHoldRule( body ) ) {
            return List.of();
        }
        final MarkdownBodySource.Prepared prepared = MarkdownBodySource.prepare( body );
        try {
            return parsedRules( prepared, bodyStart );
        } catch ( final BudgetExceeded | StackOverflowError e ) {
            LOG.warn( "Reading the access rules of a text of {} characters as the renderer would was cut off ({}); every "
                    + "[{ALLOW / [{DENY span in its body, code or not, counts as a rule instead", fullText.length(),
                    e.getClass().getSimpleName() );
            return ruleShapedSpans( body, bodyStart );
        }
    }

    private static List< Rule > parsedRules( final MarkdownBodySource.Prepared prepared, final int bodyStart ) {
        final String text = prepared.text();
        final long budget = BUDGET_BASE + ( long ) BUDGET_PER_CHAR * text.length();
        final List< Rule > rules = new ArrayList<>();
        for ( final Node node : PARSER.parse( BasedSequence.of( new CountedText( text, new long[ 1 ], budget, 0, text.length() ) ) )
                .getDescendants() ) {
            if ( node instanceof Link link ) {
                final String target = WikantikLink.wikiLinkOf( link );
                if ( LinkParsingOperations.isAccessRuleLink( target ) ) {
                    rules.add( new Rule( bodyStart + prepared.toBody( link.getStartOffset() ),
                            bodyStart + prepared.toBody( link.getEndOffset() ),
                            AccessRuleLinkNodePostProcessorState.ruleLineOf( target ) ) );
                }
            }
        }
        return rules;
    }

    /**
     * The fail-closed reading: for each <code>}]</code>, the nearest <code>[{ALLOW</code> or <code>[{DENY</code> before it
     * (after the previous rule, with no blank line between) opens a rule — the renderer's own pairing (a closing
     * {@code ]} takes the nearest open {@code [}), less its knowledge of code, HTML blocks and math, so it reads at least
     * the rules the renderer reads. The rule line is the text between <code>[{</code> and <code>}]</code>; an empty
     * {@code ()} after the closer goes with the rule. One linear pass.
     */
    static List< Rule > ruleShapedSpans( final String body, final int bodyStart ) {
        final List< Rule > rules = new ArrayList<>();
        int opener = -1;
        int i = 0;
        while ( i < body.length() ) {
            if ( body.startsWith( "[{ALLOW", i ) || body.startsWith( "[{DENY", i ) ) {
                opener = i;
                i += 2;
            } else if ( body.startsWith( CLOSER, i ) && opener >= 0 ) {
                final int end = body.startsWith( "()", i + 2 ) ? i + 4 : i + 2;
                rules.add( new Rule( bodyStart + opener, bodyStart + end, body.substring( opener + 2, i ) ) );
                opener = -1;
                i = end;
            } else {
                if ( blankLineStartsAt( body, i ) ) {
                    opener = -1;
                }
                i++;
            }
        }
        return rules;
    }

    /** Whether a line ending at {@code i} begins a blank line (only spaces and tabs before the next line ending). */
    private static boolean blankLineStartsAt( final String text, final int i ) {
        final char c = text.charAt( i );
        if ( c != '\n' && c != '\r' ) {
            return false;
        }
        int j = c == '\r' && i + 1 < text.length() && text.charAt( i + 1 ) == '\n' ? i + 2 : i + 1;
        while ( j < text.length() && ( text.charAt( j ) == ' ' || text.charAt( j ) == '\t' ) ) {
            j++;
        }
        return j < text.length() && ( text.charAt( j ) == '\n' || text.charAt( j ) == '\r' );
    }

    /** Thrown when a parse has read its budget of characters. */
    static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded( final long budget ) {
            super( "parse read more than " + budget + " characters", null, false, false );
        }
    }

    /** A text that counts every character read, through every subsequence, and stops the reader at the budget. */
    private static final class CountedText implements CharSequence {
        private final String text;
        private final long[] reads;
        private final long budget;
        private final int from;
        private final int to;

        CountedText( final String text, final long[] reads, final long budget, final int from, final int to ) {
            this.text = text;
            this.reads = reads;
            this.budget = budget;
            this.from = from;
            this.to = to;
        }

        private void count( final long n ) {
            reads[ 0 ] += n;
            if ( reads[ 0 ] > budget ) {
                throw new BudgetExceeded( budget );
            }
        }

        @Override
        public int length() {
            return to - from;
        }

        @Override
        public char charAt( final int index ) {
            count( 1 );
            return text.charAt( from + index );
        }

        @Override
        public CharSequence subSequence( final int start, final int end ) {
            return new CountedText( text, reads, budget, from + start, from + end );
        }

        @Override
        public String toString() {
            count( to - from );
            return text.substring( from, to );
        }
    }

    /**
     * Whether {@code text} could hold an access rule at all: every rule's link target starts {@code {ALLOW} or
     * {@code {DENY}, so a text with neither holds none.
     *
     * @param text any text, may be {@code null}
     * @return {@code false} when {@link #find} is certain to return nothing
     */
    public static boolean mayHoldRule( final String text ) {
        return text != null && ( text.contains( "{ALLOW" ) || text.contains( "{DENY" ) );
    }
}

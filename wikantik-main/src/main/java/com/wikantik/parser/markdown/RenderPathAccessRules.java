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

import java.util.ArrayList;
import java.util.List;

/**
 * The access rules the wiki's <em>render path</em> reads in a page text — what {@link MarkdownParser} and its
 * access-rule post-processor would hand {@code AclManager.parseAcl} — found without a wiki context, with each rule's
 * span in the text. Used for <b>hiding only</b>: {@code AclDirectiveText} removes these spans as well as the ones the
 * ACL manager's text scan enforces, so that nothing either reading turns into a rule reaches a reader who may not edit.
 * Enforcement does not use this class (the ACL manager's text scan and the render path disagree on several shapes; that
 * is pre-existing and has its own issue).
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
 * <p><b>Bounded cost, and no guessing.</b> This runs on every read of a page that holds a rule, so it may not cost what
 * a render can: the renderer's attributes extension matches <code>{…}</code> with a backtracking expression that is
 * quadratic on, for example, <code>{edit</code> followed by a long run of spaces, and overflows the stack on a long run
 * of attribute-like words. The parse therefore reads its input through a counter and is cut off at
 * {@value BudgetedText#BUDGET_PER_CHAR} character reads per character plus {@value BudgetedText#BUDGET_BASE} (see
 * {@link BudgetedText}; the page corpus needs at most about 7 per character). A cut-off parse, a
 * stack overflow, any other exception from the parse, and a rule that maps to no source text all end in
 * {@link Unreadable}: the caller then fails closed; it never gets a partial answer.
 */
public final class RenderPathAccessRules {

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
     * @throws Unreadable when the text cannot be read the way the renderer reads it (see the class comment)
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
        try {
            return parsedRules( MarkdownBodySource.prepare( body ), bodyStart, body.length() );
        } catch ( final Unreadable e ) {
            throw e;
        } catch ( final RuntimeException | StackOverflowError e ) {
            throw new Unreadable( "the parse of a body of " + body.length() + " characters failed: " + e, e );
        }
    }

    private static List< Rule > parsedRules( final MarkdownBodySource.Prepared prepared, final int bodyStart, final int bodyLength ) {
        final String text = prepared.text();
        final List< Rule > rules = new ArrayList<>();
        for ( final Node node : PARSER.parse( BasedSequence.of( BudgetedText.of( text, BudgetedText.budgetFor( text.length() ) ) ) )
                .getDescendants() ) {
            if ( node instanceof Link link ) {
                final String target = WikantikLink.wikiLinkOf( link );
                if ( LinkParsingOperations.isAccessRuleLink( target ) ) {
                    final int start = prepared.toBody( link.getStartOffset() );
                    final int end = prepared.toBody( link.getEndOffset() );
                    if ( start < 0 || end <= start || end > bodyLength ) {
                        throw new Unreadable( "a rule the renderer reads maps to no source text (" + start + ", " + end + ")", null );
                    }
                    rules.add( new Rule( bodyStart + start, bodyStart + end, AccessRuleLinkNodePostProcessorState.ruleLineOf( target ) ) );
                }
            }
        }
        return rules;
    }

    /** The text could not be read the way the renderer reads it; the caller must fail closed. */
    public static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable( final String message, final Throwable cause ) {
            super( message, cause );
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

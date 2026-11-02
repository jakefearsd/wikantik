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

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HtmlBlockBase;
import com.vladsch.flexmark.ast.HtmlInlineBase;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ext.gitlab.GitLabInlineMath;
import com.vladsch.flexmark.ext.wikilink.WikiNode;
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
 * access-rule post-processor reads as rules — found without a wiki context, with each rule's span in the text, and
 * (through {@link #structure}) the regions of the text that parse classifies as code, HTML, math or a link with a
 * target. {@code AclDirectiveText} removes these rules' spans from text shown to readers who may not edit; the
 * access-rule reader {@code AccessRules} — the only source of an enforced ACL — is built on {@link #structure}.
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
 * {@link BudgetedText}; the page corpus needs at most about 11 per character). A cut-off parse, a
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

    /**
     * What the render path's parse makes of a page text: its access rules, and where it sees code, HTML, math and links
     * that carry a target. Every range is {@code {start, end}} in the page text (frontmatter included), half open, in
     * document order per kind; nothing here is ever in the frontmatter, which the renderer never reads.
     *
     * @param rules the access rules the render path reads (see {@link #find})
     * @param code  inline code spans and code blocks the author wrote (a {@code $$} block's generated fence is math)
     * @param html  HTML blocks, HTML comments and inline HTML tags
     * @param math  inline math and {@code $$} display-math blocks
     * @param links links that are not access rules but have a target ({@code [text](url)}), images, reference links,
     *              link reference definitions and {@code [[…]]} wiki links
     * @param labels links with no target that the render path does <em>not</em> read as rules but whose text starts,
     *              after its brace and any whitespace, with {@code allow} in any case (<code>[{allow view Bob}]</code>,
     *              <code>[{ ALLOW view Bob}]</code>): each with its label as the parse reads it ({@link Rule#ruleLine}),
     *              so a blockquote's or list's continuation markers are not part of it. A label that maps to no source
     *              text is left out (it is no rule the render path reads, so it is no reason to call the text
     *              unreadable)
     */
    public record Structure( List< Rule > rules, List< int[] > code, List< int[] > html, List< int[] > math,
                             List< int[] > links, List< Rule > labels ) {
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
        return parsed( body, bodyStart ).rules();
    }

    /**
     * The render path's parse of {@code fullText}'s body — the same parse {@link #find} runs, always run (no shortcut
     * for a text without a rule), with the regions it classifies.
     *
     * @param fullText raw page text, frontmatter included; may be {@code null} (read as empty)
     * @return what the parse found; never {@code null}
     * @throws Unreadable when the text cannot be read the way the renderer reads it (see the class comment)
     */
    public static Structure structure( final String fullText ) {
        final String text = fullText == null ? "" : fullText;
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        return parsed( text.substring( bodyStart ), bodyStart );
    }

    private static Structure parsed( final String body, final int bodyStart ) {
        try {
            return parsedStructure( MarkdownBodySource.prepare( body ), bodyStart, body.length() );
        } catch ( final Unreadable e ) {
            throw e;
        } catch ( final RuntimeException | StackOverflowError e ) {
            throw new Unreadable( "the parse of a body of " + body.length() + " characters failed: " + e, e );
        }
    }

    private static Structure parsedStructure( final MarkdownBodySource.Prepared prepared, final int bodyStart, final int bodyLength ) {
        final String text = prepared.text();
        final Structure out = new Structure( new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>() );
        for ( final Node node : PARSER.parse( BasedSequence.of( BudgetedText.of( text, BudgetedText.budgetFor( text.length() ) ) ) )
                .getDescendants() ) {
            if ( node instanceof Link link && LinkParsingOperations.isAccessRuleLink( WikantikLink.wikiLinkOf( link ) ) ) {
                out.rules().add( rule( prepared, link, bodyStart, bodyLength ) );
            } else if ( !RuleLabels.collect( out.labels(), prepared, node, bodyStart, bodyLength ) ) {
                final List< int[] > kind = regionKind( out, node );
                if ( kind != null ) {
                    region( kind, prepared, node, bodyStart );
                }
            }
        }
        for ( final int[] block : prepared.displayMathInBody() ) {
            out.math().add( new int[]{ bodyStart + block[ 0 ], bodyStart + block[ 1 ] } );
        }
        out.math().sort( java.util.Comparator.comparingInt( r -> r[ 0 ] ) );
        return out;
    }

    private static Rule rule( final MarkdownBodySource.Prepared prepared, final Link link, final int bodyStart, final int bodyLength ) {
        final int start = prepared.toBody( link.getStartOffset() );
        final int end = prepared.toBody( link.getEndOffset() );
        if ( start < 0 || end <= start || end > bodyLength ) {
            throw new Unreadable( "a rule the renderer reads maps to no source text (" + start + ", " + end + ")", null );
        }
        return new Rule( bodyStart + start, bodyStart + end, AccessRuleLinkNodePostProcessorState.ruleLineOf( WikantikLink.wikiLinkOf( link ) ) );
    }

    /**
     * Which of {@code out}'s region lists {@code node} belongs to, or {@code null}. A {@code $$} block's generated fence
     * starts in generated markup (no body offset), so {@link #region} drops it as code: it is math, added from the
     * rewrite's own record.
     */
    private static List< int[] > regionKind( final Structure out, final Node node ) {
        if ( node instanceof Link link ) {
            return link.getUrl().isEmpty() ? null : out.links();
        }
        if ( node instanceof Image || node instanceof ImageRef || node instanceof LinkRef || node instanceof Reference
                || node instanceof WikiNode ) {
            return out.links();
        }
        if ( node instanceof Code || node instanceof IndentedCodeBlock || node instanceof FencedCodeBlock ) {
            return out.code();
        }
        if ( node instanceof HtmlBlockBase || node instanceof HtmlInlineBase ) {
            return out.html();
        }
        return node instanceof GitLabInlineMath ? out.math() : null;
    }

    /** Adds {@code node}'s span in the page text to {@code into}, unless it starts or ends in generated markup. */
    private static void region( final List< int[] > into, final MarkdownBodySource.Prepared prepared, final Node node, final int bodyStart ) {
        final int start = prepared.toBody( node.getStartOffset() );
        final int end = prepared.toBodyEnd( node.getEndOffset() );
        if ( start >= 0 && end > start ) {
            into.add( new int[]{ bodyStart + start, bodyStart + end } );
        }
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

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
import com.wikantik.markdown.extensions.math.InlineMathParser;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.AccessRuleLinkNodePostProcessorState;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.LinkParsingOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The reader's parse of a raw page body, and its classification: the same preparation, flexmark structure and
 * inline-math parser the render runs, under the {@link BudgetedText} parse budget, with every code, HTML, math, link
 * and access-rule region mapped back to offsets in the page text. {@link RenderPathAccessRules} reads access rules from
 * it; a cut-off or failed parse, or a rule that maps to no source text, is {@link RenderPathAccessRules.Unreadable}.
 */
final class ReaderStructure {

    /** Marks "size the budget from the prepared text", as the render path always has. */
    private static final long AUTO_BUDGET = -1L;

    /** Immutable and thread-safe: the rendered page's block and inline structure, without its context-bound extension. */
    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();

    /** How many bodies have been parsed: a test seam, so a caller's tests can count the parses it pays for. */
    private static final AtomicLong PARSES = new AtomicLong();

    private ReaderStructure() {
    }

    /** @return how many times a body has been parsed since the class loaded (a test seam) */
    static long parses() {
        return PARSES.get();
    }

    /**
     * @param body      the raw page body (frontmatter excluded)
     * @param bodyStart the body's offset in the page text; every returned range is in page-text coordinates
     * @return the body's structure
     * @throws RenderPathAccessRules.Unreadable when the body cannot be read the way the renderer reads it
     */
    static RenderPathAccessRules.Structure of( final String body, final int bodyStart ) {
        return parsed( body, bodyStart, AUTO_BUDGET );
    }

    /** As {@link #of(String, int)} with an explicit parse budget (a test seam). */
    static RenderPathAccessRules.Structure of( final String body, final int bodyStart, final long budget ) {
        return parsed( body, bodyStart, budget );
    }

    private static RenderPathAccessRules.Structure parsed( final String body, final int bodyStart, final long budget ) {
        PARSES.incrementAndGet();
        try {
            return parsedStructure( MarkdownBodySource.prepare( body ), bodyStart, body.length(), budget );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            throw e;
        } catch ( final RuntimeException | StackOverflowError e ) {
            throw new RenderPathAccessRules.Unreadable( "the parse of a body of " + body.length() + " characters failed: " + e, e );
        }
    }

    private static RenderPathAccessRules.Structure parsedStructure( final MarkdownBodySource.Prepared prepared, final int bodyStart,
                                                                     final int bodyLength, final long budget ) {
        final String text = prepared.text();
        final long parseBudget = budget == AUTO_BUDGET ? BudgetedText.budgetFor( text.length() ) : budget;
        final RenderPathAccessRules.Structure out = new RenderPathAccessRules.Structure( new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>() );
        for ( final Node node : PARSER.parse( BasedSequence.of( BudgetedText.of( text, parseBudget ) ) ).getDescendants() ) {
            if ( node instanceof Link link && LinkParsingOperations.isAccessRuleLink( WikantikLink.wikiLinkOf( link ) ) ) {
                out.rules().add( rule( prepared, link, bodyStart, bodyLength ) );
            } else if ( node instanceof FencedCodeBlock && prepared.toBody( node.getStartOffset() ) < 0 ) {
                displayMath( out.renderedMath(), prepared, node, bodyStart );
            } else if ( !RuleLabels.collect( out.labels(), prepared, node, bodyStart, bodyLength ) ) {
                final List< int[] > kind = regionKind( out, node );
                if ( kind != null ) {
                    region( kind, prepared, node, bodyStart );
                    if ( kind == out.math() ) {
                        region( out.renderedMath(), prepared, node, bodyStart );
                    }
                }
            }
        }
        for ( final int[] block : prepared.displayMathInBody() ) {
            out.math().add( new int[]{ bodyStart + block[ 0 ], bodyStart + block[ 1 ] } );
        }
        out.math().sort( java.util.Comparator.comparingInt( r -> r[ 0 ] ) );
        out.renderedMath().sort( java.util.Comparator.comparingInt( r -> r[ 0 ] ) );
        return out;
    }

    private static RenderPathAccessRules.Rule rule( final MarkdownBodySource.Prepared prepared, final Link link, final int bodyStart,
                                                    final int bodyLength ) {
        final int start = prepared.toBody( link.getStartOffset() );
        final int end = prepared.toBody( link.getEndOffset() );
        if ( start < 0 || end <= start || end > bodyLength ) {
            throw new RenderPathAccessRules.Unreadable( "a rule the renderer reads maps to no source text (" + start + ", " + end + ")", null );
        }
        return new RenderPathAccessRules.Rule( bodyStart + start, bodyStart + end,
                AccessRuleLinkNodePostProcessorState.ruleLineOf( WikantikLink.wikiLinkOf( link ) ) );
    }

    /**
     * Which of {@code out}'s region lists {@code node} belongs to, or {@code null}. A {@code $$} block's generated fence
     * starts in generated markup (no body offset), so {@link #region} drops it as code: it is math, whose extent
     * {@link #displayMath} takes from the parsed fence.
     */
    private static List< int[] > regionKind( final RenderPathAccessRules.Structure out, final Node node ) {
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

    /** Adds the span of a generated {@code math} fence, as the parse ended it, to {@code into}. */
    private static void displayMath( final List< int[] > into, final MarkdownBodySource.Prepared prepared, final Node node, final int bodyStart ) {
        final int[] span = prepared.displayMathNodeInBody( node.getStartOffset(), node.getEndOffset() );
        if ( span != null ) {
            into.add( new int[]{ bodyStart + span[ 0 ], bodyStart + span[ 1 ] } );
        }
    }

    /** Adds {@code node}'s span in the page text to {@code into}, unless it starts or ends in generated markup. */
    private static void region( final List< int[] > into, final MarkdownBodySource.Prepared prepared, final Node node, final int bodyStart ) {
        final int start = prepared.toBody( node.getStartOffset() );
        final int end = prepared.toBodyEnd( node.getEndOffset() );
        if ( start >= 0 && end > start ) {
            into.add( new int[]{ bodyStart + start, bodyStart + end } );
        }
    }
}

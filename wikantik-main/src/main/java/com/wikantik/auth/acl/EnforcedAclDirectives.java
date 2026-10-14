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

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.AclDirectiveScanner;
import com.wikantik.util.OffsetRanges;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which access rules of a page text the wiki <em>enforces</em>, and where they are. Two sources, and the rules of both
 * apply (a union, so the scan only ever tightened when the second was added):
 * <ol>
 *   <li><b>The text scan</b> — every {@code [{ALLOW …}]} {@link AclDirectiveScanner} finds (linear time, match for match
 *       what {@link DefaultAclManager#ACL_PATTERN} finds; one line, apart from whitespace runs; no length bound) that
 *       does not start inside a Markdown code span or code block, code being located by a real CommonMark parse of
 *       the full text. The identical syntax shown inside backticks or a fenced/indented block is documentation (a page
 *       that explains how ACLs work) and is skipped, so such a page does not accidentally restrict itself.</li>
 *   <li><b>The render path's rules</b> — what {@link RenderPathAccessRules} reads, i.e. what rendering the page would
 *       hand the ACL parser: a rule wrapped over several lines, {@code DENY} (read as a grant), an upper-case
 *       permission, and so on. Rendering used to set these on whichever Page instance it rendered, so the ACL depended
 *       on render order; display rendering now parses text without them, and this is where they are enforced.</li>
 * </ol>
 *
 * <p>The scan fails closed toward enforcement: a directive in ordinary page text is always returned, so a genuine
 * restriction is never silently dropped. {@link AclDirectiveText} removes exactly these spans (and then more) from text
 * shown to readers who may not edit, so what is hidden always includes what is enforced.
 */
final class EnforcedAclDirectives {

    /**
     * Vanilla CommonMark parser used solely to locate Markdown code spans and
     * code blocks when scanning for ACL directives. A bare parse (no rendering,
     * no custom extensions) is far cheaper than a full page render and is all we
     * need to tell a real {@code [{ALLOW …}]} rule apart from one shown as an
     * example inside backticks or a fenced block. Flexmark {@link Parser}
     * instances are immutable and safe to reuse across threads.
     */
    private static final Parser CODE_REGION_PARSER = Parser.builder().build();

    /**
     * One enforced rule: {@code text.substring( start, end )} is its source, {@code ruleLine} what the ACL parser reads.
     *
     * @param start    where the rule starts in the page text
     * @param end      where it ends (for a text-scan rule, before any {@code ()} after it)
     * @param ruleLine the brace-free rule line, e.g. {@code ALLOW view Admin}
     */
    record Directive( int start, int end, String ruleLine ) {
    }

    private EnforcedAclDirectives() {
    }

    /**
     * The enforced rules' source strings in {@code pageText}, in document order.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the enforced rules as written, never {@code null}
     */
    static List< String > directives( final String pageText ) {
        final List< String > directives = new ArrayList<>();
        for ( final Directive d : enforced( pageText ) ) {
            directives.add( pageText.substring( d.start(), d.end() ) );
        }
        return directives;
    }

    /**
     * The rule lines the ACL is built from, in document order.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the brace-free rule lines, never {@code null}
     */
    static List< String > ruleLines( final String pageText ) {
        final List< String > lines = new ArrayList<>();
        for ( final Directive d : enforced( pageText ) ) {
            lines.add( d.ruleLine() );
        }
        return lines;
    }

    /**
     * Every enforced rule: the text scan's, then each render-path rule that does not start where a text-scan rule does
     * (those are the same rule), merged in document order.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the enforced rules, never {@code null}
     */
    static List< Directive > enforced( final String pageText ) {
        final List< Directive > scanned = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : spans( pageText ) ) {
            scanned.add( new Directive( span.start(), span.end(),
                    DefaultAclManager.ruleLineOf( pageText.substring( span.start(), span.end() ) ) ) );
        }
        final List< RenderPathAccessRules.Rule > rendered = RenderPathAccessRules.find( pageText );
        if ( rendered.isEmpty() ) {
            return scanned;
        }
        final Set< Integer > scannedStarts = new HashSet<>();
        scanned.forEach( d -> scannedStarts.add( d.start() ) );
        final List< Directive > all = new ArrayList<>( scanned );
        for ( final RenderPathAccessRules.Rule rule : rendered ) {
            if ( !scannedStarts.contains( rule.start() ) ) {
                all.add( new Directive( rule.start(), rule.end(), rule.ruleLine() ) );
            }
        }
        all.sort( Comparator.comparingInt( Directive::start ) );
        return all;
    }

    /**
     * The {@code [start, end)} ranges a reader's copy must lose so that nothing enforced is left: each text-scan rule
     * with an empty {@code ()} right after it, each render-path rule's link; sorted, overlaps merged.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return disjoint ranges in document order, never {@code null}
     */
    static List< int[] > removalRanges( final String pageText ) {
        final List< int[] > ranges = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : spans( pageText ) ) {
            ranges.add( new int[]{ span.start(), AclDirectiveScanner.endWithEmptyParentheses( pageText, span ) } );
        }
        for ( final RenderPathAccessRules.Rule rule : RenderPathAccessRules.find( pageText ) ) {
            ranges.add( new int[]{ rule.start(), rule.end() } );
        }
        return merged( ranges );
    }

    /** {@code ranges} sorted by start, overlapping ones joined; adjacent ones stay apart. */
    static List< int[] > merged( final List< int[] > ranges ) {
        if ( ranges.size() < 2 ) {
            return ranges;
        }
        ranges.sort( Comparator.comparingInt( r -> r[ 0 ] ) );
        final List< int[] > out = new ArrayList<>( ranges.size() );
        int[] current = ranges.get( 0 ).clone();
        for ( int i = 1; i < ranges.size(); i++ ) {
            final int[] next = ranges.get( i );
            if ( next[ 0 ] < current[ 1 ] ) {
                current[ 1 ] = Math.max( current[ 1 ], next[ 1 ] );
            } else {
                out.add( current );
                current = next.clone();
            }
        }
        out.add( current );
        return out;
    }

    /**
     * The text scan's enforced directives with their offsets. The parse that locates code is only paid for when the scan
     * finds a candidate at all, so text with no directive-shaped span is never parsed as Markdown.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the enforced spans in document order, never {@code null}
     */
    static List< AclDirectiveScanner.Span > spans( final String pageText ) {
        if ( pageText == null || pageText.isEmpty() ) {
            return List.of();
        }
        final List< AclDirectiveScanner.Span > candidates = AclDirectiveScanner.findAll( pageText, false );
        if ( candidates.isEmpty() ) {
            return List.of();
        }
        final OffsetRanges code = OffsetRanges.of( codeRegions( pageText ) );
        final List< AclDirectiveScanner.Span > enforced = new ArrayList<>( candidates.size() );
        for ( final AclDirectiveScanner.Span candidate : candidates ) {
            if ( !code.contains( candidate.start() ) ) {
                enforced.add( candidate );
            }
        }
        return enforced;
    }

    /**
     * {@code pageText} less every {@code [{ALLOW …}]} span, code or not — what
     * {@code ACL_PATTERN.matcher( pageText ).replaceAll( "" )} gave, without its running time — and less every
     * render-path rule ({@link RenderPathAccessRules}), which is enforced too: {@code setPermissions} writes the new ACL
     * in their place, so none of the old rules may survive in the text.
     */
    static String withoutAnyDirective( final String pageText ) {
        final List< int[] > ranges = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( pageText, false ) ) {
            ranges.add( new int[]{ span.start(), span.end() } );
        }
        for ( final RenderPathAccessRules.Rule rule : RenderPathAccessRules.find( pageText ) ) {
            ranges.add( new int[]{ rule.start(), rule.end() } );
        }
        if ( ranges.isEmpty() ) {
            return pageText;
        }
        final StringBuilder out = new StringBuilder( pageText.length() );
        int copiedTo = 0;
        for ( final int[] range : merged( ranges ) ) {
            out.append( pageText, copiedTo, range[ 0 ] );
            copiedTo = range[ 1 ];
        }
        return out.append( pageText, copiedTo, pageText.length() ).toString();
    }

    /**
     * Computes the source-offset ranges {@code [start, end)} of every inline
     * code span and code block in {@code text}, by parsing it as CommonMark and
     * collecting {@link Code}, {@link FencedCodeBlock} and
     * {@link IndentedCodeBlock} node spans. Unhandled node types are descended
     * into automatically by {@link NodeVisitor}, so code nested anywhere in the
     * document is captured.
     */
    private static List< int[] > codeRegions( final String text ) {
        final List< int[] > ranges = new ArrayList<>();
        final Document doc = CODE_REGION_PARSER.parse( text );
        final NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>( Code.class,              n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( FencedCodeBlock.class,   n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( IndentedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) )
        );
        visitor.visit( doc );
        return ranges;
    }
}

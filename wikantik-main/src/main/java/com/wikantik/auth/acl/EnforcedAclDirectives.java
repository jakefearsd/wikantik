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
import com.wikantik.util.AclDirectiveScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Which {@code [{ALLOW …}]} spans of a page text the wiki <em>enforces</em>: the ones
 * {@link AclDirectiveScanner} finds (linear time, match for match what {@link DefaultAclManager#ACL_PATTERN} finds) that
 * do not start inside a Markdown code span or code block, code being located by a real CommonMark parse. The identical
 * syntax shown inside backticks or a fenced/indented block is documentation (a page that explains how ACLs work) and
 * is skipped, so such a page does not accidentally restrict itself.
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

    private EnforcedAclDirectives() {
    }

    /**
     * The enforced directive strings in {@code pageText}, in document order.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the matched directive strings outside code, never {@code null}
     */
    static List< String > directives( final String pageText ) {
        final List< String > directives = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : spans( pageText ) ) {
            directives.add( pageText.substring( span.start(), span.end() ) );
        }
        return directives;
    }

    /**
     * The enforced directives with their offsets. The parse that locates code is only paid for when the scan finds a
     * candidate at all, so text with no directive-shaped span is never parsed as Markdown.
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
        final List< int[] > codeRanges = codeRegions( pageText );
        final List< AclDirectiveScanner.Span > enforced = new ArrayList<>( candidates.size() );
        for ( final AclDirectiveScanner.Span candidate : candidates ) {
            if ( !isInsideCode( codeRanges, candidate.start() ) ) {
                enforced.add( candidate );
            }
        }
        return enforced;
    }

    /**
     * {@code pageText} less every {@code [{ALLOW …}]} span, code or not — what
     * {@code ACL_PATTERN.matcher( pageText ).replaceAll( "" )} gave, without its running time.
     */
    static String withoutAnyDirective( final String pageText ) {
        final List< AclDirectiveScanner.Span > spans = AclDirectiveScanner.findAll( pageText, false );
        if ( spans.isEmpty() ) {
            return pageText;
        }
        final StringBuilder out = new StringBuilder( pageText.length() );
        int copiedTo = 0;
        for ( final AclDirectiveScanner.Span span : spans ) {
            out.append( pageText, copiedTo, span.start() );
            copiedTo = span.end();
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

    /** True if {@code offset} falls within any of the {@code [start, end)} code ranges. */
    private static boolean isInsideCode( final List< int[] > ranges, final int offset ) {
        for ( final int[] range : ranges ) {
            if ( offset >= range[ 0 ] && offset < range[ 1 ] ) {
                return true;
            }
        }
        return false;
    }
}

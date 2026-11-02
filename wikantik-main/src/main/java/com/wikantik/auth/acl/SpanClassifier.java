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

import com.wikantik.auth.acl.AccessRules.Shape;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.OffsetRanges;

import java.util.HashMap;
import java.util.Map;

/**
 * How {@link AccessRules} classifies one candidate span of a parsed text: by its region, then its spelling, then whether
 * the render parse sees it as one label. Built once per text; ask about candidates in ascending order of start.
 */
final class SpanClassifier {

    private final String text;
    private final int bodyStart;
    private final OffsetRanges code;
    private final OffsetRanges math;
    private final OffsetRanges html;
    private final OffsetRanges links;
    private final DirectiveSpelling.LineEnds lineEnds;
    /** The render path's rules, by start. */
    private final Map< Integer, RenderPathAccessRules.Rule > rules = new HashMap<>();
    /** Every rule-shaped label the parse sees — the render path's rules and the labels it does not read — by start. */
    private final Map< Integer, RenderPathAccessRules.Rule > labels = new HashMap<>();

    SpanClassifier( final String text, final int bodyStart, final RenderPathAccessRules.Structure structure ) {
        this.text = text;
        this.bodyStart = bodyStart;
        this.code = OffsetRanges.of( structure.code() );
        this.math = OffsetRanges.of( structure.math() );
        this.html = OffsetRanges.of( structure.html() );
        this.links = OffsetRanges.of( structure.links() );
        this.lineEnds = new DirectiveSpelling.LineEnds( text );
        for ( final RenderPathAccessRules.Rule rule : structure.rules() ) {
            rules.put( rule.start(), rule );
            labels.put( rule.start(), rule );
        }
        for ( final RenderPathAccessRules.Rule label : structure.labels() ) {
            labels.putIfAbsent( label.start(), label );
        }
    }

    /**
     * @param start      offset of the candidate's {@code [}
     * @param end        offset just past it
     * @param scannedEnd where the old text scan's rule starting here ends; {@code null} when it read none
     * @return the candidate's shape, or {@code null} when it is literal text that neither old reading took for a rule
     */
    Shape classify( final int start, final int end, final Integer scannedEnd ) {
        final boolean readByEither = rules.containsKey( start ) || scannedEnd != null;
        final Shape region = region( start );
        if ( region != null ) {
            return region == Shape.ESCAPED || readByEither ? region : null;
        }
        final Shape spelling = DirectiveSpelling.of( text, start, end, lineEnds );
        if ( spelling == Shape.DENY || spelling == Shape.ALLOW_PREFIXED_WORD ) {
            return readByEither ? spelling : null;
        }
        return spelling == Shape.BAD_PERMISSION || spelling == Shape.UNREADABLE ? spelling : ruleShape( start, end, scannedEnd, spelling );
    }

    /** A candidate in body prose spelled as a rule ({@code spelling} {@code null} when canonically). */
    private Shape ruleShape( final int start, final int end, final Integer scannedEnd, final Shape spelling ) {
        final RenderPathAccessRules.Rule label = labels.get( start );
        if ( label == null || label.end() != end ) {
            return Shape.UNREADABLE;     // a rule shape the render parse does not see as one label over this span
        }
        if ( spelling != null ) {
            return spelling;
        }
        // Spelled canonically: a rule only where both old readings read it over the same span.
        return rules.containsKey( start ) && scannedEnd != null && scannedEnd == end ? Shape.CANONICAL : Shape.UNREADABLE;
    }

    /**
     * @param start the start of a span {@link #classify} read as a rule
     * @return its rule line in canonical form, from its label as the parse reads it
     */
    String ruleLine( final int start ) {
        return DirectiveSpelling.canonical( labels.get( start ).ruleLine() );
    }

    /**
     * The shape a candidate's region gives it, or {@code null} in body prose. Code is {@link Shape#UNREADABLE}: the render
     * path calls it code, so only a text scan that read a rule there makes it a span.
     */
    private Shape region( final int start ) {
        if ( start < bodyStart ) {
            return Shape.IN_FRONTMATTER;
        }
        if ( code.contains( start ) ) {
            return Shape.UNREADABLE;
        }
        if ( math.contains( start ) ) {
            return Shape.IN_MATH;
        }
        if ( html.contains( start ) ) {
            return Shape.IN_HTML_BLOCK;
        }
        if ( links.contains( start ) ) {
            return Shape.LINK_WITH_TARGET;
        }
        return DirectiveSpelling.escaped( text, start ) ? Shape.ESCAPED : null;
    }
}

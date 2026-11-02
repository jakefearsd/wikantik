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
package com.wikantik.api.exceptions;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by the save pipeline when a save would add an access rule ({@code [{ALLOW …}]}) the wiki cannot read: such a
 * span locks the page to administrators, so any editor could otherwise lock a page out, themselves included. Extends
 * {@link FilterException} so it flows through the {@code preSave} filter chain; the REST and MCP save surfaces catch it
 * and return the structured {@link #violations()} (HTTP 422 for REST).
 */
public class AccessRuleShapeException extends FilterException {

    private static final long serialVersionUID = 1L;

    /** The machine code every violation carries. */
    public static final String CODE = "ACCESS_RULE_SHAPE";

    /**
     * One refused span.
     *
     * @param code    always {@link #CODE}
     * @param shape   how the span is written (the reader's shape name, e.g. {@code DENY})
     * @param line    the 1-based line of the page body the span starts on, as the editor shows the body (the first line
     *                after the frontmatter's closing {@code ---} is line 1); {@code null} for a span in the frontmatter
     * @param field   the frontmatter field the span is in; {@code null} for a span in the body (or in a frontmatter line
     *                no field owns)
     * @param text    the span's text (cut short when long)
     * @param message what is wrong and how to write it instead
     */
    public record Violation( String code, String shape, Integer line, String field, String text, String message ) {
    }

    /**
     * The most violations a refusal keeps. A page of nothing but openers holds one per few characters (a megabyte holds
     * about 150,000), so every surface that shows a refusal (the 422 body, the MCP result, the importer's job state, this
     * message) names the first ones and counts the rest in {@link #total()}.
     */
    public static final int MAX_VIOLATIONS = 50;

    private final transient List< Violation > violations;

    private final int total;

    /** @param violations the refused spans, in document order; at least one. Only the first {@link #MAX_VIOLATIONS} are kept */
    public AccessRuleShapeException( final List< Violation > violations ) {
        this( violations, violations.size() );
    }

    /**
     * @param violations the first refused spans, in document order; at least one. Only the first {@link #MAX_VIOLATIONS}
     *                   are kept
     * @param total      how many spans were refused in all (at least {@code violations.size()})
     */
    public AccessRuleShapeException( final List< Violation > violations, final int total ) {
        super( message( capped( violations ), Math.max( total, violations.size() ) ) );
        this.violations = capped( violations );
        this.total = Math.max( total, violations.size() );
    }

    private static List< Violation > capped( final List< Violation > violations ) {
        return List.copyOf( violations.size() <= MAX_VIOLATIONS ? violations : violations.subList( 0, MAX_VIOLATIONS ) );
    }

    private static String message( final List< Violation > kept, final int total ) {
        final String listed = kept.stream().map( Violation::message ).collect( Collectors.joining( "; " ) );
        return "Access rules refused: " + listed + ( total > kept.size() ? "; and " + ( total - kept.size() ) + " more" : "" );
    }

    /** @return the first refused spans (at most {@link #MAX_VIOLATIONS}), in document order */
    public List< Violation > violations() {
        return violations;
    }

    /** @return how many spans were refused in all; more than {@code violations().size()} when the list was capped */
    public int total() {
        return total;
    }
}

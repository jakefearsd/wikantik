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
package com.wikantik.markdown.extensions.math;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Pre-processes Markdown source text to convert {@code $$...$$} display math blocks
 * into {@code ```math} fenced code blocks that the Flexmark GitLab extension can render.
 *
 * <p>Only standalone {@code $$} delimiters (on their own line, with optional whitespace)
 * are transformed. Inline occurrences within a paragraph are left untouched.</p>
 */
public final class DisplayMathPreProcessor {

    /**
     * Matches a {@code $$} delimiter that occupies an entire line (optional surrounding whitespace).
     * The pattern captures a pair of opening/closing delimiters with content between them.
     *
     * <p>Explanation:
     * <ul>
     *   <li>Group 1 ({@code ^([ \t]*)}) — captures the leading indentation of the opening
     *       delimiter so we can re-apply it to the emitted {@code ```math} / {@code ```}
     *       fences. This is essential for math blocks nested inside list items, where
     *       dropping the indent would break list continuation and cause subsequent
     *       indented prose to be parsed as an indented code block.</li>
     *   <li>{@code \$\$[ \t]*$} — the opening {@code $$} with optional trailing whitespace</li>
     *   <li>Group 2 ({@code (.*?)}) — the math content (non-greedy, including newlines via DOTALL)</li>
     *   <li>The closing {@code $$} must also be on its own line (leading/trailing whitespace allowed)</li>
     * </ul>
     */
    private static final Pattern DISPLAY_MATH_BLOCK = Pattern.compile(
            "^([ \\t]*)\\$\\$[ \\t]*$\\r?\\n(.*?)^[ \\t]*\\$\\$[ \\t]*$",
            Pattern.MULTILINE | Pattern.DOTALL
    );

    private DisplayMathPreProcessor() {
        // utility class
    }

    /**
     * Transforms {@code $$...$$} display math blocks into {@code ```math} fenced code blocks.
     *
     * @param source the raw Markdown source text; may be {@code null}
     * @return the transformed text, or the original value when {@code null}/empty
     */
    public static String transform( final String source ) {
        if ( source == null || source.isEmpty() ) {
            return source;
        }

        final Matcher matcher = DISPLAY_MATH_BLOCK.matcher( source );
        final StringBuilder result = new StringBuilder();

        while ( matcher.find() ) {
            final String indent = matcher.group( 1 );
            String content = matcher.group( 2 );
            // Strip trailing line break — the regex captures it because (.*?) extends
            // to the line before the closing $$ delimiter. Handle both \n and \r\n.
            if ( content.endsWith( "\r\n" ) ) {
                content = content.substring( 0, content.length() - 2 );
            } else if ( content.endsWith( "\n" ) ) {
                content = content.substring( 0, content.length() - 1 );
            }
            // Preserve the opening delimiter's indent on both fences so math blocks
            // inside list items keep the list's continuation indent intact.
            final String replacement = content.isEmpty()
                    ? indent + "```math\n" + indent + "```"
                    : indent + "```math\n" + Matcher.quoteReplacement( content ) + "\n" + indent + "```";
            matcher.appendReplacement( result, replacement );
        }
        matcher.appendTail( result );

        return result.toString();
    }

}

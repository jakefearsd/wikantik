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

import java.util.ArrayList;
import java.util.List;
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
        return transformTracked( source ).text();
    }

    /**
     * One rewritten {@code $$...$$} block: {@code source[sourceStart, sourceEnd)} became
     * {@code text[targetStart, targetEnd)}. The math content is copied verbatim:
     * {@code source[sourceContent, sourceContent + contentLength)} is {@code text[targetContent, targetContent +
     * contentLength)}; everything else in the edit is generated fence markup. Text outside the edits is copied unchanged.
     *
     * @param sourceStart   start of the block in the source
     * @param sourceEnd     end of the block in the source
     * @param targetStart   start of its replacement in the transformed text
     * @param targetEnd     end of its replacement in the transformed text
     * @param sourceContent where the copied content starts in the source
     * @param targetContent where it starts in the transformed text
     * @param contentLength its length
     */
    public record Edit( int sourceStart, int sourceEnd, int targetStart, int targetEnd,
                        int sourceContent, int targetContent, int contentLength ) {
    }

    /**
     * The transformed text and where it differs from the source, so a position in it can be taken back.
     *
     * @param text  what {@link #transform(String)} returns
     * @param edits the rewritten blocks, in order
     */
    public record Tracked( String text, List< Edit > edits ) {
    }

    /**
     * {@link #transform(String)}, also reporting each rewritten block.
     *
     * @param source the raw Markdown source text; must not be {@code null}
     * @return the transformed text with its edits (none, and the very same text, when there is no display math)
     */
    public static Tracked transformTracked( final String source ) {
        // No display-math delimiter anywhere means the DOTALL/MULTILINE regex below
        // cannot match. Most pages carry no math at all, and this runs on every parse —
        // including once per hit on the search path — so short-circuit on a plain indexOf
        // instead of a backtracking scan of the whole body.
        if ( !source.contains( "$$" ) ) {
            return new Tracked( source, List.of() );
        }

        final Matcher matcher = DISPLAY_MATH_BLOCK.matcher( source );
        final StringBuilder result = new StringBuilder();
        final List< Edit > edits = new ArrayList<>();
        int copied = 0;
        while ( matcher.find() ) {
            result.append( source, copied, matcher.start() );
            final int targetStart = result.length();
            final String indent = matcher.group( 1 );
            final String content = content( matcher.group( 2 ) );
            result.append( replacement( indent, content ) );
            edits.add( new Edit( matcher.start(), matcher.end(), targetStart, result.length(),
                    matcher.start( 2 ), targetStart + indent.length() + MATH_OPEN.length(), content.length() ) );
            copied = matcher.end();
        }
        if ( edits.isEmpty() ) {
            return new Tracked( source, List.of() );
        }
        result.append( source, copied, source.length() );
        return new Tracked( result.toString(), List.copyOf( edits ) );
    }

    /** The opening fence written in place of the opening {@code $$} line (after its indent). */
    private static final String MATH_OPEN = "```math\n";

    /**
     * The captured math content less its trailing line break — the regex captures it because (.*?) extends to the
     * line before the closing $$ delimiter. Handles both \n and \r\n.
     */
    private static String content( final String captured ) {
        if ( captured.endsWith( "\r\n" ) ) {
            return captured.substring( 0, captured.length() - 2 );
        }
        return captured.endsWith( "\n" ) ? captured.substring( 0, captured.length() - 1 ) : captured;
    }

    /** The {@code ```math} fence that replaces one block. */
    private static String replacement( final String indent, final String content ) {
        // Preserve the opening delimiter's indent on both fences so math blocks
        // inside list items keep the list's continuation indent intact.
        return content.isEmpty()
                ? indent + MATH_OPEN + indent + "```"
                : indent + MATH_OPEN + content + "\n" + indent + "```";
    }

}

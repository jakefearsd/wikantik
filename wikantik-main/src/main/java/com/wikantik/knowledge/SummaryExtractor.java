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
package com.wikantik.knowledge;

import java.util.regex.Pattern;

/**
 * Extracts a representative summary sentence from wiki page body text using
 * heuristic sentence selection. Used to auto-generate the {@code summary}
 * frontmatter field when a page is saved without one.
 *
 * @since 1.0
 */
public final class SummaryExtractor {

    /** Minimum character length for a sentence to be considered suitable. */
    static final int MIN_LENGTH = 40;

    /** Maximum character length for the returned summary. */
    static final int MAX_LENGTH = 200;

    private static final Pattern PLUGIN_CALL = Pattern.compile( "\\[\\{[^}]*\\}\\](?:\\([^)]*\\))?" );
    private static final Pattern SENTENCE_SPLIT = Pattern.compile( "(?<=[.!?])\\s+" );

    private SummaryExtractor() {}

    /**
     * Extracts a summary sentence from the given wiki page body.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Strips plugin calls ({@code [{...}](...)}).</li>
     *   <li>Strips markdown via {@link NodeTextAssembler#stripMarkdown(String)}.</li>
     *   <li>Splits into sentences on sentence-terminal punctuation.</li>
     *   <li>Returns the first sentence with length in [{@value #MIN_LENGTH}, {@value #MAX_LENGTH}].</li>
     *   <li>Fallback: takes the first non-empty sentence, truncated to {@value #MAX_LENGTH} + "...".</li>
     * </ol>
     *
     * @param body raw wiki page body, may be {@code null}
     * @return extracted summary, or {@code ""} if nothing suitable is found
     */
    public static String extract( final String body ) {
        if( body == null || body.isBlank() ) return "";

        // Strip plugin calls first, before markdown stripping
        String text = PLUGIN_CALL.matcher( body ).replaceAll( " " );

        // Strip markdown (also strips frontmatter, headings, links, emphasis, etc.)
        text = NodeTextAssembler.stripMarkdown( text );

        if( text.isBlank() ) return "";

        final String[] sentences = SENTENCE_SPLIT.split( text );

        String firstNonEmpty = null;
        for( final String raw : sentences ) {
            final String sentence = raw.trim();
            if( sentence.isEmpty() ) continue;
            if( firstNonEmpty == null ) firstNonEmpty = sentence;
            if( sentence.length() >= MIN_LENGTH && sentence.length() <= MAX_LENGTH ) {
                return sentence;
            }
        }

        // Fallback: truncate the first non-empty sentence
        if( firstNonEmpty == null ) return "";
        if( firstNonEmpty.length() <= MAX_LENGTH ) return firstNonEmpty;
        return firstNonEmpty.substring( 0, MAX_LENGTH ) + "...";
    }
}

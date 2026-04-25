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
package com.wikantik.knowledge.extraction;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-extraction predicate. Decides whether a chunk is worth handing to the
 * LLM extractor. Pure function: no I/O, no state beyond the four config flags
 * passed at construction.
 *
 * <p>Two predicates currently:
 * <ul>
 *   <li><b>pure code block</b> — chunk text is one fenced code block with no
 *       prose around it. The extractor's prompt asks for named entities in
 *       prose; identifiers inside code aren't useful to it.</li>
 *   <li><b>no proper-noun candidate</b> — chunk text contains no token
 *       matching {@code \b[A-Z]\w{2,}\b}. Without one, the extractor has
 *       nothing to ground a named entity on.</li>
 * </ul>
 *
 * <p>Operates on chunk text only; heading_path is intentionally not inspected
 * (the extractor's prompt already includes it, so a chunk whose only caps are
 * in its heading still has nothing extractable in the body).
 */
public final class ChunkExtractionPrefilter {

    /** A non-skipping filter; used when the feature is off or in tests. */
    public static ChunkExtractionPrefilter passthrough() {
        return new ChunkExtractionPrefilter(
            /*enabled*/ false, /*dryRun*/ false,
            /*skipPureCode*/ false, /*skipNoProperNoun*/ false );
    }

    private static final Pattern PROPER_NOUN = Pattern.compile( "\\b[A-Z]\\w{2,}\\b" );
    private static final Pattern PURE_CODE_BLOCK = Pattern.compile(
        "(?s)\\A\\s*```[^\\n]*\\n.*?\\n```\\s*\\z" );

    private final boolean enabled;
    private final boolean dryRun;
    private final boolean skipPureCode;
    private final boolean skipNoProperNoun;

    public ChunkExtractionPrefilter( final boolean enabled,
                                     final boolean dryRun,
                                     final boolean skipPureCode,
                                     final boolean skipNoProperNoun ) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.skipPureCode = skipPureCode;
        this.skipNoProperNoun = skipNoProperNoun;
    }

    /** True iff the master switch is on; cheap predicate so callers can skip
     *  pre-extraction work (e.g. {@code findByIds}) when the filter is off. */
    public boolean isEnabled() {
        return enabled;
    }

    public Decision evaluate( final String text, final List< String > headingPath ) {
        if( !enabled ) {
            return new Decision( true, "disabled" );
        }
        final String body = text == null ? "" : text;
        final String reason;
        if( skipPureCode && isPureCodeBlock( body ) ) {
            reason = "pure_code";
        } else if( skipNoProperNoun && !hasProperNoun( body ) ) {
            reason = "no_proper_noun";
        } else {
            return new Decision( true, "ok" );
        }
        if( dryRun ) {
            return new Decision( true, "dry_run:" + reason );
        }
        return new Decision( false, reason );
    }

    private static boolean isPureCodeBlock( final String text ) {
        return PURE_CODE_BLOCK.matcher( text ).matches();
    }

    private static boolean hasProperNoun( final String text ) {
        return PROPER_NOUN.matcher( text ).find();
    }

    public record Decision( boolean shouldExtract, String reason ) {}
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic chunk → entity mention attribution. For each resolved entity
 * name, scan each chunk's text for whole-word matches (case-insensitive
 * presence, case-preserving surface form). No LLM, no I/O.
 */
public final class MentionAttributor {

    /**
     * Per-entity-name compiled-pattern cache.
     *
     * <p>The whole-word match regex is a pure function of the entity name, but
     * {@link #attribute} is invoked once per content chunk against the same
     * entity vocabulary — see {@code MentionAttributionRunner}, which builds
     * one {@code NameMapping} list and reuses it for every chunk of every
     * page. Without caching, the same patterns are recompiled
     * {@code chunks × names} times across an extraction run that spans hours
     * on a large corpus; caching collapses that to one compile per distinct
     * name.</p>
     *
     * <p>Keyed by the entity name string. The key space is the corpus entity
     * vocabulary — bounded and small (compiled {@link Pattern}s are lightweight),
     * so the cache is intentionally unbounded; it is not a request-scoped map.
     * {@link ConcurrentHashMap} because the extraction pipeline can attribute
     * chunks from a shared instance on more than one worker thread; the cached
     * {@link Pattern} objects are themselves immutable and thread-safe.</p>
     */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public List<ChunkMention> attribute(final UUID chunkId, final String chunkText,
                                         final List<NameMapping> names) {
        final List<ChunkMention> out = new ArrayList<>();
        if (chunkText == null || chunkText.isEmpty() || names.isEmpty()) {
            return out;
        }
        for (final NameMapping nm : names) {
            // Reuse the compiled pattern across chunks — see patternCache javadoc.
            // The pattern depends only on nm.name(), never on the chunk text,
            // so caching by name is exact, not approximate.
            final Pattern p = patternCache.computeIfAbsent(nm.name(), MentionAttributor::compileWholeWord);
            final Matcher m = p.matcher(chunkText);
            while (m.find()) {
                out.add(new ChunkMention(chunkId, nm.nodeId(), m.group(), m.start(), m.end()));
            }
        }
        return out;
    }

    /** Builds the whole-word, case-insensitive match pattern for one entity name. */
    private static Pattern compileWholeWord(final String name) {
        // (?<!\w) / (?!\w) keep "Java" from matching inside "JavaScript" but still
        // accept names that end in non-word chars like "C++" or ".NET" — \b alone
        // would reject those because there is no \w-vs-\W boundary at "+|space".
        // Pattern.quote handles regex metachars in the entity name.
        return Pattern.compile(
            "(?<!\\w)" + Pattern.quote(name) + "(?!\\w)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    public record NameMapping(UUID nodeId, String name) {}

    public record ChunkMention(UUID chunkId, UUID nodeId, String surfaceForm,
                               int startOffset, int endOffset) {}
}

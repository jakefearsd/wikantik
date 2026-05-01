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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic chunk → entity mention attribution. For each resolved entity
 * name, scan each chunk's text for whole-word matches (case-insensitive
 * presence, case-preserving surface form). No LLM, no I/O.
 */
public final class MentionAttributor {

    public List<ChunkMention> attribute(final UUID chunkId, final String chunkText,
                                         final List<NameMapping> names) {
        final List<ChunkMention> out = new ArrayList<>();
        if (chunkText == null || chunkText.isEmpty() || names.isEmpty()) {
            return out;
        }
        for (final NameMapping nm : names) {
            // (?<!\w) / (?!\w) keep "Java" from matching inside "JavaScript" but still
            // accept names that end in non-word chars like "C++" or ".NET" — \b alone
            // would reject those because there is no \w-vs-\W boundary at "+|space".
            // Pattern.quote handles regex metachars in the entity name.
            final Pattern p = Pattern.compile(
                "(?<!\\w)" + Pattern.quote(nm.name()) + "(?!\\w)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            final Matcher m = p.matcher(chunkText);
            while (m.find()) {
                out.add(new ChunkMention(chunkId, nm.nodeId(), m.group(), m.start(), m.end()));
            }
        }
        return out;
    }

    public record NameMapping(UUID nodeId, String name) {}

    public record ChunkMention(UUID chunkId, UUID nodeId, String surfaceForm,
                               int startOffset, int endOffset) {}
}

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
package com.wikantik.mcp.tools;

import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side frontmatter normalization shared by {@link WritePagesTool} and
 * {@link UpdatePageTool}. Closes a recurring class of agent errors where a
 * caller hand-crafts a markdown file with YAML frontmatter and forgets that
 * values containing {@code ':'} (e.g. {@code title: Woodworking Joinery: Structural
 * Mechanics}) need to be quoted. SnakeYAML's emitter handles quoting correctly
 * when given a {@code Map}, so the safe path is: parse whatever frontmatter the
 * agent embedded in {@code content}, merge it with the explicit {@code metadata}
 * argument, and re-emit through {@link FrontmatterWriter}. The caller never has
 * to know YAML quoting rules.
 *
 * <p>If the embedded YAML block is malformed beyond recovery, this throws
 * {@link FrontmatterParseException} so the tool can report a structured error
 * (line/column/message) back to the agent rather than silently dropping
 * metadata.</p>
 */
final class FrontmatterNormalizer {

    private FrontmatterNormalizer() {
    }

    /**
     * Result of normalisation — body (no frontmatter) and the merged metadata map.
     * Callers pass these to {@code PageSaveHelper.saveText(pageName, body, options)}
     * with {@code options.metadata(merged)}; the save helper composes well-quoted
     * YAML via {@code FrontmatterWriter}.
     */
    static final class Normalized {
        private final String body;
        private final Map< String, Object > metadata;

        Normalized( final String body, final Map< String, Object > metadata ) {
            this.body = body;
            this.metadata = metadata;
        }

        String body() {
            return body;
        }

        Map< String, Object > metadata() {
            return metadata;
        }
    }

    /**
     * Splits an agent-supplied {@code content} string into a body + merged metadata
     * map whose YAML representation is guaranteed to round-trip through
     * {@link FrontmatterParser}.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>If {@code content} starts with a {@code ---} frontmatter block, parse it
     *       strictly. The body is the text after the closing delimiter.</li>
     *   <li>Embedded frontmatter is merged with {@code explicitMetadata} — explicit
     *       keys win, so the agent's structured arg always overrides whatever the
     *       embedded block said. This matches the MCP "metadata is the canonical
     *       form" contract while still being forgiving of agents that only know
     *       the markdown form.</li>
     *   <li>{@code content} without a leading {@code ---} block is treated entirely
     *       as body — the explicit metadata map alone becomes the frontmatter.</li>
     * </ul>
     *
     * @throws FrontmatterParseException when {@code content} starts with {@code ---}
     *         but the YAML inside is malformed (e.g. unquoted colon in a {@code title:}
     *         value).
     */
    static Normalized normalize( final String content, final Map< String, Object > explicitMetadata )
            throws FrontmatterParseException {
        final String safeContent = content == null ? "" : content;

        final boolean hasEmbeddedFrontmatter = safeContent.startsWith( "---\r\n" )
                || safeContent.startsWith( "---\n" );

        final Map< String, Object > merged = new LinkedHashMap<>();
        final String body;
        if ( hasEmbeddedFrontmatter ) {
            final ParsedPage parsed = FrontmatterParser.parseStrict( safeContent );
            merged.putAll( parsed.metadata() );
            body = parsed.body();
        } else {
            body = safeContent;
        }

        if ( explicitMetadata != null && !explicitMetadata.isEmpty() ) {
            merged.putAll( explicitMetadata );
        }
        return new Normalized( body, merged );
    }
}

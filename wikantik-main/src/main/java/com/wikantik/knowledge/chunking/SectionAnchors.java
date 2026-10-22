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
package com.wikantik.knowledge.chunking;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.export.HeadingSlugs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps a chunk's {@code heading_path} to the anchor the page view gives that section, so a
 * retrieval result can link straight to it ({@code /wiki/Page#anchor}).
 *
 * <p>The view anchors only h2/h3 headings, so a deeper section resolves to its nearest h2/h3
 * ancestor, and a path with no h2/h3 on it (a page-title h1) has no anchor. Paths are rebuilt the
 * way {@link ContentChunker} records them; a path that occurs twice resolves to its first section.
 */
public final class SectionAnchors {

    private static final Parser PARSER = Parser.builder().build();

    /**
     * Heading markup the wiki's renderer turns into different text than this plain parser sees —
     * wikilinks, plugins and a trailing attribute block — so the view's id for such a heading cannot
     * be predicted from its source, and its sections link to the nearest ancestor instead.
     */
    private static final Pattern RENDERER_REWRITTEN = Pattern.compile("\\[\\[|\\[\\{|\\{[^}`]*}\\s*$");

    /** A heading-stack slot; {@link #PAD} fills a skipped level. */
    private record Entry(String title, Heading heading) {}

    private static final Entry PAD = new Entry("", null);

    private SectionAnchors() {}

    /** Every heading path the page body yields, mapped to its section's anchor; empty for a null or heading-less body. */
    public static Map<List<String>, String> byHeadingPath(final String markdownBody) {
        final String body = ContentChunker.chunkableBody(markdownBody);
        if (body.isEmpty()) {
            return Map.of();
        }
        final Document doc = PARSER.parse(body);
        final Map<Heading, String> viewAnchors = HeadingSlugs.viewAnchors(doc);
        final Deque<Entry> stack = new ArrayDeque<>();
        final Map<List<String>, String> out = new HashMap<>();
        for (final Node child : doc.getChildren()) {
            if (child instanceof Heading heading) {
                HeadingPaths.push(stack, heading.getLevel(), new Entry(HeadingPaths.title(heading), heading), PAD);
                final String anchor = nearestAnchor(stack, viewAnchors);
                if (anchor != null) {
                    out.putIfAbsent(HeadingPaths.path(stack.stream().map(Entry::title).toList()), anchor);
                }
            }
        }
        return out;
    }

    /** The view anchor of the deepest h2/h3 on the stack whose anchor is predictable, or null when there is none. */
    private static String nearestAnchor(final Deque<Entry> stack, final Map<Heading, String> viewAnchors) {
        final Iterator<Entry> it = stack.descendingIterator();
        while (it.hasNext()) {
            final Entry entry = it.next();
            final String anchor = entry.heading() == null ? null : viewAnchors.get(entry.heading());
            // A heading with no slug characters gets no id in the view.
            if (anchor != null && !anchor.isEmpty() && !RENDERER_REWRITTEN.matcher(entry.title()).find()) {
                return anchor;
            }
        }
        return null;
    }
}

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
import com.vladsch.flexmark.util.ast.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * How a chunk's {@code heading_path} follows from the headings above it — shared by
 * {@link ContentChunker}, which records it, and {@link SectionAnchors}, which resolves it back
 * to the page view's anchor, so the two cannot disagree about a section's path. Changing any of
 * this changes every chunk's {@code heading_path}, and with it the chunk content hashes.
 */
final class HeadingPaths {

    private HeadingPaths() {}

    /**
     * Extracts the heading's full title by walking its inline children and
     * concatenating their source ranges. Flexmark's {@code Heading.getText()}
     * returns a {@link com.vladsch.flexmark.util.sequence.BasedSequence} covering
     * the ATX text span in the current version (0.64.8), but that behaviour is
     * brittle across versions: sibling releases have returned only the first
     * child's characters, silently truncating headings that contain inline
     * markup like {@code ## First *emphasized* Section}. Walking the children
     * explicitly and using {@code getChars()} on each one preserves the full
     * source-range title (markers included) regardless of Flexmark version.
     */
    static String title(final Heading heading) {
        final StringBuilder sb = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            sb.append(child.getChars());
            child = child.getNext();
        }
        return sb.toString().strip().trim();
    }

    /**
     * Puts {@code entry} on the heading stack at {@code level} (1-based): drops the entries at or
     * below that level, and pads a skipped level (a {@code ###} with no {@code ##} above it) with
     * {@code padding}.
     */
    static <T> void push(final Deque<T> stack, final int level, final T entry, final T padding) {
        while (stack.size() >= level) {
            stack.removeLast();
        }
        while (stack.size() < level - 1) {
            stack.addLast(padding);
        }
        stack.addLast(entry);
    }

    /**
     * Builds a heading path snapshot from the stack's titles, stripping leading
     * empty entries that the stack pads in when a subheading appears without a
     * parent heading (e.g. a {@code ##} at the top of a page). Downstream
     * callers treat {@code heading_path} as the *meaningful* hierarchy, so a
     * leading {@code ""} placeholder isn't useful to preserve.
     */
    static List<String> path(final Collection<String> titles) {
        final List<String> list = new ArrayList<>(titles);
        int firstNonEmpty = 0;
        while (firstNonEmpty < list.size() && list.get(firstNonEmpty).isEmpty()) {
            firstNonEmpty++;
        }
        return List.copyOf(list.subList(firstNonEmpty, list.size()));
    }
}

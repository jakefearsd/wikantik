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

import java.util.Locale;

/**
 * Marks the byte offsets of a Markdown body that fall inside code — fenced code blocks
 * ({@code ```} / {@code ~~~}, but NOT {@code ```math}, which is display math) and inline
 * {@code `code`} spans. Math delimiters inside these regions are literal examples, not math,
 * and must be ignored by every validator. This is the primary false-positive defense.
 */
public final class CodeRegions {

    private final boolean[] masked;

    private CodeRegions(final boolean[] masked) { this.masked = masked; }

    /** True when the character at {@code offset} is inside a code region. */
    public boolean isMasked(final int offset) {
        return offset >= 0 && offset < masked.length && masked[offset];
    }

    public static CodeRegions scan(final String body) {
        final boolean[] masked = new boolean[body.length()];
        final String[] lines = body.split("\n", -1);
        int offset = 0;
        boolean inFence = false;
        String fenceMarker = null;   // "```" or "~~~"
        boolean fenceIsMath = false;

        for (final String line : lines) {
            final String trimmed = line.strip();
            final boolean isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~");

            if (!inFence && isFence) {
                inFence = true;
                fenceMarker = trimmed.startsWith("```") ? "```" : "~~~";
                final String info = trimmed.substring(3).strip().toLowerCase( Locale.ROOT );
                fenceIsMath = info.equals("math");
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
            } else if (inFence && isFence && trimmed.startsWith(fenceMarker)) {
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
                inFence = false; fenceMarker = null; fenceIsMath = false;
            } else if (inFence) {
                if (!fenceIsMath) { maskLine(masked, offset, line.length()); }
            } else {
                maskInlineCode(masked, line, offset);
            }
            offset += line.length() + 1;   // +1 for the '\n' consumed by split
        }
        return new CodeRegions(masked);
    }

    private static void maskLine(final boolean[] masked, final int start, final int len) {
        for (int p = start; p < start + len && p < masked.length; p++) { masked[p] = true; }
    }

    /**
     * Masks paired backtick runs (run of N backticks closed by the next run of exactly N). Each run's partner is
     * precomputed right-to-left, so a line of many unmatched runs is linear instead of every opener rescanning the
     * rest of the line (same pairing as {@code com.wikantik.api.parser.CodeMask}).
     */
    private static void maskInlineCode(final boolean[] masked, final String line, final int base) {
        final java.util.List<int[]> runs = new java.util.ArrayList<>();   // {start, length}
        int i = line.indexOf('`');
        while (i >= 0) {
            int k = i;
            while (k < line.length() && line.charAt(k) == '`') { k++; }
            runs.add(new int[] { i, k - i });
            i = line.indexOf('`', k);
        }
        final int[] partner = new int[runs.size()];
        final java.util.Map<Integer, Integer> nextOfLength = new java.util.HashMap<>();
        for (int r = runs.size() - 1; r >= 0; r--) {
            final Integer next = nextOfLength.put(runs.get(r)[1], r);
            partner[r] = next == null ? -1 : next;
        }
        int r = 0;
        while (r < runs.size()) {
            final int close = partner[r];
            if (close < 0) { r++; continue; }   // unterminated run: leave unmasked, scan continues past opener
            final int end = runs.get(close)[0] + runs.get(close)[1];
            for (int p = base + runs.get(r)[0]; p < base + end && p < masked.length; p++) { masked[p] = true; }
            r = close + 1;
        }
    }
}

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

    /** Masks paired backtick runs (run of N backticks closed by a run of exactly N). */
    private static void maskInlineCode(final boolean[] masked, final String line, final int base) {
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '`') { i++; continue; }
            final int runStart = i;
            int n = 0;
            while (i < line.length() && line.charAt(i) == '`') { i++; n++; }
            int j = i;
            boolean closed = false;
            while (j < line.length()) {
                if (line.charAt(j) == '`') {
                    int k = j, m = 0;
                    while (k < line.length() && line.charAt(k) == '`') { k++; m++; }
                    if (m == n) {
                        for (int p = base + runStart; p < base + k && p < masked.length; p++) { masked[p] = true; }
                        i = k; closed = true; break;
                    }
                    j = k;
                } else { j++; }
            }
            if (!closed) { /* unterminated run: leave unmasked, scan continues past opener */ }
        }
    }
}

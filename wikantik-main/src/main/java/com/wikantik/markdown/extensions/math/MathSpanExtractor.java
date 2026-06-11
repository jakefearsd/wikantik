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
 * Extracts recognised math spans from a Markdown body, skipping code regions ({@link CodeRegions}).
 * Recognises: line-isolated {@code $$…$$} display blocks, {@code ```math} fences, and inline
 * {@code $…$}. Deliberately does NOT recognise inline-glued {@code $$} — that is a structure error
 * surfaced by {@link MathStructureValidator}, not a span to lint.
 */
public class MathSpanExtractor {

    /** Mirrors InlineMathParser: non-empty content not starting/ending with a space, no inner '$'. */
    private static final Pattern INLINE = Pattern.compile("\\$([^ $](?:[^$]*[^ $])?)\\$|\\$([^ $])\\$");

    public List<MathSpan> extract(final String body) {
        if (body == null || body.isEmpty()) { return List.of(); }
        final CodeRegions code = CodeRegions.scan(body);
        final List<MathSpan> spans = new ArrayList<>();
        final String[] lines = body.split("\n", -1);

        int offset = 0;
        int blockStart = -1;          // offset of opening delimiter line
        String closer = null;         // "$$" or "```"
        MathSpan.Kind blockKind = null;
        final StringBuilder content = new StringBuilder();

        for (final String line : lines) {
            final String trimmed = line.strip();
            if (closer == null) {
                if (trimmed.equals("$$")) {
                    closer = "$$"; blockKind = MathSpan.Kind.DISPLAY_DOLLAR;
                    blockStart = offset; content.setLength(0);
                } else if (trimmed.equals("```math")) {
                    closer = "```"; blockKind = MathSpan.Kind.MATH_FENCE;
                    blockStart = offset; content.setLength(0);
                } else if (!isFullyMasked(code, offset, line.length()) && !trimmed.startsWith("```")
                           && !trimmed.startsWith("~~~")) {
                    extractInline(line, offset, code, spans);
                }
            } else if (("$$".equals(closer) && trimmed.equals("$$"))
                       || ("```".equals(closer) && trimmed.startsWith("```"))) {
                final String text = content.length() > 0
                        ? content.substring(0, content.length() - 1) : "";   // drop trailing '\n'
                spans.add(new MathSpan(blockKind, text,
                        MathSourceRange.from(body, blockStart, offset + line.length())));
                closer = null; blockKind = null; content.setLength(0);
            } else {
                content.append(line).append('\n');
            }
            offset += line.length() + 1;
        }
        return spans;
    }

    private void extractInline(final String line, final int base, final CodeRegions code,
                               final List<MathSpan> out) {
        final Matcher m = INLINE.matcher(line);
        int from = 0;
        while (m.find(from)) {
            // Skip a $$ display marker (two adjacent $ at the match start should not be inline)
            if (m.start() > 0 && line.charAt(m.start() - 1) == '$') { from = m.end(); continue; }
            if (m.end() < line.length() && line.charAt(m.end()) == '$') { from = m.end(); continue; }
            final int s = base + m.start();
            if (code.isMasked(s)) { from = m.end(); continue; }
            final String c = m.group(1) != null ? m.group(1) : m.group(2);
            out.add(new MathSpan(MathSpan.Kind.INLINE_DOLLAR, c, rangeIn(line, base, m.start(), m.end())));
            from = m.end();
        }
    }

    private static MathSourceRange rangeIn(final String line, final int base, final int s, final int e) {
        // line-local line/col is always line 1 for an inline span; offsets are body-absolute
        return new MathSourceRange(base + s, base + e, 1, s + 1, 1, e + 1);
    }

    private static boolean isFullyMasked(final CodeRegions code, final int start, final int len) {
        for (int p = start; p < start + len; p++) { if (!code.isMasked(p)) { return false; } }
        return len > 0;
    }
}

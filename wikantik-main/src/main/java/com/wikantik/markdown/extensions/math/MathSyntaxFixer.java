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
 * Repairs the {@code math.inline.prose} defect that {@link MathStructureValidator} flags: a literal
 * currency {@code $} (e.g. {@code $100}) parsed as the opening of an inline-math span, which mangles
 * rendering (the prose between two such {@code $} is shown as math). The fix escapes the offending
 * currency {@code $} as {@code \$}.
 *
 * <p><b>It mirrors the validator's detection exactly</b> (same {@link CodeRegions} masking, same
 * {@code $$}-display exclusion, same left-to-right single-{@code $} pairing, same prose-stopword +
 * no-backslash flag test), so it can only ever touch a {@code $} the validator itself flags. Real
 * inline math — including <em>number-led</em> math like {@code $2^{256}$}, {@code $90^\circ$},
 * {@code $10^{12}$} (content has no English stopword, or contains a {@code \command}) — display
 * {@code $$…$$} blocks, fenced/inline code, and already-escaped {@code \$} are never altered.
 *
 * <p><b>Safety against mis-pairing.</b> A lone currency {@code $} can mis-pair with a genuine math
 * {@code $} across prose. So for each flagged pair only the <em>opening</em> {@code $} is escaped, and
 * only when it is immediately followed by a digit (true currency); a mis-paired math delimiter
 * (followed by whitespace/letter) is left alone and re-pairs correctly on the next iteration. The
 * pass iterates to a fixpoint. Net effect: every clearly-currency {@code $} that triggers a warning is
 * escaped, and no real math is ever broken. The transform is idempotent.
 */
public final class MathSyntaxFixer {

    /** Whole-word English stopwords marking inline-$ content as prose — copied from the validator. */
    private static final Pattern PROSE_STOPWORD = Pattern.compile(
            "(?i)\\b(and|the|or|is|are|of|to|for|with|per|at|on|in|from|by|as)\\b");

    private static final int MAX_ITERATIONS = 500;

    private MathSyntaxFixer() {}

    /** Escapes currency {@code $} the validator flags as inline prose; unchanged input returns the same string. */
    public static String escapeCurrency(final String body) {
        if (body == null || body.isEmpty() || body.indexOf('$') < 0) {
            return body;
        }
        String current = body;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            final List<Integer> openings = flaggedCurrencyOpenings(current);
            if (openings.isEmpty()) {
                break;                                  // no more clearly-currency openings to fix
            }
            current = escapeAt(current, openings);
        }
        // Same reference when nothing changed, so callers can detect "no change" with ==.
        return current.equals(body) ? body : current;
    }

    /**
     * Offsets of every {@code $} that (a) opens a pair the validator would flag as {@code math.inline.prose}
     * AND (b) is immediately followed by a digit (so it is currency, not a mis-paired math delimiter).
     */
    private static List<Integer> flaggedCurrencyOpenings(final String body) {
        final CodeRegions code = CodeRegions.scan(body);

        // Non-masked $$ display-delimiter offsets.
        final List<Integer> marks = new ArrayList<>();
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) == '$' && body.charAt(i + 1) == '$' && !code.isMasked(i)) {
                marks.add(i);
                i++;
            }
        }
        // Mask positions inside paired $$…$$ blocks.
        final boolean[] inDisplay = new boolean[body.length()];
        for (int k = 0; k + 1 < marks.size(); k += 2) {
            final int end = Math.min(marks.get(k + 1) + 2, body.length());
            for (int i = marks.get(k); i < end; i++) { inDisplay[i] = true; }
        }
        // True single-$ offsets (not code-masked, not in display, not escaped, not part of $$).
        final List<Integer> singles = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '$' || code.isMasked(i) || inDisplay[i]) { continue; }
            if (i > 0 && body.charAt(i - 1) == '\\') { continue; }
            if ((i > 0 && body.charAt(i - 1) == '$')
                    || (i + 1 < body.length() && body.charAt(i + 1) == '$')) { continue; }
            singles.add(i);
        }
        // Pair left-to-right; collect the digit-led opening of each prose-flagged pair.
        final List<Integer> openings = new ArrayList<>();
        for (int k = 0; k + 1 < singles.size(); k += 2) {
            final int open = singles.get(k);
            final int close = singles.get(k + 1);
            final String content = body.substring(open + 1, close);
            if (!content.contains("\\") && PROSE_STOPWORD.matcher(content).find()
                    && open + 1 < body.length() && Character.isDigit(body.charAt(open + 1))) {
                openings.add(open);
            }
        }
        return openings;
    }

    private static String escapeAt(final String body, final List<Integer> offsets) {
        final StringBuilder sb = new StringBuilder(body);
        for (int idx = offsets.size() - 1; idx >= 0; idx--) {   // high→low keeps earlier offsets valid
            sb.insert((int) offsets.get(idx), '\\');
        }
        return sb.toString();
    }

    /** A whole line that is a complete single-line display block: {@code $$ … $$} (content required). */
    private static final Pattern SINGLE_LINE_DISPLAY =
            Pattern.compile("^\\s*\\$\\$\\s*(\\S.*?\\S|\\S)\\s*\\$\\$\\s*$");

    /**
     * Repairs {@code math.display.notIsolated}: a single-line {@code $$ x $$} renders as literal text
     * because the {@code $$} delimiters are glued to the content. Rewrites each such line to a
     * blank-line-isolated block:
     * <pre>
     *
     * $$
     * x
     * $$
     *
     * </pre>
     * Lines inside fenced code blocks are skipped (the {@code $$} there is a literal example), and a
     * {@code $$ $$} block with no content is left for the {@code math.display.empty} warning. Returns the
     * same reference when nothing changed.
     */
    public static String isolateDisplayMath(final String body) {
        if (body == null || body.length() < 4 || !body.contains("$$")) {
            return body;
        }
        final String[] lines = body.split("\n", -1);
        final List<String> out = new ArrayList<>(lines.length + 8);
        boolean changed = false;
        boolean inFence = false;
        String fenceMarker = null;
        for (final String raw : lines) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String cr = raw.endsWith("\r") ? "\r" : "";
            final String trimmed = line.strip();
            final boolean isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (!inFence && isFence) {
                inFence = true;
                fenceMarker = trimmed.startsWith("```") ? "```" : "~~~";
                out.add(raw);
                continue;
            }
            if (inFence) {
                if (isFence && trimmed.startsWith(fenceMarker)) { inFence = false; fenceMarker = null; }
                out.add(raw);
                continue;
            }
            final Matcher m = SINGLE_LINE_DISPLAY.matcher(line);
            if (!m.matches()) {
                out.add(raw);
                continue;
            }
            changed = true;
            if (!out.isEmpty() && !out.get(out.size() - 1).strip().isEmpty()) {
                out.add("");                              // blank line before the block
            }
            out.add("$$" + cr);
            out.add(m.group(1).strip() + cr);
            out.add("$$" + cr);
            out.add("");                                  // blank line after the block
        }
        return changed ? String.join("\n", out) : body;
    }
}

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

import com.wikantik.api.frontmatter.schema.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects high-confidence math <em>structure</em> problems by scanning code-masked {@code $$} pairs.
 * <ul>
 *   <li>ERROR {@code math.display.notIsolated}: a {@code $$…$$} pair whose delimiters are not each
 *       alone on their own line — it renders as literal text (the FastenerEngineering / single-line bug).</li>
 *   <li>ERROR {@code math.display.unterminated}: an odd, unpaired {@code $$}.</li>
 *   <li>WARNING {@code math.display.empty}: a {@code $$…$$} pair with blank content.</li>
 * </ul>
 * Bare single-{@code $} balance is deliberately NOT checked — currency (<code>$5 and $10</code>)
 * would false-positive, and false positives are worse than a few escapes.
 */
public class MathStructureValidator {

    /** Whole-word English stopwords that mark inline-$ content as prose, not math. */
    private static final Pattern PROSE_STOPWORD = Pattern.compile(
            "(?i)\\b(and|the|or|is|are|of|to|for|with|per|at|on|in|from|by|as)\\b");

    public List<MathViolation> validate(final String body) {
        final List<MathViolation> out = new ArrayList<>();
        if (body == null || body.isEmpty()) { return out; }
        final CodeRegions code = CodeRegions.scan(body);

        // Collect non-masked $$ delimiter offsets.
        final List<Integer> marks = new ArrayList<>();
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) == '$' && body.charAt(i + 1) == '$' && !code.isMasked(i)) {
                marks.add(i);
                i++;   // consume both
            }
        }

        int k = 0;
        for (; k + 1 < marks.size(); k += 2) {
            final int open = marks.get(k);
            final int close = marks.get(k + 1);
            final String content = body.substring(open + 2, close);
            if (content.isBlank()) {
                out.add(new MathViolation("math.display.empty", Severity.WARNING,
                        "Empty display-math block ($$ $$).",
                        MathSourceRange.from(body, open, close + 2)));
                continue;
            }
            if (!delimiterAloneOnLine(body, open) || !delimiterAloneOnLine(body, close)) {
                out.add(new MathViolation("math.display.notIsolated", Severity.ERROR,
                        "Display math ($$…$$) is glued to surrounding text and will render as "
                                + "literal text. Put the $$ delimiters on their own lines.",
                        MathSourceRange.from(body, open, close + 2)));
            }
        }
        if (k < marks.size()) {   // dangling, unpaired $$
            final int open = marks.get(k);
            out.add(new MathViolation("math.display.unterminated", Severity.ERROR,
                    "Unterminated display-math block: an opening $$ has no matching closing $$.",
                    MathSourceRange.from(body, open, Math.min(open + 2, body.length()))));
        }
        out.addAll(checkInlineProse(body, code, marks));
        return out;
    }

    /**
     * WARNING {@code math.inline.prose}: an inline {@code $…$} pair whose content is prose (contains an
     * English stopword and no {@code \\command}) — typically a literal currency {@code $} being parsed
     * as math. Display {@code $$} blocks and code regions are excluded; escaped {@code \\$} is skipped.
     */
    private List<MathViolation> checkInlineProse(final String body, final CodeRegions code,
                                                 final List<Integer> displayMarks) {
        final List<MathViolation> out = new ArrayList<>();
        // Mask positions covered by paired $$…$$ display blocks.
        final boolean[] inDisplay = new boolean[body.length()];
        for (int k = 0; k + 1 < displayMarks.size(); k += 2) {
            final int end = Math.min(displayMarks.get(k + 1) + 2, body.length());
            for (int i = displayMarks.get(k); i < end; i++) { inDisplay[i] = true; }
        }
        // Collect true single-$ offsets (not code-masked, not in display, not escaped, not part of $$).
        final List<Integer> singles = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '$' || code.isMasked(i) || inDisplay[i]) { continue; }
            if (i > 0 && body.charAt(i - 1) == '\\') { continue; }                 // escaped \$
            if ((i > 0 && body.charAt(i - 1) == '$')
                    || (i + 1 < body.length() && body.charAt(i + 1) == '$')) { continue; }  // part of $$
            singles.add(i);
        }
        // Pair left-to-right (renderer order); flag pairs whose content is prose.
        for (int k = 0; k + 1 < singles.size(); k += 2) {
            final int open = singles.get(k);
            final int close = singles.get(k + 1);
            final String content = body.substring(open + 1, close);
            if (!content.contains("\\") && PROSE_STOPWORD.matcher(content).find()) {
                out.add(new MathViolation("math.inline.prose", Severity.WARNING,
                        "Inline $…$ contains prose (\"" + snippet(content) + "\"); a literal '$' "
                                + "(e.g. currency) is likely being parsed as math — escape it as \\$.",
                        MathSourceRange.from(body, open, close + 1)));
            }
        }
        return out;
    }

    private static String snippet(final String s) {
        final String t = s.strip();
        return t.length() <= 40 ? t : t.substring(0, 39) + "…";
    }

    /**
     * True when the {@code $$} at {@code pos} is alone on its own line — whitespace-only on BOTH
     * sides (so the content is on a different line). A single-line {@code $$x$$} fails this because
     * content follows the open (and precedes the close) on the same line.
     */
    private static boolean delimiterAloneOnLine(final String body, final int pos) {
        for (int i = pos - 1; i >= 0 && body.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(body.charAt(i))) { return false; }
        }
        for (int i = pos + 2; i < body.length() && body.charAt(i) != '\n'; i++) {
            if (!Character.isWhitespace(body.charAt(i))) { return false; }
        }
        return true;
    }
}

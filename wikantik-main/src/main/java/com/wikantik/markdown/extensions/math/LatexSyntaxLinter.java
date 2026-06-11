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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a single LaTeX expression using probe-validated rules from {@link LatexRules}.
 *
 * <p>ERROR rules (block save): unbalancedBraces, leftRightMismatch, beginEndMismatch, emptyScript,
 * doubleScript.
 *
 * <p>WARNING rules (savable, still reported): ampOutsideEnv, sqrtBadOptional, fracArity,
 * unknownCommand.
 */
public class LatexSyntaxLinter {

    private static final Pattern COMMAND = Pattern.compile("\\\\([a-zA-Z]+)");

    public List<MathViolation> lint(final String latex) {
        final List<MathViolation> out = new ArrayList<>();
        if (latex == null || latex.isBlank()) { return out; }
        final MathSourceRange whole = new MathSourceRange(0, latex.length(), 1, 1, 1, latex.length() + 1);

        // --- ERROR rules ---
        if (LatexRules.unbalancedBraces(latex)) {
            out.add(new MathViolation("math.syntax.unbalancedBraces", Severity.ERROR,
                    "Unbalanced { } braces — add or remove a brace to fix.", whole));
        }
        if (LatexRules.leftRightMismatch(latex)) {
            out.add(new MathViolation("math.syntax.leftRight", Severity.ERROR,
                    "\\left without a matching \\right (or vice versa) — every \\left delimiter needs a paired \\right.", whole));
        }
        if (LatexRules.beginEndMismatch(latex)) {
            out.add(new MathViolation("math.syntax.beginEnd", Severity.ERROR,
                    "\\begin{…} / \\end{…} environments do not match — check environment names and nesting.", whole));
        }
        if (LatexRules.emptyScript(latex)) {
            out.add(new MathViolation("math.syntax.emptyScript", Severity.ERROR,
                    "^ or _ with no argument — use ^ {} for an empty superscript or supply the argument.", whole));
        }
        if (LatexRules.doubleScript(latex)) {
            out.add(new MathViolation("math.syntax.doubleScript", Severity.ERROR,
                    "Double superscript or subscript (e.g. x^a^b) — wrap the first script in braces: x^{a^b}.", whole));
        }

        // --- WARNING rules ---
        if (LatexRules.ampOutsideEnv(latex)) {
            out.add(new MathViolation("math.syntax.ampOutsideEnv", Severity.WARNING,
                    "& used outside a \\begin{…}…\\end{…} environment — wrap in align, matrix, etc.", whole));
        }
        if (LatexRules.sqrtBadOptional(latex)) {
            out.add(new MathViolation("math.syntax.sqrtBadOptional", Severity.WARNING,
                    "\\sqrt[ without a matching ] — close the optional degree argument before the radicand.", whole));
        }
        if (LatexRules.fracArity(latex)) {
            out.add(new MathViolation("math.syntax.fracArity", Severity.WARNING,
                    "\\frac requires exactly two brace arguments {numerator}{denominator}.", whole));
        }

        // unknownCommand — one warning per expression is enough
        final Matcher m = COMMAND.matcher(latex);
        while (m.find()) {
            if (!LatexRules.KNOWN_COMMANDS.contains(m.group(1))) {
                out.add(new MathViolation("math.syntax.unknownCommand", Severity.WARNING,
                        "Unknown/unsupported command \\" + m.group(1) + " — check the KaTeX supported functions list.", whole));
                break;
            }
        }

        return out;
    }
}

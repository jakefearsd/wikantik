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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pragmatic, savable WARNING-level checks for one LaTeX expression. Approximate vs real KaTeX by
 * design — the corpus is the spec, and {@code katexExpect}/{@code linterExpect} divergences are
 * tracked as TODOs to close. Never produces an ERROR; never blocks a save.
 */
public class LatexSyntaxLinter {

    private static final Pattern COMMAND = Pattern.compile("\\\\([a-zA-Z]+)");

    /** A pragmatic allowlist of KaTeX commands seen in the corpus. Expand as the corpus grows. */
    private static final Set<String> KNOWN = Set.of(
            "frac", "sqrt", "sum", "int", "prod", "lim", "infty", "partial", "nabla",
            "left", "right", "begin", "end", "text", "mathrm", "mathbf", "mathbb", "mathcal",
            "alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "nu", "pi",
            "rho", "sigma", "tau", "phi", "psi", "omega", "Delta", "Gamma", "Phi", "Omega",
            "cdot", "times", "div", "pm", "mp", "leq", "geq", "neq", "approx", "equiv",
            "rightarrow", "leftarrow", "Rightarrow", "cos", "sin", "tan", "log", "ln", "exp",
            "hat", "bar", "vec", "dot", "ddot", "overline", "underline", "binom", "cases", "matrix",
            "pmatrix", "bmatrix", "vmatrix", "quad", "qquad", "space", "displaystyle");

    public List<MathViolation> lint(final String latex) {
        final List<MathViolation> out = new ArrayList<>();
        if (latex == null || latex.isBlank()) { return out; }
        final MathSourceRange whole = new MathSourceRange(0, latex.length(), 1, 1, 1, latex.length() + 1);

        if (!bracesBalanced(latex)) {
            out.add(new MathViolation("math.syntax.unbalancedBraces", Severity.WARNING,
                    "Unbalanced { } braces.", whole));
        }
        if (count(latex, "\\left") != count(latex, "\\right")) {
            out.add(new MathViolation("math.syntax.leftRight", Severity.WARNING,
                    "\\left without a matching \\right (or vice versa).", whole));
        }
        if (!beginEndMatched(latex)) {
            out.add(new MathViolation("math.syntax.beginEnd", Severity.WARNING,
                    "\\begin{…} / \\end{…} environments do not match.", whole));
        }
        final Matcher m = COMMAND.matcher(latex);
        while (m.find()) {
            if (!KNOWN.contains(m.group(1))) {
                out.add(new MathViolation("math.syntax.unknownCommand", Severity.WARNING,
                        "Unknown/unsupported command \\" + m.group(1) + ".", whole));
                break;   // one unknown-command warning per expression is enough
            }
        }
        return out;
    }

    private static boolean bracesBalanced(final String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }   // skip escaped char (e.g. \{ \})
            if (c == '{') { depth++; }
            else if (c == '}') { if (--depth < 0) { return false; } }
        }
        return depth == 0;
    }

    private static int count(final String s, final String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static boolean beginEndMatched(final String s) {
        final Pattern env = Pattern.compile("\\\\(begin|end)\\{([^}]*)\\}");
        final Matcher m = env.matcher(s);
        final Deque<String> stack = new ArrayDeque<>();
        while (m.find()) {
            if ("begin".equals(m.group(1))) { stack.push(m.group(2)); }
            else { if (stack.isEmpty() || !stack.pop().equals(m.group(2))) { return false; } }
        }
        return stack.isEmpty();
    }
}

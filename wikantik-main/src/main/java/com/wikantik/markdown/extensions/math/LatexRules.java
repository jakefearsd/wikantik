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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, probe-validated static predicates for LaTeX syntax rules. Each method returns {@code true}
 * when the expression is considered <em>invalid</em> for that rule. These are the exact
 * implementations that measured 100% precision against KaTeX in {@code MathRuleProbeTest}, lifted
 * here so both the linter and the probe share the same logic.
 */
public final class LatexRules {

    private LatexRules() {}

    // -----------------------------------------------------------------------
    // ERROR-severity rules (100% precision, airtight)
    // -----------------------------------------------------------------------

    /**
     * Unbalanced curly braces, skipping escaped {@code \{} and {@code \}}.
     *
     * @return {@code true} if braces are unbalanced (depth goes negative or non-zero at end)
     */
    public static boolean unbalancedBraces(final String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '{') { depth++; }
            else if (c == '}') { if (--depth < 0) { return true; } }
        }
        return depth != 0;
    }

    private static final Pattern LEFT_TOKEN  = Pattern.compile("\\\\left(?![a-zA-Z])");
    private static final Pattern RIGHT_TOKEN = Pattern.compile("\\\\right(?![a-zA-Z])");

    /**
     * Token-form {@code \left}/{\code \right} mismatch. Only counts the delimiter tokens — not
     * followed by a letter — so {@code \rightarrow}, {@code \Rightarrow} etc. are excluded.
     *
     * @return {@code true} if the counts of {@code \left} and {@code \right} tokens differ
     */
    public static boolean leftRightMismatch(final String s) {
        final int lefts  = countMatches(LEFT_TOKEN,  s);
        final int rights = countMatches(RIGHT_TOKEN, s);
        return lefts != rights;
    }

    /**
     * Begin/end environment mismatch using a name-stack.
     *
     * @return {@code true} if any {@code \begin}/{\code \end} pair is unmatched or mis-named
     */
    public static boolean beginEndMismatch(final String s) {
        final Pattern env = Pattern.compile("\\\\(begin|end)\\{([^}]*)\\}");
        final Matcher m = env.matcher(s);
        final Deque<String> stack = new ArrayDeque<>();
        while (m.find()) {
            if ("begin".equals(m.group(1))) {
                stack.push(m.group(2));
            } else {
                if (stack.isEmpty() || !stack.pop().equals(m.group(2))) { return true; }
            }
        }
        return !stack.isEmpty();
    }

    /**
     * Empty script: a {@code ^} or {@code _} (unescaped) not followed by any argument. An argument
     * is a brace group {@code {…}}, a single character/command, or anything that is not end-of-string,
     * another script operator, or a closing delimiter. Specifically, {@code ^{}}, {@code ^x}, and
     * {@code ^\cmd} all count as having an argument and do NOT fire.
     *
     * @return {@code true} if a {@code ^} or {@code _} has no following argument
     */
    public static boolean emptyScript(final String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }  // skip escaped char
            if (c == '^' || c == '_') {
                // skip whitespace
                int j = i + 1;
                while (j < s.length() && s.charAt(j) == ' ') { j++; }
                if (j >= s.length()) { return true; }                     // end of string
                final char next = s.charAt(j);
                if (next == '^' || next == '_' || next == '}' || next == ')' || next == ']') {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Pattern DOUBLE_SUP = Pattern.compile("[^{]\\^[^{\\s]\\^");
    private static final Pattern DOUBLE_SUB = Pattern.compile("[^{]_[^{\\s]_");

    /**
     * Double script: detects {@code x^a^b} or {@code x_a_b} — a base with two consecutive
     * same-kind scripts where the first argument is a single unbraced token. Mixed scripts
     * {@code x^a_b} are VALID and do NOT fire.
     *
     * @return {@code true} if a double superscript or double subscript is detected
     */
    public static boolean doubleScript(final String s) {
        return DOUBLE_SUP.matcher(s).find() || DOUBLE_SUB.matcher(s).find();
    }

    // -----------------------------------------------------------------------
    // WARNING-severity rules (high confidence, savable)
    // -----------------------------------------------------------------------

    /**
     * Bare {@code &} used when not inside any {@code \begin…\end} block.
     *
     * @return {@code true} if {@code &} appears outside any environment
     */
    public static boolean ampOutsideEnv(final String s) {
        final Pattern envPat = Pattern.compile("\\\\(begin|end)\\{[^}]*\\}|&");
        final Matcher m = envPat.matcher(s);
        int depth = 0;
        while (m.find()) {
            final String hit = m.group();
            if (hit.startsWith("\\begin")) { depth++; }
            else if (hit.startsWith("\\end")) { depth = Math.max(0, depth - 1); }
            else if ("&".equals(hit) && depth == 0) { return true; }
        }
        return false;
    }

    /**
     * {@code \sqrt[} not followed by a matching {@code ]} before the radicand group.
     *
     * @return {@code true} if a malformed {@code \sqrt} optional argument is detected
     */
    public static boolean sqrtBadOptional(final String s) {
        int i = 0;
        while ((i = s.indexOf("\\sqrt[", i)) >= 0) {
            int j = i + 6;
            boolean foundClose = false;
            while (j < s.length() && s.charAt(j) != '{') {
                if (s.charAt(j) == ']') { foundClose = true; break; }
                j++;
            }
            if (!foundClose) { return true; }
            i += 6;
        }
        return false;
    }

    /**
     * {@code \frac} not followed by exactly two complete brace groups. Uses a proper recursive
     * brace-group scanner (not a flat regex) so {@code \frac{\frac{a}{b}}{c}} does NOT fire.
     *
     * @return {@code true} if any {@code \frac} lacks two complete brace arguments
     */
    public static boolean fracArity(final String s) {
        int i = 0;
        while (i < s.length()) {
            final int fracPos = s.indexOf("\\frac", i);
            if (fracPos < 0) { break; }
            // skip \frac, then optional whitespace
            int pos = fracPos + 5;
            while (pos < s.length() && s.charAt(pos) == ' ') { pos++; }
            // attempt to consume first argument ({…} group, \command, or bare token)
            final int afterFirst = consumeArg(s, pos);
            if (afterFirst < 0) { return true; }   // no first argument
            int pos2 = afterFirst;
            while (pos2 < s.length() && s.charAt(pos2) == ' ') { pos2++; }
            // attempt to consume second argument
            final int afterSecond = consumeArg(s, pos2);
            if (afterSecond < 0) { return true; }  // no second argument
            i = fracPos + 5;
        }
        return false;
    }

    /**
     * Attempts to consume a complete {@code {…}} brace group starting at {@code pos}, handling
     * arbitrary nesting. Returns the index just after the closing {@code }}, or {@code -1} if
     * {@code pos} does not point at {@code {}.
     */
    static int consumeBraceGroup(final String s, final int pos) {
        if (pos >= s.length() || s.charAt(pos) != '{') { return -1; }
        int depth = 0;
        for (int i = pos; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }   // skip escaped char
            if (c == '{') { depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0) { return i + 1; }
            }
        }
        return -1;  // unclosed group
    }

    /**
     * Consumes a single LaTeX argument starting at {@code pos}: a {@code {…}} group, a control
     * sequence ({@code \cmd}), or a single bare token (e.g. {@code b}, a digit). Returns the index
     * after the argument, or -1 when no valid argument is present. This is why {@code \frac{a}b}
     * (braced first arg, bare second arg — valid KaTeX) does NOT trip {@link #fracArity}.
     */
    static int consumeArg(final String s, final int pos) {
        if (pos >= s.length()) { return -1; }
        final char c = s.charAt(pos);
        if (c == '{') { return consumeBraceGroup(s, pos); }
        if (c == '}' || c == ' ') { return -1; }            // not a valid argument start
        if (c == '\\') {                                     // control sequence
            int i = pos + 1;
            if (i < s.length() && Character.isLetter(s.charAt(i))) {
                while (i < s.length() && Character.isLetter(s.charAt(i))) { i++; }
                return i;
            }
            return Math.min(pos + 2, s.length());            // control symbol, e.g. \% \,
        }
        return pos + 1;                                      // single bare token
    }

    // -----------------------------------------------------------------------
    // WARNING-severity: unknown command
    // -----------------------------------------------------------------------

    /** Comprehensive allowlist of KaTeX commands. Expand as the corpus grows. */
    public static final Set<String> KNOWN_COMMANDS = Set.of(
            // core structural
            "frac", "dfrac", "tfrac", "sqrt", "binom", "dbinom", "tbinom",
            "left", "right", "begin", "end", "text", "operatorname",
            // decorations
            "hat", "bar", "vec", "dot", "ddot", "overline", "underline",
            "widehat", "widetilde", "mathring", "grave", "acute", "breve", "check", "tilde",
            "overrightarrow", "overleftarrow",
            // font
            "mathrm", "mathbf", "mathbb", "mathcal", "mathfrak", "mathscr", "mathsf", "mathtt",
            "boldsymbol", "textbf", "textit", "textrm",
            // style
            "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
            // big operators
            "sum", "prod", "coprod", "int", "iint", "iiint", "oint",
            "bigcup", "bigcap", "bigoplus", "bigotimes", "bigvee", "bigwedge",
            // common trig / functions
            "cos", "sin", "tan", "sec", "csc", "cot",
            "arcsin", "arccos", "arctan",
            "sinh", "cosh", "tanh", "coth",
            "log", "ln", "exp", "lim", "max", "min", "sup", "inf",
            "det", "deg", "dim", "gcd", "hom", "ker", "arg",
            "mod", "bmod", "pmod",
            // constants / symbols
            "infty", "partial", "nabla", "hbar", "ell", "Re", "Im", "aleph",
            "forall", "exists", "nexists", "emptyset", "varnothing",
            // Greek lower
            "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon",
            "theta", "vartheta", "lambda", "mu", "nu", "xi", "pi", "varpi",
            "rho", "varrho", "sigma", "varsigma", "tau", "upsilon",
            "phi", "varphi", "chi", "psi", "omega",
            "eta", "iota", "kappa",
            // Greek upper
            "Delta", "Gamma", "Phi", "Omega", "Pi", "Psi", "Sigma", "Theta",
            "Lambda", "Xi", "Upsilon",
            // relations
            "cdot", "times", "div", "pm", "mp",
            "leq", "geq", "neq", "approx", "equiv", "sim", "simeq", "cong", "propto",
            "doteq", "ll", "gg", "asymp", "prec", "succ", "preceq", "succeq",
            "perp", "parallel", "angle", "models", "vdash",
            // set / logic
            "in", "notin", "subset", "supset", "subseteq", "supseteq",
            "cup", "cap", "oplus", "otimes", "odot", "wedge", "vee",
            "neg", "lnot", "land", "lor", "top", "bot",
            // arrows
            "rightarrow", "leftarrow", "Rightarrow", "Leftarrow",
            "leftrightarrow", "Leftrightarrow",
            "longrightarrow", "Longrightarrow", "mapsto", "mapsfrom",
            "to",
            // delimiters
            "langle", "rangle", "lfloor", "rfloor", "lceil", "rceil",
            // misc symbols
            "dagger", "ddagger", "star", "ast", "circ", "bullet",
            "triangle",
            // dots
            "dots", "ldots", "cdots", "vdots", "ddots",
            // environments
            "cases", "matrix", "pmatrix", "bmatrix", "vmatrix",
            // spacing
            "quad", "qquad", "space",
            // stacking / layout
            "substack", "overset", "underset", "stackrel",
            "underbrace", "overbrace",
            "not",
            // phantom
            "phantom", "hphantom", "vphantom",
            // color
            "color", "textcolor",
            // probe-surfaced false positives + common omissions (keep WARNING precision high)
            "zeta",                                              // basic Greek — was missing
            "xrightarrow", "xleftarrow",                          // extensible arrows
            "iff", "implies", "impliedby", "gets",
            "setminus", "smallsetminus", "mid", "nmid", "colon",
            "vert", "Vert", "lvert", "rvert", "lVert", "rVert",
            "prime", "therefore", "because",
            "le", "ge", "ne",                                     // KaTeX aliases of leq/geq/neq
            "ncong", "nleq", "ngeq", "nsubseteq", "nsupseteq",
            "blacksquare", "square", "Box", "triangleq",
            "limsup", "liminf",
            // big-delimiter sizing
            "big", "Big", "bigg", "Bigg",
            "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr", "middle"
    );

    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\\\([a-zA-Z]+)");

    /**
     * Detects any backslash command not in {@link #KNOWN_COMMANDS}.
     *
     * @return {@code true} if any unknown command is found
     */
    public static boolean unknownCommand(final String s) {
        final Matcher m = COMMAND_PATTERN.matcher(s);
        while (m.find()) {
            if (!KNOWN_COMMANDS.contains(m.group(1))) { return true; }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static int countMatches(final Pattern p, final String s) {
        int n = 0;
        final Matcher m = p.matcher(s);
        while (m.find()) { n++; }
        return n;
    }
}

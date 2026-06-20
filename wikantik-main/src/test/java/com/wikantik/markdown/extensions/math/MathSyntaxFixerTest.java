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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathSyntaxFixerTest {

    private static final MathStructureValidator VALIDATOR = new MathStructureValidator();

    private static boolean hasInlineProse(final String body) {
        return VALIDATOR.validate(body).stream().anyMatch(v -> "math.inline.prose".equals(v.code()));
    }

    @Test
    void clearsInlineProseWarningForCurrency() {
        final String bad = "He earned $100 and then $200 on the trade.";
        assertTrue(hasInlineProse(bad), "precondition: the unescaped currency must trip the validator");

        final String fixed = MathSyntaxFixer.escapeCurrency(bad);
        assertFalse(hasInlineProse(fixed), "the inline-prose warning must be gone after the fix");
        assertTrue(fixed.contains("\\$100"), "the flagged currency $ must be escaped");
    }

    @Test
    void leavesNumberLedInlineMathUntouched() {
        // The bug that nearly corrupted prod: legitimate inline math that opens on a DIGIT.
        // The validator never flags these (no prose stopword / has a \\command), so neither may the fixer.
        final String math = "The space is $2^{256}$, the angle is $90^\\circ$, and the count is $10^{12}$.";
        assertFalse(hasInlineProse(math), "precondition: number-led math is not flagged by the validator");
        assertSame(math, MathSyntaxFixer.escapeCurrency(math), "number-led inline math must be left byte-identical");
    }

    @Test
    void mixedCurrencyAndNumberLedMathEscapesOnlyCurrency() {
        // A currency $ that mis-pairs with a number-led math $ across prose. Escaping the OPENING
        // currency must NOT break the math span that follows.
        final String mixed = "It costs $100 and the value $2^{256}$ holds.";
        assertTrue(hasInlineProse(mixed), "precondition: the mis-paired currency trips the validator");

        final String fixed = MathSyntaxFixer.escapeCurrency(mixed);
        assertTrue(fixed.contains("\\$100"), "the currency must be escaped");
        assertTrue(fixed.contains("$2^{256}$"), "the real math span must remain intact");
        assertFalse(hasInlineProse(fixed), "and the warning must clear");
    }

    @Test
    void leavesRealInlineMathUntouched() {
        final String math = "Duration $D$ and convexity $C$ relate price to yield via $\\phi$.";
        assertSame(math, MathSyntaxFixer.escapeCurrency(math), "letter/backslash-led math is not currency");
    }

    @Test
    void leavesDisplayMathUntouched() {
        final String display = "Before.\n\n$$\n5x + 3y = 10\n$$\n\nAfter.";
        assertSame(display, MathSyntaxFixer.escapeCurrency(display), "$$ display delimiters must not be escaped");
    }

    @Test
    void leavesCodeRegionsUntouched() {
        final String fenced = "Text.\n\n```\nprice = $100 and $200\n```\n\nMore.";
        assertSame(fenced, MathSyntaxFixer.escapeCurrency(fenced), "fenced code is literal");

        final String inline = "Run `echo $100 and $200` in the shell.";
        assertSame(inline, MathSyntaxFixer.escapeCurrency(inline), "inline code is literal");
    }

    @Test
    void leavesAlreadyEscapedCurrencyUntouched() {
        final String escaped = "It cost \\$35 then \\$70 and the rate doubled.";
        assertSame(escaped, MathSyntaxFixer.escapeCurrency(escaped));
    }

    @Test
    void isIdempotent() {
        final String bad = "He paid $50 for the first and $60 for the second on each call.";
        final String once = MathSyntaxFixer.escapeCurrency(bad);
        final String twice = MathSyntaxFixer.escapeCurrency(once);
        assertEquals(once, twice, "running the fixer again must be a no-op");
        assertFalse(hasInlineProse(once));
    }

    @Test
    void noDollarSignIsAFastNoOp() {
        final String plain = "No money here, just words and numbers like 100 and 200.";
        assertSame(plain, MathSyntaxFixer.escapeCurrency(plain));
    }

    private static boolean hasDisplayNotIsolated(final String body) {
        return VALIDATOR.validate(body).stream().anyMatch(v -> "math.display.notIsolated".equals(v.code()));
    }

    @Test
    void isolatesSingleLineDisplayMathAndClearsTheError() {
        final String bad = "The formula is:\n$$ SS = Z \\times \\sigma $$\nwhere Z is the factor.";
        assertTrue(hasDisplayNotIsolated(bad), "precondition: the single-line $$ trips the validator");

        final String fixed = MathSyntaxFixer.isolateDisplayMath(bad);
        assertFalse(hasDisplayNotIsolated(fixed), "the notIsolated error must be gone");
        assertTrue(fixed.contains("\n$$\nSS = Z \\times \\sigma\n$$\n"), "the $$ delimiters must be on their own lines");
    }

    @Test
    void leavesProperlyIsolatedDisplayMathUntouched() {
        final String good = "Intro.\n\n$$\nx = y + z\n$$\n\nOutro.";
        assertSame(good, MathSyntaxFixer.isolateDisplayMath(good), "already-isolated display math must not change");
    }

    @Test
    void leavesDisplayMathInsideCodeFencesUntouched() {
        final String fenced = "Example:\n\n```\n$$ x = y $$\n```\n\nEnd.";
        assertSame(fenced, MathSyntaxFixer.isolateDisplayMath(fenced), "a $$ inside code is a literal example");
    }

    @Test
    void escapesAcrossAChainOfCurrencyAmounts() {
        // Iteration must walk a run of currency amounts, escaping each prose-flagged opening.
        final String chain = "Spent $100 on food and $200 on rent and saved $300 for the year.";
        final String fixed = MathSyntaxFixer.escapeCurrency(chain);
        assertFalse(hasInlineProse(fixed), "no inline-prose warning may remain on a pure-currency chain");
        assertTrue(fixed.contains("\\$100") && fixed.contains("\\$200"), "interior currency must be escaped");
    }
}

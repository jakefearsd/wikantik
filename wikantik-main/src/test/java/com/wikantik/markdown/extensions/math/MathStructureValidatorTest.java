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
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathStructureValidatorTest {

    private final MathStructureValidator validator = new MathStructureValidator();

    private boolean hasError(final List<MathViolation> v, final String code) {
        return v.stream().anyMatch(x -> x.severity() == Severity.ERROR && x.code().equals(code));
    }

    @Test
    void blocksInlineGluedDisplayWithCommand() {
        // The FastenerEngineering bug.
        final String body = "friction:$$\\text{T} = \\text{F}_p \\left( x \\right)$$Where";
        assertTrue(hasError(validator.validate(body), "math.display.notIsolated"));
    }

    @Test
    void blocksUnterminatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\nno closer here";
        assertTrue(hasError(validator.validate(body), "math.display.unterminated"));
    }

    @Test
    void allowsLineIsolatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\n$$\noutro";
        assertEquals(List.of(), validator.validate(body));
    }

    private boolean hasWarning(final List<MathViolation> v, final String code) {
        return v.stream().anyMatch(x -> x.severity() == Severity.WARNING && x.code().equals(code));
    }

    @Test
    void warnsOnCurrencyProse() {
        // Was allowsCurrencyProse: currency $ wrapping prose now WARNs (never blocks).
        assertTrue(hasWarning(validator.validate("It costs $5 and $10 to ship, total $15."),
                "math.inline.prose"));
    }

    @Test
    void warnsOnCurrencyWrappingClause() {
        assertTrue(hasWarning(validator.validate("You save $500 on every $1000 spent."),
                "math.inline.prose"));
        assertTrue(hasWarning(validator.validate("gold hit $3,800 per ounce and silver $48 today"),
                "math.inline.prose"));
    }

    @Test
    void doesNotWarnOnRealInlineMath() {
        for (final String body : new String[]{
                "the sum $x + y$ and product $a \\cdot b$",
                "let $f(n)$ be the cost and $g(n)$ the heuristic",
                "the first $3$ terms with $a_1 + b_2$",
                "compute $max(x, y)$ then $\\frac{a}{b}$",
                "Einstein wrote $E = mc^2$ in 1905"}) {
            assertEquals(List.of(), validator.validate(body).stream()
                    .filter(x -> x.code().equals("math.inline.prose")).toList(),
                    "should not warn on: " + body);
        }
    }

    @Test
    void doesNotWarnOnEscapedCurrency() {
        assertEquals(List.of(), validator.validate("costs \\$500 and \\$1000 per unit").stream()
                .filter(x -> x.code().equals("math.inline.prose")).toList());
    }

    @Test
    void doesNotWarnOnProseBetweenClosedPairs() {
        assertEquals(List.of(), validator.validate("let $g(n)$ be the cost from start and $h(n)$ the rest")
                .stream().filter(x -> x.code().equals("math.inline.prose")).toList());
    }

    @Test
    void ignoresDisplayInsideCodeFence() {
        final String body = "```\nfriction:$$\\text{T}=x$$Where\n```";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void warnsOnEmptyDisplay() {
        final String body = "a\n$$\n$$\nb";
        final List<MathViolation> v = validator.validate(body);
        assertTrue(v.stream().anyMatch(x -> x.severity() == Severity.WARNING
                && x.code().equals("math.display.empty")));
    }

    @Test
    void blocksSingleLineDisplayWithCommand() {
        assertTrue(hasError(validator.validate("intro\n$$E = \\frac{a}{b}$$\noutro"),
                "math.display.notIsolated"));
    }

    @Test
    void blocksSingleLineDisplayWithoutBackslash() {
        // The ActorModelProgramming class — no backslash, previously missed.
        assertTrue(hasError(validator.validate("intro\n$$S_{t+1} = f(S_t, M)$$\nouttro"),
                "math.display.notIsolated"));
    }

    @Test
    void blocksGluedOpenIsolatedClose() {
        assertTrue(hasError(validator.validate("text $$\n x = y \n$$\nrest"),
                "math.display.notIsolated"));
    }
}

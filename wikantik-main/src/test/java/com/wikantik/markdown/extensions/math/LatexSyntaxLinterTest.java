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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatexSyntaxLinterTest {

    private final LatexSyntaxLinter linter = new LatexSyntaxLinter();

    private boolean hasCode(final String latex, final String code) {
        return linter.lint(latex).stream().anyMatch(v -> v.code().equals(code));
    }

    private boolean hasError(final String latex, final String code) {
        return linter.lint(latex).stream()
                .anyMatch(v -> v.code().equals(code) && v.severity() == Severity.ERROR);
    }

    private boolean hasWarning(final String latex, final String code) {
        return linter.lint(latex).stream()
                .anyMatch(v -> v.code().equals(code) && v.severity() == Severity.WARNING);
    }

    // -----------------------------------------------------------------------
    // Clean expressions — no violations
    // -----------------------------------------------------------------------

    @Test
    void cleanLatexHasNoViolations() {
        assertTrue(linter.lint("\\frac{a}{b} + \\sqrt{x^2}").isEmpty());
        assertTrue(linter.lint("\\left( \\frac{a}{b} \\right)").isEmpty());
        assertTrue(linter.lint("\\begin{matrix} a & b \\end{matrix}").isEmpty());
    }

    // -----------------------------------------------------------------------
    // ERROR rules
    // -----------------------------------------------------------------------

    @Test
    void unbalancedBracesIsError() {
        assertTrue(hasError("\\frac{a}{b", "math.syntax.unbalancedBraces"));
    }

    @Test
    void leftWithoutRightIsError() {
        assertTrue(hasError("\\left( x", "math.syntax.leftRight"));
    }

    @Test
    void beginWithoutEndIsError() {
        assertTrue(hasError("\\begin{matrix} a", "math.syntax.beginEnd"));
    }

    @Test
    void mismatchedBeginEndIsError() {
        assertTrue(hasError("\\begin{matrix} a \\end{pmatrix}", "math.syntax.beginEnd"));
    }

    @Test
    void emptyScriptSuperIsError() {
        // bare ^ at end of string
        assertTrue(hasError("x^", "math.syntax.emptyScript"),
                "bare ^ with nothing after should be ERROR");
    }

    @Test
    void emptyScriptSubIsError() {
        // bare _ at end of string
        assertTrue(hasError("x_", "math.syntax.emptyScript"),
                "bare _ with nothing after should be ERROR");
    }

    @Test
    void emptyScriptWithArgDoesNotFire() {
        // ^ followed by a brace group — even empty — is valid
        assertFalse(hasCode("x^{}", "math.syntax.emptyScript"),
                "^{} (empty brace group) must NOT fire emptyScript");
        assertFalse(hasCode("x^2", "math.syntax.emptyScript"),
                "^2 must NOT fire emptyScript");
        assertFalse(hasCode("x^\\alpha", "math.syntax.emptyScript"),
                "^\\cmd must NOT fire emptyScript");
        assertFalse(hasCode("x_{i}", "math.syntax.emptyScript"),
                "_{i} must NOT fire emptyScript");
    }

    @Test
    void doubleScriptSuperIsError() {
        assertTrue(hasError("x^a^b", "math.syntax.doubleScript"),
                "x^a^b should be ERROR doubleScript");
    }

    @Test
    void doubleScriptSubIsError() {
        assertTrue(hasError("x_a_b", "math.syntax.doubleScript"),
                "x_a_b should be ERROR doubleScript");
    }

    @Test
    void mixedScriptDoesNotFireDoubleScript() {
        // x^a_b is valid LaTeX
        assertFalse(hasCode("x^a_b", "math.syntax.doubleScript"),
                "x^a_b (mixed scripts) must NOT fire doubleScript");
    }

    // -----------------------------------------------------------------------
    // WARNING rules
    // -----------------------------------------------------------------------

    @Test
    void ampOutsideEnvIsWarning() {
        assertTrue(hasWarning("a & b", "math.syntax.ampOutsideEnv"),
                "& outside environment should be WARNING");
    }

    @Test
    void ampInsideEnvDoesNotFire() {
        assertFalse(hasCode("\\begin{matrix} a & b \\end{matrix}", "math.syntax.ampOutsideEnv"),
                "& inside matrix must NOT fire ampOutsideEnv");
    }

    @Test
    void sqrtBadOptionalIsWarning() {
        assertTrue(hasWarning("\\sqrt[", "math.syntax.sqrtBadOptional"),
                "\\sqrt[ without ] should be WARNING");
    }

    @Test
    void sqrtGoodOptionalDoesNotFire() {
        assertFalse(hasCode("\\sqrt[3]{x}", "math.syntax.sqrtBadOptional"),
                "\\sqrt[3]{x} must NOT fire sqrtBadOptional");
    }

    @Test
    void fracArityOneArgIsWarning() {
        assertTrue(hasWarning("\\frac{a}", "math.syntax.fracArity"),
                "\\frac with one arg should be WARNING");
    }

    @Test
    void fracArityNoArgsIsWarning() {
        assertTrue(hasWarning("\\frac", "math.syntax.fracArity"),
                "\\frac with no args should be WARNING");
    }

    @Test
    void fracArityNestedDoesNotFire() {
        // The recursive scanner must handle nested fracs without FP
        assertFalse(hasCode("\\frac{\\frac{a}{b}}{c}", "math.syntax.fracArity"),
                "\\frac{\\frac{a}{b}}{c} must NOT fire fracArity");
    }

    // -----------------------------------------------------------------------
    // unknownCommand WARNING + allowlist
    // -----------------------------------------------------------------------

    @Test
    void unknownCommandIsWarning() {
        assertTrue(hasWarning("\\frooble{x}", "math.syntax.unknownCommand"));
    }

    @Test
    void knownCommandsPass() {
        assertFalse(hasCode("\\alpha \\cdot \\beta", "math.syntax.unknownCommand"));
    }

    @Test
    void widenedCommandsPass() {
        // Check a selection of newly-added allowlist commands
        assertFalse(hasCode("\\lim_{n \\to \\infty} a_n", "math.syntax.unknownCommand"),
                "\\lim and \\to should be known");
        assertFalse(hasCode("\\mathbb{R}", "math.syntax.unknownCommand"),
                "\\mathbb should be known");
        assertFalse(hasCode("\\mapsto", "math.syntax.unknownCommand"),
                "\\mapsto should be known");
        assertFalse(hasCode("\\lfloor x \\rfloor", "math.syntax.unknownCommand"),
                "\\lfloor and \\rfloor should be known");
    }

    @Test
    void rightarrowAloneDoesNotFireLeftRight() {
        // \rightarrow must not be confused with \right by the token rule
        assertFalse(hasCode("\\rightarrow", "math.syntax.leftRight"),
                "\\rightarrow alone must NOT fire leftRightMismatch");
        assertFalse(hasCode("\\Rightarrow", "math.syntax.leftRight"),
                "\\Rightarrow alone must NOT fire leftRightMismatch");
    }
}
